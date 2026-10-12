package com.networkalert.server;

import com.google.gson.Gson;
import com.networkalert.model.AlertConfig;
import com.networkalert.model.CheckResult;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetStateMachine;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.ProtocolException;
import com.networkalert.protocol.messages.ConsoleCommand;
import com.networkalert.protocol.messages.ConsoleResponse;
import com.networkalert.protocol.messages.DisconnectMessage;
import com.networkalert.protocol.messages.ForceCheckRequest;
import com.networkalert.protocol.messages.ForceCheckResponse;
import com.networkalert.protocol.messages.HeartbeatPing;
import com.networkalert.protocol.messages.RegisterRequest;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.protocol.messages.TargetDto;
import com.networkalert.repository.ConfigRepository;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.server.ingest.IngestMetrics;
import com.networkalert.server.ingest.ResultIngestor;
import com.networkalert.server.ingest.ResultSink;
import com.networkalert.service.AlertService;
import com.networkalert.util.LoggerUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Máy chủ trung tâm giám sát mạng phân tán NCAS Server (NCAS-P v1).
 * <p>
 * Lắng nghe kết nối TCP từ các NCAS Agent và NCAS Console quản trị.
 * Thực hiện:
 * - Xác thực token kết nối
 * - Quản lý phiên và theo dõi nhịp tim (Agent Heartbeat)
 * - Tự động phân bổ / tái cân bằng mục tiêu giám sát (Target Distributor)
 * - Thu thập kết quả đo kiểm, cập nhật State Machine, ghi log sự cố và kích hoạt cảnh báo đa kênh
 * - Phục vụ các lệnh điều khiển từ Console CLI
 * </p>
 */
public class NcasServer {
    public static final int DEFAULT_PORT = 9876;
    private static final Gson GSON = new Gson();

    private final int port;
    private final String bindHost;
    private final String authToken;

    private final TargetRepository targetRepository;
    private final IncidentRepository incidentRepository;
    private final ConfigRepository configRepository;
    private final AlertService alertService;

    private final AgentManager agentManager;
    private final TargetDistributor targetDistributor;
    private final ResultIngestor resultIngestor;

    private ServerSocket serverSocket;
    private final ExecutorService clientWorkerPool;
    private final ScheduledExecutorService maintenanceScheduler;

    private final List<AgentSession> consoleSessions = new CopyOnWriteArrayList<>();
    private final List<java.util.function.BiConsumer<ServerTarget, CheckResult>> checkResultListeners = new CopyOnWriteArrayList<>();

    public void addCheckResultListener(java.util.function.BiConsumer<ServerTarget, CheckResult> listener) {
        if (listener != null) checkResultListeners.add(listener);
    }

    private volatile boolean running = false;
    private final Clock clock;
    private final Instant startTime;
    private static final java.util.concurrent.atomic.AtomicInteger WORKER_THREAD_COUNTER = new java.util.concurrent.atomic.AtomicInteger(1);

    // Bảo mật & Xác thực token phân tán (ERRATA §E2.2 - §E2.6)
    private boolean allowLegacyAuth = true; // Bật mặc định trong constructor cho bộ test v1 hiện có
    private boolean tlsEnabled = false; // Bật mặc định khi chạy CLI/production (ERRATA §E2.1)
    private String keystorePath = "data/tls/keystore.p12";
    private char[] keystorePassword = null;
    private String certificateFingerprint = null;
    private javax.net.ssl.SSLContext sslContext = null;
    private final com.networkalert.security.AgentTokenManager agentTokenManager;
    private final com.networkalert.security.ChallengeManager challengeManager;
    private final com.networkalert.security.ConnectionLimiter connectionLimiter;

    public NcasServer(int port, String bindHost, String authToken) {
        this(port, bindHost, authToken, 30_000L, Clock.systemUTC());
    }

    public NcasServer(int port, String bindHost, String authToken, long heartbeatTimeoutMs) {
        this(port, bindHost, authToken, heartbeatTimeoutMs, Clock.systemUTC());
    }

    public NcasServer(int port, String bindHost, String authToken, long heartbeatTimeoutMs, Clock clock) {
        this(port, bindHost, authToken, heartbeatTimeoutMs, clock, null);
    }

    public NcasServer(int port, String bindHost, String authToken, long heartbeatTimeoutMs, Clock clock, com.networkalert.server.ingest.ResultSink resultSink) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.startTime = Instant.now(this.clock);
        this.port = port >= 0 ? port : DEFAULT_PORT;
        this.bindHost = (bindHost != null && !bindHost.isEmpty()) ? bindHost : "0.0.0.0";

        // Khởi tạo phân hệ an ninh & quản lý Token phân tán
        this.agentTokenManager = new com.networkalert.security.AgentTokenManager(java.nio.file.Path.of("data/agents.json"), this.clock);
        this.challengeManager = new com.networkalert.security.ChallengeManager(this.clock);
        this.connectionLimiter = new com.networkalert.security.ConnectionLimiter();

        // Khởi tạo tầng dữ liệu và cảnh báo
        this.targetRepository = new TargetRepository();
        this.incidentRepository = new IncidentRepository();
        this.configRepository = new ConfigRepository();
        this.alertService = new AlertService(incidentRepository, configRepository, this.clock);

        AlertConfig alertConfig = alertService.getConfig();
        this.authToken = (authToken != null && !authToken.isEmpty())
                ? authToken
                : (alertConfig.getApiToken() != null && !alertConfig.getApiToken().isEmpty() ? alertConfig.getApiToken() : "NCAS-SECRET-TOKEN");

        // Khởi tạo các phân hệ máy chủ
        this.targetDistributor = new TargetDistributor(targetRepository);
        this.agentManager = new AgentManager(alertService, heartbeatTimeoutMs > 0 ? heartbeatTimeoutMs : 30_000L, 10, this.clock);
        this.agentManager.setAgentTokenManager(this.agentTokenManager);
        this.agentManager.setTargetDistributor(this.targetDistributor);
        this.targetDistributor.setAgentManager(this.agentManager);

        // Khởi tạo đường ống Ingestor xử lý kết quả đo kiểm (ERRATA §E5.1, Task T1.6 & Task T3.3)
        com.networkalert.server.ingest.ResultSink sink = (resultSink != null)
                ? resultSink
                : new com.networkalert.server.ingest.JdbcResultSink(com.networkalert.repository.DatabaseManager.getInstance(), this.clock);
        this.agentManager.setResultSink(sink);
        this.resultIngestor = new com.networkalert.server.ingest.ResultIngestor(
                this.targetDistributor,
                sink,
                this.alertService,
                new com.networkalert.server.ingest.IngestMetrics(),
                this.clock
        );
        this.resultIngestor.addCheckResultListener((t, cr) -> {
            for (java.util.function.BiConsumer<ServerTarget, CheckResult> l : checkResultListeners) {
                try {
                    l.accept(t, cr);
                } catch (Exception ignored) {}
            }
        });
        this.resultIngestor.addConsoleBroadcaster(this::broadcastToConsoles);

        this.clientWorkerPool = Executors.newCachedThreadPool(
                new com.networkalert.util.NamedThreadFactory("NCAS-ClientWorker", true));

        this.maintenanceScheduler = Executors.newSingleThreadScheduledExecutor(
                new com.networkalert.util.NamedThreadFactory("NCAS-ServerMaintenance", true));
    }

    public Clock getClock() {
        return clock;
    }

    public com.networkalert.security.AgentTokenManager getAgentTokenManager() {
        return agentTokenManager;
    }

    public com.networkalert.security.ChallengeManager getChallengeManager() {
        return challengeManager;
    }

    public com.networkalert.security.ConnectionLimiter getConnectionLimiter() {
        return connectionLimiter;
    }

    public boolean isAllowLegacyAuth() {
        return allowLegacyAuth;
    }

    public void setAllowLegacyAuth(boolean allowLegacyAuth) {
        this.allowLegacyAuth = allowLegacyAuth;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public void setKeystorePassword(char[] keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public String getCertificateFingerprint() {
        return certificateFingerprint;
    }

    public void setSslContext(javax.net.ssl.SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    /**
     * Khởi động máy chủ NCAS Server
     */
    public synchronized void start() throws IOException {
        if (running) return;
        running = true;

        // 1. Tải danh sách mục tiêu giám sát từ CSDL
        targetDistributor.loadTargets();

        // 2. Mở ServerSocket lắng nghe kết nối (TLS hoặc Plain TCP)
        if (tlsEnabled) {
            try {
                if (sslContext == null) {
                    java.nio.file.Path ksPath = java.nio.file.Path.of(keystorePath);
                    if (!java.nio.file.Files.exists(ksPath)) {
                        char[] pass = keystorePassword != null ? keystorePassword : "changeit".toCharArray();
                        com.networkalert.security.TlsContextFactory.generateSelfSignedKeystore(ksPath, pass, "ncas-server", 365);
                    }
                    this.sslContext = com.networkalert.security.TlsContextFactory.createServerSSLContext(ksPath, keystorePassword);
                }
                this.certificateFingerprint = com.networkalert.security.TlsContextFactory.getCertificateFingerprint(
                        java.nio.file.Path.of(keystorePath), keystorePassword);
                javax.net.ssl.SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
                javax.net.ssl.SSLServerSocket sslServerSocket = (javax.net.ssl.SSLServerSocket) ssf.createServerSocket();
                sslServerSocket.setReuseAddress(true);
                com.networkalert.security.TlsContextFactory.configureServerSocket(sslServerSocket);
                sslServerSocket.bind(new InetSocketAddress(bindHost, port));
                this.serverSocket = sslServerSocket;
            } catch (Exception e) {
                throw new IOException("Không thể khởi động NCAS Server qua TLS: " + e.getMessage(), e);
            }
        } else {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindHost, port));
        }

        LoggerUtil.info(String.format("======================================================================"));
        LoggerUtil.info(String.format("🚀 NCAS DISTRIBUTED MONITORING SERVER v1.0 ĐÃ KHỞI ĐỘNG!"));
        LoggerUtil.info(String.format("   • Địa chỉ lắng nghe: %s:%d", bindHost, port));
        if (tlsEnabled) {
            LoggerUtil.info(String.format("   • Kênh an toàn: TLS 1.2/1.3 (Fingerprint: %s)", certificateFingerprint));
        } else {
            LoggerUtil.warn("   • Cảnh báo an ninh: Đang chạy ở chế độ --no-tls!");
        }
        LoggerUtil.info(String.format("   • Auth Token: %s", (authToken != null && !authToken.isEmpty()) ? "*** (Masked)" : "None"));
        LoggerUtil.info(String.format("   • Legacy Auth v1: %b", allowLegacyAuth));
        LoggerUtil.info(String.format("   • Số mục tiêu nạp: %d", targetDistributor.getAllTargets().size()));
        LoggerUtil.info(String.format("======================================================================"));

        // 3. Luồng chính chấp nhận kết nối TCP (Accept Loop)
        Thread acceptThread = com.networkalert.util.NamedThreadFactory.createThread("NCAS-AcceptLoop", this::runAcceptLoop, true);
        acceptThread.start();

        // 4. Lên lịch in bảng trạng thái ASCII định kỳ mỗi 30 giây
        maintenanceScheduler.scheduleWithFixedDelay(this::printStatusDashboard, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Vòng lặp chấp nhận kết nối TCP của các Agent và Console
     */
    private void runAcceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                String remoteIp = clientSocket.getInetAddress() != null ? clientSocket.getInetAddress().getHostAddress() : "unknown";
                if (connectionLimiter != null && !connectionLimiter.tryAcquirePreAuth(remoteIp)) {
                    try { clientSocket.close(); } catch (Exception ignored) {}
                    continue;
                }
                clientWorkerPool.submit(() -> {
                    try {
                        handleClientSocket(clientSocket);
                    } finally {
                        if (connectionLimiter != null) {
                            connectionLimiter.releasePreAuth(remoteIp);
                        }
                    }
                });
            } catch (SocketException e) {
                if (!running) break; // Server đã đóng chủ động
                LoggerUtil.warn("[NcasServer] SocketException trong Accept Loop: " + e.getMessage());
            } catch (Exception e) {
                if (running) {
                    LoggerUtil.error("[NcasServer] Lỗi chấp nhận kết nối client: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Xử lý luồng kết nối của từng client (Agent hoặc Console)
     */
    private void handleClientSocket(Socket socket) {
        AgentSession session = null;
        try {
            session = new AgentSession(socket);
            LoggerUtil.info(String.format("[NcasServer] Kết nối mới từ: %s", session.getRemoteAddress()));

            // Vòng lặp đọc và giải mã các Frame giao thức NCAS-P
            while (running && !session.isClosed()) {
                Frame frame = session.readNextFrame();
                if (frame == null) {
                    // Đã đóng kết nối từ phía client (EOF)
                    break;
                }

                dispatchFrame(session, frame);
            }
        } catch (ProtocolException pe) {
            LoggerUtil.warn(String.format("[NcasServer] Lỗi giao thức (%s) từ %s: %s",
                    pe.getReason(), session != null ? session.getRemoteAddress() : "client", pe.getMessage()));
        } catch (java.net.SocketTimeoutException ste) {
            LoggerUtil.warn(String.format("[NcasServer] Socket Timeout từ %s (đóng kết nối)",
                    session != null ? session.getRemoteAddress() : "client"));
        } catch (IOException ioe) {
            // Ngắt kết nối socket thông thường (Connection reset / Broken pipe)
        } catch (Exception e) {
            LoggerUtil.error(String.format("[NcasServer] Ngoại lệ xử lý client %s: %s",
                    session != null ? session.getRemoteAddress() : "client", e.getMessage()));
        } finally {
            if (session != null) {
                cleanUpSession(session);
            }
        }
    }

    /**
     * Điều phối xử lý Frame theo từng loại MessageType trong giao thức NCAS-P v1
     */
    private void dispatchFrame(AgentSession session, Frame frame) throws IOException, ProtocolException {
        MessageType type = frame.getType();

        switch (type) {
            case REGISTER_REQ: {
                RegisterRequest req = frame.parsePayload(RegisterRequest.class);
                session.setPendingRegisterRequest(req);

                if (req == null || req.getAgentId() == null || req.getAgentId().trim().isEmpty()) {
                    com.networkalert.protocol.ProtocolMetrics.recordAuthFailure("unknown");
                    session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Định danh Agent ID không hợp lệ hoặc để trống!"));
                    session.close();
                    break;
                }

                String aId = req.getAgentId().trim();
                String provToken = req.getAuthToken() != null ? req.getAuthToken().trim() : "";
                com.networkalert.security.AgentTokenEntry entry = agentTokenManager != null ? agentTokenManager.getEntry(aId) : null;

                if (entry != null && entry.isRevoked()) {
                    com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                    session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Token của Agent đã bị thu hồi!"));
                    session.close();
                    break;
                }

                boolean isTlsSession = this.tlsEnabled || (session.getSocket() instanceof javax.net.ssl.SSLSocket);

                if (isTlsSession) {
                    // Trong kênh an toàn TLS (§E2.2): Agent gửi token trong REGISTER, Server so với H
                    if (provToken.isEmpty()) {
                        com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                        session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Kênh TLS yêu cầu gửi Auth Token trong thông điệp đăng ký!"));
                        session.close();
                        break;
                    }
                    agentManager.registerAgent(session, req, authToken);
                } else if (allowLegacyAuth && !provToken.isEmpty()) {
                    LoggerUtil.warn(String.format("[NcasServer] Chấp nhận xác thực Legacy Auth v1 cho Agent '%s' (%s)",
                            aId, session.getRemoteAddress()));
                    agentManager.registerAgent(session, req, authToken);
                } else if (!allowLegacyAuth && !provToken.isEmpty()) {
                    com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                    session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Không chấp nhận token bản rõ trong chế độ --no-tls! Bật --allow-legacy-auth hoặc sử dụng Challenge-Response."));
                    session.close();
                } else {
                    // Cơ chế Challenge-Response (§E2.3)
                    if (entry == null) {
                        com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                        session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Agent ID chưa được đăng ký trong hệ thống!"));
                        session.close();
                        break;
                    }
                    com.networkalert.protocol.messages.AuthChallengeMessage challenge =
                            challengeManager.createChallenge(aId, entry.getSaltHex());
                    session.sendFrame(MessageType.AUTH_CHALLENGE, challenge);
                }
                break;
            }

            case AUTH_RESPONSE: {
                com.networkalert.protocol.messages.AuthResponseMessage authResp =
                        frame.parsePayload(com.networkalert.protocol.messages.AuthResponseMessage.class);
                handleAuthResponse(session, authResp);
                break;
            }

            case HEARTBEAT_PING: {
                if (!session.isAuthenticated()) {
                    session.sendFrame(MessageType.DISCONNECT, DisconnectMessage.of("UNAUTHENTICATED", "Chưa đăng ký danh tính"));
                    session.close();
                    return;
                }
                long serverRecvMs = clock.millis();
                HeartbeatPing ping = frame.parsePayload(HeartbeatPing.class);
                agentManager.handleHeartbeat(session, ping);

                if (session.isV11OrHigher()) {
                    // v1.1: Trả lời ACK kèm mốc NTP (t1=serverRecvMs, t2=serverSendMs) và watermark các stream (ERRATA §E3.3, §E3.7)
                    List<com.networkalert.protocol.messages.StreamAck> acks = new ArrayList<>();
                    for (StreamTracker st : session.getStreamTrackers().values()) {
                        acks.add(new com.networkalert.protocol.messages.StreamAck(st.getStreamId(), st.getWatermark()));
                    }
                    long serverSendMs = clock.millis();
                    com.networkalert.protocol.messages.AckMessage ack = new com.networkalert.protocol.messages.AckMessage(
                            acks, ping != null ? ping.getHbSeq() : null, serverRecvMs, serverSendMs);
                    session.sendFrame(MessageType.ACK, ack);
                } else {
                    // v1.0: Trả lời HEARTBEAT_PONG truyền thống
                    session.sendFrame(MessageType.HEARTBEAT_PONG, com.networkalert.protocol.messages.HeartbeatPong.ok());
                }
                break;
            }

            case REPORT_RESULT: {
                if (!session.isAuthenticated()) {
                    return;
                }
                long serverRecvMs = clock.millis();
                ReportResultMessage report = frame.parsePayload(ReportResultMessage.class);
                handleReportResult(session, report, serverRecvMs);
                break;
            }

            case REPORT_BATCH: {
                if (!session.isAuthenticated()) {
                    return;
                }
                long serverRecvMs = clock.millis();
                com.networkalert.protocol.messages.ReportBatchMessage batch =
                        frame.parsePayload(com.networkalert.protocol.messages.ReportBatchMessage.class);
                handleReportBatch(session, batch, serverRecvMs);
                break;
            }

            case FORCE_CHECK_RESP: {
                ForceCheckResponse resp = frame.parsePayload(ForceCheckResponse.class);
                handleForceCheckResponse(session, resp);
                break;
            }

            case CONSOLE_COMMAND: {
                ConsoleCommand cmd = frame.parsePayload(ConsoleCommand.class);
                ConsoleResponse response = executeConsoleCommand(session, cmd);
                session.sendFrame(MessageType.CONSOLE_COMMAND_RESP, response);
                break;
            }

            case CONSOLE_SUBSCRIBE: {
                if (!consoleSessions.contains(session)) {
                    consoleSessions.add(session);
                    LoggerUtil.info("[NcasServer] Đã đăng ký một Console vào luồng theo dõi thời gian thực.");
                }
                break;
            }

            case DISCONNECT: {
                DisconnectMessage disc = frame.parsePayload(DisconnectMessage.class);
                String reason = disc != null ? (disc.getReason() + " - " + disc.getDetails()) : "Yêu cầu ngắt kết nối";
                if (session.isAuthenticated()) {
                    agentManager.unregisterAgent(session.getAgentId(), reason);
                }
                session.close();
                break;
            }

            default:
                LoggerUtil.warn("[NcasServer] Nhận được Frame chưa hỗ trợ xử lý: " + type);
                break;
        }
    }

    /**
     * Xử lý thông điệp phản hồi Challenge-Response từ Agent trong chế độ --no-tls (ERRATA §E2.3)
     */
    private void handleAuthResponse(AgentSession session, com.networkalert.protocol.messages.AuthResponseMessage authResp) throws IOException {
        if (authResp == null || authResp.getAgentId() == null || authResp.getNonce() == null) {
            com.networkalert.protocol.ProtocolMetrics.recordAuthFailure("unknown");
            session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Thông điệp AUTH_RESPONSE không hợp lệ!"));
            session.close();
            return;
        }

        String aId = authResp.getAgentId().trim();
        com.networkalert.security.AgentTokenEntry entry = agentTokenManager != null ? agentTokenManager.getEntry(aId) : null;
        if (entry == null || entry.isRevoked()) {
            com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
            session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Agent không tồn tại hoặc token đã bị thu hồi!"));
            session.close();
            return;
        }

        boolean valid = challengeManager.verifyResponse(aId, authResp.getNonce(), authResp.getHmac(), entry.getHashHex());
        if (!valid) {
            com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
            LoggerUtil.warn(String.format("🚨 [NcasServer] Xác thực Challenge-Response thất bại cho Agent '%s' (%s)",
                    aId, session.getRemoteAddress()));
            session.sendFrame(MessageType.REGISTER_RESP, com.networkalert.protocol.messages.RegisterResponse.rejected("AUTH_FAIL: Chữ ký HMAC không khớp hoặc Nonce đã hết hạn / bị sử dụng lại!"));
            session.close();
            return;
        }

        com.networkalert.protocol.messages.RegisterRequest req = session.getPendingRegisterRequest();
        if (req == null) {
            req = new com.networkalert.protocol.messages.RegisterRequest();
            req.setAgentId(aId);
        }
        agentManager.completeRegistration(session, req, null);
    }

    /**
     * Xử lý báo cáo kết quả kiểm tra mục tiêu từ Agent gửi về qua Ingestor pipeline (ERRATA §E5.1)
     */
    private void handleReportResult(AgentSession session, ReportResultMessage report) {
        handleReportResult(session, report, clock.millis());
    }

    private void handleReportResult(AgentSession session, ReportResultMessage report, long serverRecvMs) {
        resultIngestor.ingestSingle(session, report, serverRecvMs);
    }

    /**
     * Xử lý lô kết quả đo kiểm REPORT_BATCH (0x0010) qua Ingestor pipeline (ERRATA §E5.1)
     */
    private void handleReportBatch(AgentSession session, com.networkalert.protocol.messages.ReportBatchMessage batch, long serverRecvMs) {
        resultIngestor.ingestBatch(session, batch, serverRecvMs);
    }

    /**
     * Xử lý kết quả kiểm tra tức thời trả về từ Agent
     */
    private void handleForceCheckResponse(AgentSession session, ForceCheckResponse resp) {
        if (resp == null || resp.getResult() == null) return;
        LoggerUtil.info(String.format("🎯 [ForceCheck] Nhận kết quả đo tức thời cho mục tiêu #%d: Thành công=%b, Độ trễ=%dms",
                resp.getTargetId(), resp.getResult().isSuccess(), resp.getResult().getLatencyMs()));

        handleReportResult(session, resp.getResult());
        broadcastToConsoles(Frame.create(MessageType.FORCE_CHECK_RESP, resp));
    }

    /**
     * Thực thi các lệnh quản trị từ Console
     */
    public ConsoleResponse executeConsoleCommand(AgentSession session, ConsoleCommand cmd) {
        if (cmd == null || cmd.getCommand() == null) {
            return ConsoleResponse.error("Lệnh không hợp lệ!");
        }

        long start = clock.millis();
        String command = cmd.getCommand().trim().toLowerCase();
        Map<String, String> params = cmd.getParams();

        try {
            switch (command) {
                case "list-agents": {
                    List<Map<String, Object>> agentList = new ArrayList<>();
                    for (AgentSession a : agentManager.getActiveSessions()) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("agentId", a.getAgentId());
                        map.put("hostname", a.getHostname());
                        map.put("localIp", a.getLocalIp());
                        map.put("os", a.getOs());
                        map.put("version", a.getAgentVersion());
                        map.put("targetsCount", a.getAssignedTargetIds().size());
                        map.put("lastHeartbeatSecAgo", (clock.millis() - a.getLastHeartbeatTime()) / 1000);
                        map.put("cpuUsage", a.getLastCpuUsage());
                        map.put("freeMemoryMb", a.getLastFreeMemoryMb());
                        agentList.add(map);
                    }
                    return new ConsoleResponse(true, "Danh sách Agent (" + agentList.size() + ")",
                            GSON.toJson(agentList), clock.millis() - start);
                }

                case "list-targets": {
                    List<Map<String, Object>> targetList = new ArrayList<>();
                    for (ServerTarget t : targetDistributor.getAllTargets()) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", t.getId());
                        map.put("name", t.getName());
                        map.put("host", t.getHost());
                        map.put("port", t.getPort());
                        map.put("protocol", t.getProtocol() != null ? t.getProtocol().name() : "TCP");
                        map.put("status", t.getStatus() != null ? t.getStatus().name() : "UNKNOWN");
                        map.put("latency", t.getCurrentLatency());
                        map.put("assignedAgent", targetDistributor.getAssignedAgent(t.getId()));
                        map.put("enabled", t.isEnabled());
                        targetList.add(map);
                    }
                    return new ConsoleResponse(true, "Danh sách mục tiêu (" + targetList.size() + ")",
                            GSON.toJson(targetList), clock.millis() - start);
                }

                case "check-now": {
                    String tidStr = params != null ? params.get("targetId") : null;
                    if (tidStr == null) return ConsoleResponse.error("Thiếu tham số 'targetId'!");
                    int tid = Integer.parseInt(tidStr);
                    boolean ok = forceCheckTarget(tid);
                    if (ok) {
                        return ConsoleResponse.ok("Đã gửi yêu cầu đo tức thời tới Agent phụ trách mục tiêu #" + tid);
                    } else {
                        return ConsoleResponse.error("Không tìm thấy Agent nào phụ trách mục tiêu #" + tid);
                    }
                }

                case "rebalance": {
                    targetDistributor.rebalance();
                    return ConsoleResponse.ok("Đã kích hoạt tái phân bổ mục tiêu tới toàn bộ Agent trực tuyến.");
                }

                case "server-info": {
                    Map<String, Object> info = new HashMap<>();
                    info.put("port", port);
                    info.put("bindHost", bindHost);
                    info.put("uptimeSeconds", Duration.between(startTime, Instant.now(clock)).getSeconds());
                    info.put("activeAgents", agentManager.getActiveAgentCount());
                    info.put("monitoredTargets", targetDistributor.getAllTargets().size());
                    info.put("totalMemoryMb", Runtime.getRuntime().totalMemory() / (1024 * 1024));
                    info.put("freeMemoryMb", Runtime.getRuntime().freeMemory() / (1024 * 1024));
                    return new ConsoleResponse(true, "Thông tin NCAS Server", GSON.toJson(info), clock.millis() - start);
                }

                case "token-create":
                case "token create":
                case "agent-token create": {
                    String aId = params != null ? params.get("agentId") : null;
                    if (aId == null || aId.trim().isEmpty()) {
                        return ConsoleResponse.error("Thiếu tham số 'agentId'!");
                    }
                    String role = params != null && params.containsKey("role") ? params.get("role") : "agent";
                    String rawToken = agentTokenManager.createToken(aId.trim(), role);
                    return ConsoleResponse.ok(String.format("Đã sinh Token thành công cho Agent '%s'!\nToken (Chỉ hiện một lần): %s",
                            aId.trim(), rawToken));
                }

                case "token-revoke":
                case "token revoke":
                case "agent-token revoke": {
                    String aId = params != null ? params.get("agentId") : null;
                    if (aId == null || aId.trim().isEmpty()) {
                        return ConsoleResponse.error("Thiếu tham số 'agentId'!");
                    }
                    boolean ok = agentTokenManager.revokeToken(aId.trim());
                    if (ok) {
                        return ConsoleResponse.ok(String.format("Đã thu hồi Token của Agent '%s' thành công!", aId.trim()));
                    } else {
                        return ConsoleResponse.error(String.format("Không tìm thấy Agent '%s' hoặc Token đã bị thu hồi trước đó!", aId.trim()));
                    }
                }

                case "token-list":
                case "token list":
                case "agent-token list": {
                    List<com.networkalert.security.AgentTokenInfo> tokenList = agentTokenManager.listTokens();
                    return new ConsoleResponse(true, "Danh sách Token Agent (" + tokenList.size() + ")",
                            GSON.toJson(tokenList), 0L);
                }

                default:
                    return ConsoleResponse.error("Lệnh không được hỗ trợ: '" + command + "'");
            }
        } catch (Exception e) {
            return ConsoleResponse.error("Lỗi thực thi lệnh '" + command + "': " + e.getMessage());
        }
    }

    /**
     * Yêu cầu đo tức thời một mục tiêu qua Agent phụ trách
     */
    public boolean forceCheckTarget(int targetId) {
        String assignedAgentId = targetDistributor.getAssignedAgent(targetId);
        if (assignedAgentId == null) {
            return false;
        }

        AgentSession session = agentManager.getSession(assignedAgentId);
        if (session == null || session.isClosed()) {
            return false;
        }

        ServerTarget target = targetDistributor.getTarget(targetId);
        if (target == null) return false;

        ForceCheckRequest req = new ForceCheckRequest(targetId, TargetDto.fromServerTarget(target));
        try {
            session.sendFrame(MessageType.FORCE_CHECK_REQ, req);
            return true;
        } catch (Exception e) {
            LoggerUtil.error("[NcasServer] Lỗi gửi yêu cầu ForceCheck tới Agent " + assignedAgentId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Phát Frame quảng bá tới toàn bộ các Console đang kết nối
     */
    private void broadcastToConsoles(Frame frame) {
        for (AgentSession console : consoleSessions) {
            try {
                console.sendFrame(frame);
            } catch (Exception e) {
                consoleSessions.remove(console);
            }
        }
    }

    /**
     * Dọn dẹp tài nguyên khi một phiên socket ngắt kết nối
     */
    private void cleanUpSession(AgentSession session) {
        consoleSessions.remove(session);
        if (session.isAuthenticated() && session.getAgentId() != null) {
            agentManager.unregisterAgent(session.getAgentId(), "SOCKET_DISCONNECTED");
        }
        session.close();
    }

    /**
     * In Dashboard trạng thái hệ thống dạng ASCII Art ra Console
     */
    public void printStatusDashboard() {
        if (!running) return;

        long uptimeSec = Duration.between(startTime, Instant.now(clock)).getSeconds();
        long hours = uptimeSec / 3600;
        long minutes = (uptimeSec % 3600) / 60;
        long seconds = uptimeSec % 60;

        int totalAgents = agentManager.getActiveAgentCount();
        Collection<ServerTarget> targets = targetDistributor.getAllTargets();
        long onlineCount = targets.stream().filter(t -> t.getStatus() == ServerStatus.ONLINE).count();
        long warningCount = targets.stream().filter(t -> t.getStatus() == ServerStatus.WARNING).count();
        long offlineCount = targets.stream().filter(t -> t.getStatus() == ServerStatus.OFFLINE).count();

        StringBuilder sb = new StringBuilder();
        sb.append("\n======================================================================\n");
        sb.append(String.format("📡 NCAS DISTRIBUTED MONITORING SERVER [PORT: %d] | Uptime: %02d:%02d:%02d\n",
                port, hours, minutes, seconds));
        sb.append(String.format("   • Agent trực tuyến: %d   |   Tổng mục tiêu: %d\n", totalAgents, targets.size()));
        sb.append(String.format("   • Trạng thái mục tiêu: 🟢 %d ONLINE  |  🟡 %d WARNING  |  🔴 %d OFFLINE\n",
                onlineCount, warningCount, offlineCount));
        sb.append("----------------------------------------------------------------------\n");
        sb.append("DANH SÁCH AGENT TRỰC TUYẾN:\n");
        if (totalAgents == 0) {
            sb.append("   (Chưa có Agent nào kết nối tới Server)\n");
        } else {
            for (AgentSession a : agentManager.getActiveSessions()) {
                sb.append(String.format("   • [%s] %s (%s) - %s | Mục tiêu: %d | CPU: %.1f%% | RAM trống: %d MB\n",
                        a.getAgentId(), a.getHostname(), a.getLocalIp(), a.getOs(),
                        a.getAssignedTargetIds().size(), a.getLastCpuUsage(), a.getLastFreeMemoryMb()));
            }
        }
        sb.append("======================================================================\n");

        LoggerUtil.info(sb.toString());
    }

    /**
     * Dừng máy chủ trung tâm an toàn
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        LoggerUtil.info("[NcasServer] Đang tiến hành dừng máy chủ NCAS Server...");

        maintenanceScheduler.shutdownNow();

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }

        // Đóng các phiên Console
        Frame disc = Frame.create(MessageType.DISCONNECT, DisconnectMessage.of("SERVER_SHUTDOWN", "Máy chủ tắt"));
        for (AgentSession c : consoleSessions) {
            try { c.sendFrame(disc); } catch (Exception ignored) {}
            c.close();
        }
        consoleSessions.clear();

        // Đóng các phiên Agent
        agentManager.shutdown();

        // Dừng pool công việc
        clientWorkerPool.shutdownNow();

        if (alertService != null && alertService.getEmailService() != null) {
            alertService.getEmailService().shutdown();
        }

        LoggerUtil.info("✅ [NcasServer] Máy chủ NCAS Server đã dừng hoàn toàn an toàn.");
    }

    // --- Getters ---
    public int getPort() {
        return (serverSocket != null && serverSocket.isBound()) ? serverSocket.getLocalPort() : port;
    }
    public String getAuthToken() { return authToken; }
    public boolean isRunning() { return running; }
    public AgentManager getAgentManager() { return agentManager; }
    public TargetDistributor getTargetDistributor() { return targetDistributor; }
    public ResultIngestor getResultIngestor() { return resultIngestor; }
    public IngestMetrics getIngestMetrics() { return resultIngestor.getMetrics(); }
    public AlertService getAlertService() { return alertService; }
    public TargetRepository getTargetRepository() { return targetRepository; }
    public IncidentRepository getIncidentRepository() { return incidentRepository; }

    /**
     * Entry point khởi chạy chế độ Server độc lập từ dòng lệnh
     */
    public static void main(String[] args) {
        LoggerUtil.ensureUtf8Console();

        // 1. Xử lý các CLI Subcommands quản lý Token theo ERRATA §E2.2: token create/revoke/list
        if (args != null && args.length > 0) {
            String sub = args[0].trim().toLowerCase();
            if ("token".equals(sub) || "agent-token".equals(sub)) {
                com.networkalert.security.AgentTokenManager tm =
                        new com.networkalert.security.AgentTokenManager(java.nio.file.Path.of("data/agents.json"), Clock.systemUTC());
                if (args.length >= 3 && "create".equalsIgnoreCase(args[1])) {
                    String agentId = args[2].trim();
                    String role = (args.length >= 4) ? args[3].trim() : "agent";
                    String rawToken = tm.createToken(agentId, role);
                    LoggerUtil.info("======================================================================");
                    LoggerUtil.info("🔑 ĐÃ SINH TOKEN MỚI CHO AGENT: " + agentId);
                    LoggerUtil.info("   • Token (LƯU Ý: Chỉ hiển thị một lần duy nhất): " + rawToken);
                    LoggerUtil.info("======================================================================");
                    return;
                } else if (args.length >= 3 && "revoke".equalsIgnoreCase(args[1])) {
                    String agentId = args[2].trim();
                    boolean ok = tm.revokeToken(agentId);
                    if (ok) {
                        LoggerUtil.info("✅ Đã thu hồi quyền truy cập (Revoke) của Agent: " + agentId);
                    } else {
                        LoggerUtil.warn("❌ Không tìm thấy Agent hoặc Token đã bị thu hồi: " + agentId);
                    }
                    return;
                } else if (args.length >= 2 && "list".equalsIgnoreCase(args[1])) {
                    List<com.networkalert.security.AgentTokenInfo> list = tm.listTokens();
                    LoggerUtil.info("======================================================================");
                    LoggerUtil.info("DANH SÁCH AGENT TOKEN (" + list.size() + "):");
                    LoggerUtil.info("----------------------------------------------------------------------");
                    LoggerUtil.info(String.format("%-20s | %-10s | %-10s | %s", "Agent ID", "Vai trò", "Trạng thái", "Ngày tạo (UTC)"));
                    for (com.networkalert.security.AgentTokenInfo info : list) {
                        String st = info.isRevoked() ? "REVOKED" : "ACTIVE";
                        LoggerUtil.info(String.format("%-20s | %-10s | %-10s | %s",
                                info.getAgentId(), info.getRole(), st, java.time.Instant.ofEpochMilli(info.getCreatedAt())));
                    }
                    LoggerUtil.info("======================================================================");
                    return;
                }
            }
        }

        int port = DEFAULT_PORT;
        String host = "0.0.0.0";
        String token = null;
        boolean allowLegacy = false; // Mặc định TẮT ở production (ERRATA §E2.4)
        boolean noTls = false; // Mặc định BẬT TLS ở production (ERRATA §E2.1)
        String keystore = "data/tls/keystore.p12";
        String keystorePass = System.getenv("NCAS_KEYSTORE_PASSWORD");

        // Phân tích tham số dòng lệnh
        for (int i = 0; i < args.length; i++) {
            if ("--port".equalsIgnoreCase(args[i]) || "-p".equalsIgnoreCase(args[i])) {
                if (i + 1 < args.length) {
                    port = Integer.parseInt(args[++i]);
                }
            } else if ("--host".equalsIgnoreCase(args[i]) || "-h".equalsIgnoreCase(args[i])) {
                if (i + 1 < args.length) {
                    host = args[++i];
                }
            } else if ("--token".equalsIgnoreCase(args[i]) || "-t".equalsIgnoreCase(args[i])) {
                if (i + 1 < args.length) {
                    token = args[++i];
                }
            } else if ("--allow-legacy-auth".equalsIgnoreCase(args[i])) {
                allowLegacy = true;
            } else if ("--no-tls".equalsIgnoreCase(args[i])) {
                noTls = true;
            } else if ("--keystore".equalsIgnoreCase(args[i])) {
                if (i + 1 < args.length) {
                    keystore = args[++i];
                }
            } else if ("--keystore-pass".equalsIgnoreCase(args[i])) {
                if (i + 1 < args.length) {
                    keystorePass = args[++i];
                }
            }
        }

        if (noTls) {
            LoggerUtil.warn("⚠️  [AN NINH] NCAS Server đang chạy ở chế độ --no-tls!");
            LoggerUtil.warn("⚠️  Mọi Agent bắt buộc xác thực qua Challenge-Response HMAC-SHA256 (hoặc --allow-legacy-auth).");
        }

        // Khởi tạo Database nếu có SQL Server
        DatabaseManager db = DatabaseManager.getInstance();
        if (db.testConnection()) {
            db.initializeTables();
        }

        NcasServer server = new NcasServer(port, host, token);
        server.setAllowLegacyAuth(allowLegacy);
        server.setTlsEnabled(!noTls);
        if (keystore != null) server.setKeystorePath(keystore);
        if (keystorePass != null) server.setKeystorePassword(keystorePass.toCharArray());

        // Đăng ký Hook tắt máy chủ an toàn khi nhận tín hiệu SIGINT (Ctrl + C)
        Runtime.getRuntime().addShutdownHook(
                com.networkalert.util.NamedThreadFactory.createThread("NCAS-ServerShutdownHook", server::stop, false));

        try {
            server.start();

            // Đọc lệnh CLI từ bàn phím nếu chạy tương tác
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            LoggerUtil.info("Nhập 'status', 'agents', 'targets', 'tokens', 'rebalance', 'help' hoặc 'exit' để điều khiển:");

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                    server.stop();
                    System.exit(0);
                    break;
                } else if ("status".equalsIgnoreCase(line)) {
                    server.printStatusDashboard();
                } else if ("agents".equalsIgnoreCase(line)) {
                    ConsoleResponse r = server.executeConsoleCommand(null, new ConsoleCommand("list-agents"));
                    LoggerUtil.info(r.getDataJson());
                } else if ("targets".equalsIgnoreCase(line)) {
                    ConsoleResponse r = server.executeConsoleCommand(null, new ConsoleCommand("list-targets"));
                    LoggerUtil.info(r.getDataJson());
                } else if ("tokens".equalsIgnoreCase(line)) {
                    ConsoleResponse r = server.executeConsoleCommand(null, new ConsoleCommand("token-list"));
                    LoggerUtil.info(r.getDataJson());
                } else if (line.toLowerCase().startsWith("token create ") || line.toLowerCase().startsWith("agent-token create ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        Map<String, String> p = new HashMap<>();
                        p.put("agentId", parts[2]);
                        ConsoleResponse r = server.executeConsoleCommand(null, new ConsoleCommand("token-create", p));
                        LoggerUtil.info(r.getMessage());
                    }
                } else if (line.toLowerCase().startsWith("token revoke ") || line.toLowerCase().startsWith("agent-token revoke ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        Map<String, String> p = new HashMap<>();
                        p.put("agentId", parts[2]);
                        ConsoleResponse r = server.executeConsoleCommand(null, new ConsoleCommand("token-revoke", p));
                        LoggerUtil.info(r.getMessage());
                    }
                } else if ("rebalance".equalsIgnoreCase(line)) {
                    server.getTargetDistributor().rebalance();
                    LoggerUtil.info("Đã tái phân bổ mục tiêu!");
                } else if ("help".equalsIgnoreCase(line)) {
                    LoggerUtil.info("Lệnh có sẵn: status, agents, targets, tokens, token create <id>, token revoke <id>, rebalance, exit");
                }
            }
        } catch (Exception e) {
            LoggerUtil.error("Không thể khởi động NCAS Server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

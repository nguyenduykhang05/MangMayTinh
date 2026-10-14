package com.networkalert.agent;

import com.networkalert.agent.backoff.FullJitterBackoff;
import com.networkalert.agent.cache.TargetConfigCache;
import com.networkalert.agent.spool.DiskSpoolManager;
import com.networkalert.agent.spool.SpoolRecord;
import com.networkalert.model.CheckResult;
import com.networkalert.model.RealNetworkInfo;
import com.networkalert.model.ServerTarget;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.AssignTargetsMessage;
import com.networkalert.protocol.messages.DataGap;
import com.networkalert.protocol.messages.DisconnectMessage;
import com.networkalert.protocol.messages.ForceCheckRequest;
import com.networkalert.protocol.messages.ForceCheckResponse;
import com.networkalert.protocol.messages.HeartbeatPing;
import com.networkalert.protocol.messages.HeartbeatPong;
import com.networkalert.protocol.messages.RegisterRequest;
import com.networkalert.protocol.messages.RegisterResponse;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.protocol.messages.TargetDto;
import com.networkalert.service.RealNetworkService;
import com.networkalert.service.checker.CheckerFactory;
import com.networkalert.service.checker.IProtocolChecker;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NamedThreadFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trạm đo mạng phân tán NCAS Agent (NCAS-P v1).
 * <p>
 * Tính năng chính:
 * <ul>
 *   <li><b>Reverse TCP Connection</b>: Chủ động kết nối ngược từ sau NAT/Firewall lên NCAS Server.</li>
 *   <li><b>Store-and-Forward Offline Queue</b>: Bộ đệm hàng đợi lưu giữ kết quả đo khi mất mạng với Server, tự động đẩy bù khi kết nối khôi phục.</li>
 *   <li><b>Multi-protocol Measurement</b>: Đo kiểm TCP, UDP, HTTP, HTTPS, ICMP, DNS theo lịch trình phân bổ từ Server.</li>
 *   <li><b>On-demand Force Check</b>: Đo tức thời khi Server/Admin yêu cầu và phản hồi tức thì.</li>
 *   <li><b>Exponential Backoff Reconnect</b>: Tự động kết nối lại thông minh khi Server bảo trì hoặc đường truyền gián đoạn.</li>
 *   <li><b>Heartbeat & Telemetry</b>: Định kỳ gửi thông số CPU, RAM, số tác vụ đang chạy lên Server.</li>
 * </ul>
 * </p>
 */
public class NcasAgent {
    private final AgentConfig config;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile boolean authenticated = false;
    private String assignedSessionId;

    // Quản lý các lịch trình đo kiểm mục tiêu
    private final Map<Integer, ServerTarget> currentTargets = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    // Hàng đợi lưu trữ ngoại tuyến khi đứt kết nối Server (Store-and-Forward Queue)
    private final BlockingQueue<ReportResultMessage> offlineQueue;

    // Scheduler thực hiện các phép đo mạng
    private final ScheduledExecutorService monitorScheduler;

    // Scheduler gửi nhịp tim và báo cáo tài nguyên
    private final ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatFuture;

    // Luồng quản lý kết nối TCP và đọc Frame từ Server
    private Thread networkThread;

    private final Clock clock;
    private static final java.util.concurrent.atomic.AtomicInteger MONITOR_THREAD_COUNTER = new java.util.concurrent.atomic.AtomicInteger(1);

    // Chính sách an ninh cục bộ của Agent (§E2.6) & Bộ đếm backoff lỗi xác thực
    private com.networkalert.security.AgentPolicyManager policyManager;
    private volatile long lastAuthFailureBackoffMs = 0L;

    // NCAS-P v1.1 Stream, Seq và Đồng bộ mốc thời gian NTP (ERRATA §E3.2, §E3.7)
    private final long streamId;
    private final java.util.concurrent.atomic.AtomicLong seqCounter = new java.util.concurrent.atomic.AtomicLong(1);
    private final java.util.concurrent.atomic.AtomicLong hbSeqCounter = new java.util.concurrent.atomic.AtomicLong(1);
    private final com.networkalert.protocol.ClockSyncManager clockSyncManager = new com.networkalert.protocol.ClockSyncManager();
    private volatile long lastHeartbeatSendMs = 0L;

    // Quản lý Spool đĩa bền vững, Cache cấu hình phân công và Tái kết nối Full Jitter (ERRATA §E4)
    private final DiskSpoolManager spoolManager;
    private final TargetConfigCache targetCache;
    private final FullJitterBackoff backoff;
    private final ConcurrentHashMap<Integer, AtomicBoolean> targetRunningProbes = new ConcurrentHashMap<>();
    private final AtomicLong skippedProbesCounter = new AtomicLong(0L);
    private final Set<Long> sentInThisConnectionSeqs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Integer, Integer> targetFailureCounters = new ConcurrentHashMap<>();
    private final com.networkalert.agent.diag.NetworkPathDiagnoser networkPathDiagnoser;

    public static class AuthConfigurationException extends IOException {
        public AuthConfigurationException(String message) {
            super(message);
        }
    }

    public NcasAgent(AgentConfig config) {
        this(config, Clock.systemUTC());
    }

    public NcasAgent(AgentConfig config, Clock clock) {
        this.config = config != null ? config : new AgentConfig();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.streamId = java.util.concurrent.ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE;
        this.offlineQueue = new LinkedBlockingQueue<>(this.config.getMaxQueueCapacity());
        this.policyManager = new com.networkalert.security.AgentPolicyManager(
                Path.of(this.config.getPolicyFilePath() != null ? this.config.getPolicyFilePath() : "data/agent-policy.json")
        );
        this.targetCache = new TargetConfigCache(
                Path.of(this.config.getAssignedCachePath() != null ? this.config.getAssignedCachePath() : "data/assigned.json")
        );
        this.spoolManager = new DiskSpoolManager(
                Path.of(this.config.getSpoolDirPath() != null ? this.config.getSpoolDirPath() : "data/spool"),
                this.config.getMaxSpoolBytes(),
                this.config.getMaxSpoolHours(),
                5 * 1024 * 1024L,
                5 * 1024 * 1024L,
                this.config.getSpoolFsyncIntervalMs(),
                100,
                this.clock
        );
        this.backoff = new FullJitterBackoff(
                this.config.getReconnectInitialDelayMs(),
                this.config.getReconnectMaxDelayMs(),
                new java.util.Random()
        );

        int poolSize = Math.min(64, Math.max(4, Runtime.getRuntime().availableProcessors() * 4));
        this.monitorScheduler = Executors.newScheduledThreadPool(poolSize,
                new NamedThreadFactory("NCAS-AgentMonitor"));

        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("NCAS-AgentHeartbeat"));
        this.networkPathDiagnoser = new com.networkalert.agent.diag.NetworkPathDiagnoser(this.clock);
    }

    public com.networkalert.agent.diag.NetworkPathDiagnoser getNetworkPathDiagnoser() {
        return networkPathDiagnoser;
    }

    public Clock getClock() {
        return clock;
    }

    public DiskSpoolManager getSpoolManager() {
        return spoolManager;
    }

    public TargetConfigCache getTargetCache() {
        return targetCache;
    }

    public FullJitterBackoff getBackoff() {
        return backoff;
    }

    public long getSkippedProbes() {
        return skippedProbesCounter.get();
    }

    public com.networkalert.security.AgentPolicyManager getPolicyManager() {
        return policyManager;
    }

    public void setPolicyManager(com.networkalert.security.AgentPolicyManager policyManager) {
        this.policyManager = policyManager;
    }

    public long getLastAuthFailureBackoffMs() {
        return lastAuthFailureBackoffMs;
    }

    /**
     * Khởi động NCAS Agent
     */
    public synchronized void start() {
        if (running) return;
        running = true;

        LoggerUtil.info(String.format("======================================================================"));
        LoggerUtil.info(String.format("📡 NCAS MONITORING AGENT v1.0 ĐANG KHỞI CHẠY!"));
        LoggerUtil.info(String.format("   • Agent ID: %s", config.getAgentId()));
        LoggerUtil.info(String.format("   • Mục tiêu kết nối: %s:%d", config.getServerHost(), config.getServerPort()));
        LoggerUtil.info(String.format("   • Chu kỳ nhịp tim: %d giây", config.getHeartbeatIntervalSeconds()));
        LoggerUtil.info(String.format("   • Dung lượng bộ đệm ngoại tuyến: %d bản ghi", config.getMaxQueueCapacity()));
        LoggerUtil.info(String.format("   • Thư mục Spool đĩa: %s", config.getSpoolDirPath()));
        LoggerUtil.info(String.format("======================================================================"));

        // 1. Mở và phục hồi Disk Spool bền vững (§E4.1)
        try {
            spoolManager.open();
        } catch (Exception e) {
            LoggerUtil.error("[NcasAgent] Lỗi khởi tạo Disk Spool: " + e.getMessage());
        }

        // 2. Nạp cấu hình mục tiêu đã cache từ phiên trước nếu Server đang sập (§E4.5)
        if (targetCache != null) {
            List<TargetDto> cachedTargets = targetCache.loadTargets();
            if (!cachedTargets.isEmpty()) {
                LoggerUtil.info(String.format("🎯 [NcasAgent] Đã nạp %d mục tiêu từ cache nội bộ.", cachedTargets.size()));
                updateAssignedTargets(cachedTargets, false);
            }
        }

        networkThread = new NamedThreadFactory("NCAS-AgentNetworkLoop").newThread(this::runConnectionLoop);
        networkThread.start();
    }

    /**
     * Vòng lặp quản lý kết nối và tự động kết nối lại theo cơ chế Full Jitter Exponential Backoff (ERRATA §E4.4)
     */
    private void runConnectionLoop() {
        while (running) {
            try {
                connectAndServe();
                backoff.onDisconnected(clock.millis());
            } catch (AuthConfigurationException ace) {
                if (!running) break;
                backoff.onDisconnected(clock.millis());
                long delay = backoff.getConfigurationErrorBackoffMs();
                lastAuthFailureBackoffMs = delay;
                LoggerUtil.error(String.format("🚨 [NcasAgent] Lỗi cấu hình xác thực (AUTH_FAIL): %s. Áp dụng backoff cố định %d ms...",
                        ace.getMessage(), delay));
                sleepQuietly(delay);
                continue;
            } catch (Exception e) {
                if (!running) break;
                backoff.onDisconnected(clock.millis());
                LoggerUtil.warn(String.format("[NcasAgent] Mất kết nối tới Server (%s)...", e.getMessage()));
            }

            if (running) {
                long delay = backoff.nextDelayMs();
                LoggerUtil.info(String.format("[NcasAgent] Thử kết nối lại sau %d ms (Full Jitter, attempt=%d)...",
                        delay, backoff.getAttempt()));
                sleepQuietly(delay);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Thực hiện kết nối tới Server, xác thực danh tính và bước vào vòng lặp đọc Frame
     */
    private void connectAndServe() throws IOException {
        LoggerUtil.info(String.format("[NcasAgent] Đang kết nối tới NCAS Server tại %s:%d (TLS=%b)...",
                config.getServerHost(), config.getServerPort(), config.isTlsEnabled()));

        Socket s;
        if (config.isTlsEnabled()) {
            Socket rawSocket = new Socket();
            rawSocket.connect(new java.net.InetSocketAddress(config.getServerHost(), config.getServerPort()), 5000);
            rawSocket.setTcpNoDelay(true);

            javax.net.ssl.SSLContext sslCtx;
            try {
                sslCtx = com.networkalert.security.TlsContextFactory.createClientSSLContext(config.getServerFingerprints());
            } catch (Exception e) {
                try { rawSocket.close(); } catch (Exception ignored) {}
                throw new AuthConfigurationException("Không thể khởi tạo ngữ cảnh TLS Client: " + e.getMessage());
            }

            javax.net.ssl.SSLSocketFactory ssf = sslCtx.getSocketFactory();
            javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) ssf.createSocket(
                    rawSocket, config.getServerHost(), config.getServerPort(), true);
            com.networkalert.security.TlsContextFactory.configureClientSocket(sslSocket);

            try {
                sslSocket.startHandshake();
            } catch (Exception e) {
                try { sslSocket.close(); } catch (Exception ignored) {}
                throw new AuthConfigurationException("Lỗi bắt tay TLS hoặc Fingerprint không khớp: " + e.getMessage());
            }

            s = sslSocket;
        } else {
            s = new Socket(config.getServerHost(), config.getServerPort());
            s.setTcpNoDelay(true); // Vô hiệu hóa Nagle Algorithm
        }

        synchronized (this) {
            this.socket = s;
            this.in = new BufferedInputStream(socket.getInputStream(), 16384);
            this.out = new BufferedOutputStream(socket.getOutputStream(), 16384);
            this.connected = true;
        }

        LoggerUtil.info("[NcasAgent] Đã kết nối TCP thành công! Đang gửi thông điệp đăng ký danh tính (REGISTER_REQ)...");

        // 1. Gửi thông điệp đăng ký MSG_REGISTER_REQ
        RegisterRequest req = buildRegisterRequest();
        sendFrameDirect(Frame.create(MessageType.REGISTER_REQ, req));

        // 2. Chờ phản hồi từ Server (AUTH_CHALLENGE hoặc REGISTER_RESP)
        Frame respFrame;
        try {
            respFrame = FrameDecoder.readFrame(in);
        } catch (Exception e) {
            throw new IOException("Lỗi đọc phản hồi đăng ký từ Server: " + e.getMessage(), e);
        }

        if (respFrame != null && respFrame.getType() == MessageType.AUTH_CHALLENGE) {
            com.networkalert.protocol.messages.AuthChallengeMessage challenge =
                    respFrame.parsePayload(com.networkalert.protocol.messages.AuthChallengeMessage.class);

            // Tính H = SHA-256(salt || token)
            byte[] saltBytes = com.networkalert.security.AgentTokenManager.hexToBytes(challenge.getSalt());
            byte[] hBytes = com.networkalert.security.AgentTokenManager.computeHashBytes(saltBytes, config.getAuthToken());

            // Tính HMAC = HMAC-SHA256(H, nonce || agentId)
            String hmac = com.networkalert.security.ChallengeManager.computeResponseHmac(hBytes, challenge.getNonce(), config.getAgentId());
            com.networkalert.protocol.messages.AuthResponseMessage authResp =
                    new com.networkalert.protocol.messages.AuthResponseMessage(config.getAgentId(), challenge.getNonce(), hmac);

            sendFrameDirect(Frame.create(MessageType.AUTH_RESPONSE, authResp));

            // Chờ phản hồi REGISTER_RESP sau bước Challenge-Response
            try {
                respFrame = FrameDecoder.readFrame(in);
            } catch (Exception e) {
                throw new IOException("Lỗi đọc phản hồi sau Challenge-Response: " + e.getMessage(), e);
            }
        }

        if (respFrame == null || respFrame.getType() != MessageType.REGISTER_RESP) {
            throw new IOException("Server phản hồi sai loại thông điệp khi đăng ký: " + (respFrame != null ? respFrame.getType() : "EOF"));
        }

        RegisterResponse resp = respFrame.parsePayload(RegisterResponse.class);
        if (!resp.isAccepted()) {
            String errMsg = resp.getMessage() != null ? resp.getMessage() : "Bị từ chối";
            closeSocket();
            if (errMsg.contains("AUTH_FAIL")) {
                throw new AuthConfigurationException(errMsg);
            } else {
                LoggerUtil.alert(String.format("❌ [NcasAgent] Server TỪ CHỐI đăng ký danh tính! Lý do: %s", errMsg));
                return;
            }
        }

        this.assignedSessionId = resp.getAssignedSessionId();
        this.authenticated = true;
        this.backoff.onConnected(clock.millis());
        this.sentInThisConnectionSeqs.clear();

        // Lưu Secret riêng nếu vừa thực hiện Enrollment đổi token
        if (resp.getDedicatedSecret() != null && !resp.getDedicatedSecret().isEmpty()) {
            config.setAuthToken(resp.getDedicatedSecret());
            LoggerUtil.info(String.format("🔑 [NcasAgent] Đã hoàn tất Enrollment! Đã nhận Dedicated Secret riêng: %s", resp.getDedicatedSecret()));
        }

        int hbInterval = resp.getHeartbeatIntervalSeconds() > 0 ? resp.getHeartbeatIntervalSeconds() : config.getHeartbeatIntervalSeconds();

        LoggerUtil.info(String.format("🚀 [NcasAgent] Đăng ký thành công vào Server! Session ID: %s | Nhịp tim: %d giây",
                assignedSessionId, hbInterval));

        // 3. Khởi động / tái thiết lập gửi nhịp tim định kỳ
        scheduleHeartbeat(hbInterval);

        // 4. Xả backlog từ Disk Spool và hàng đợi Store-and-Forward (§E4.3)
        drainBacklog();
        flushOfflineQueue();

        // 5. Vòng lặp nhận và xử lý Frame từ Server
        while (running && connected && !socket.isClosed()) {
            Frame frame;
            try {
                frame = FrameDecoder.readFrame(in);
            } catch (Exception e) {
                if (!running) break;
                throw new IOException("Đứt đường truyền khi đọc Frame từ Server: " + e.getMessage(), e);
            }

            if (frame == null) {
                // Server đã ngắt socket an toàn (EOF)
                LoggerUtil.warn("[NcasAgent] Nhận tín hiệu ngắt kết nối từ Server (EOF).");
                break;
            }

            dispatchServerFrame(frame);
        }

        closeSocket();
    }

    /**
     * Điều phối xử lý các Frame nhận được từ NCAS Server
     */
    private void dispatchServerFrame(Frame frame) {
        MessageType type = frame.getType();

        switch (type) {
            case ASSIGN_TARGETS: {
                AssignTargetsMessage msg = frame.parsePayload(AssignTargetsMessage.class);
                if (msg != null && msg.getTargets() != null) {
                    updateAssignedTargets(msg.getTargets());
                }
                break;
            }

            case FORCE_CHECK_REQ: {
                ForceCheckRequest req = frame.parsePayload(ForceCheckRequest.class);
                if (req != null) {
                    handleForceCheck(req);
                }
                break;
            }

            case HEARTBEAT_PONG: {
                HeartbeatPong pong = frame.parsePayload(HeartbeatPong.class);
                // Nhận phản hồi nhịp tim thành công
                break;
            }

            case ACK: {
                com.networkalert.protocol.messages.AckMessage ack =
                        frame.parsePayload(com.networkalert.protocol.messages.AckMessage.class);
                if (ack != null) {
                    if (ack.getServerRecvMs() != null && ack.getServerSendMs() != null && lastHeartbeatSendMs > 0) {
                        clockSyncManager.recordSample(lastHeartbeatSendMs, ack.getServerRecvMs(), ack.getServerSendMs(), clock.millis());
                    }
                    if (ack.getStreams() != null && spoolManager != null) {
                        for (com.networkalert.protocol.messages.StreamAck sa : ack.getStreams()) {
                            spoolManager.acknowledge(sa.getStreamId(), sa.getAckSeq());
                        }
                    }
                }
                break;
            }

            case DISCONNECT: {
                DisconnectMessage disc = frame.parsePayload(DisconnectMessage.class);
                LoggerUtil.warn(String.format("[NcasAgent] Server thông báo ngắt kết nối: %s (%s)",
                        disc != null ? disc.getReason() : "?", disc != null ? disc.getDetails() : ""));
                closeSocket();
                break;
            }

            default:
                LoggerUtil.warn("[NcasAgent] Nhận được Frame Server chưa hỗ trợ: " + type);
                break;
        }
    }

    /**
     * Cập nhật danh sách mục tiêu đo kiểm được Server phân công
     */
    public synchronized void updateAssignedTargets(List<TargetDto> targets) {
        updateAssignedTargets(targets, true);
    }

    public synchronized void updateAssignedTargets(List<TargetDto> targets, boolean saveCache) {
        Set<Integer> newTargetIds = new HashSet<>();
        for (TargetDto dto : targets) {
            newTargetIds.add(dto.getId());
        }

        // 1. Hủy các tác vụ đo của những mục tiêu không còn được phân công
        Set<Integer> currentIds = new HashSet<>(currentTargets.keySet());
        for (Integer tid : currentIds) {
            if (!newTargetIds.contains(tid)) {
                ScheduledFuture<?> f = scheduledTasks.remove(tid);
                if (f != null) {
                    f.cancel(true);
                }
                currentTargets.remove(tid);
                targetRunningProbes.remove(tid);
                LoggerUtil.info("[NcasAgent] Đã hủy đo kiểm mục tiêu #" + tid + " (Đã được Server tái phân công đi nơi khác).");
            }
        }

        // 2. Thêm mới hoặc cập nhật lịch đo cho các mục tiêu được giao sau khi đã kiểm tra chính sách an ninh (§E2.6)
        int acceptedCount = 0;
        for (TargetDto dto : targets) {
            // Kiểm tra chính sách an ninh cục bộ của Agent
            if (policyManager != null) {
                com.networkalert.security.AgentPolicyManager.ValidationResult vr =
                        policyManager.validateTarget(dto.getId(), dto.getHost(), dto.getPort(), dto.getProtocol(), dto.getIntervalSeconds(), acceptedCount);
                if (!vr.isAllowed()) {
                    LoggerUtil.warn(String.format("🛡️ [AgentPolicy] Từ chối mục tiêu #%d '%s' (%s): %s",
                            dto.getId(), dto.getName(), dto.getHost(), vr.getReason()));
                    continue;
                }
            }

            ServerTarget target = dto.toServerTarget();
            currentTargets.put(target.getId(), target);
            acceptedCount++;

            // Hủy lịch cũ nếu có để nạp chu kỳ mới
            ScheduledFuture<?> existing = scheduledTasks.remove(target.getId());
            if (existing != null) {
                existing.cancel(true);
            }

            if (target.isEnabled()) {
                int interval = Math.max(1, target.getIntervalSeconds());
                // Pha đầu ngẫu nhiên trong [0, interval) để tránh đo cùng một nhịp (§E4.6)
                int initialDelay = java.util.concurrent.ThreadLocalRandom.current().nextInt(interval);
                ScheduledFuture<?> future = monitorScheduler.scheduleWithFixedDelay(
                        () -> performCheck(target),
                        initialDelay, interval, TimeUnit.SECONDS
                );
                scheduledTasks.put(target.getId(), future);
            }
        }

        // 3. Lưu cấu hình vào cache cục bộ data/assigned.json (§E4.5)
        if (saveCache && targetCache != null) {
            targetCache.saveTargets(targets, clock.millis());
        }

        LoggerUtil.info(String.format("🎯 [NcasAgent] Đã cập nhật phân công: Đang đo kiểm %d mục tiêu.", currentTargets.size()));
    }

    /**
     * Thực hiện đo kiểm một mục tiêu theo chu kỳ
     */
    private void performCheck(ServerTarget target) {
        if (!target.isEnabled()) return;

        // Chống chồng lấn tác vụ đo: Lần trước chưa xong thì bỏ lượt và tăng skippedProbes (§E4.6)
        AtomicBoolean runningProbe = targetRunningProbes.computeIfAbsent(target.getId(), id -> new AtomicBoolean(false));
        if (!runningProbe.compareAndSet(false, true)) {
            skippedProbesCounter.incrementAndGet();
            LoggerUtil.warn("[NcasAgent] Bỏ qua lượt đo cho mục tiêu #" + target.getId() + " do lượt trước chưa hoàn tất.");
            return;
        }

        try {
            IProtocolChecker checker = CheckerFactory.getChecker(target.getProtocol());
            CheckResult result = checker.check(target);

            // Kiểm tra trạng thái thất bại để kích hoạt tự chẩn đoán tại chỗ khi sắp HARD DOWN (§E9.9)
            if (result.isSuccess()) {
                targetFailureCounters.remove(target.getId());
            } else {
                int consecutiveFails = targetFailureCounters.merge(target.getId(), 1, Integer::sum);
                if (consecutiveFails == 2) {
                    networkPathDiagnoser.triggerDiagnosisAsync(target);
                }
            }

            ReportResultMessage report = new ReportResultMessage(
                    config.getAgentId(),
                    target.getId(),
                    clock.millis(),
                    result.isSuccess(),
                    result.getLatencyMs(),
                    result.getResponseCode(),
                    null,
                    result.getMessage()
            );
            report.setStreamId(streamId);
            report.setSeq(seqCounter.getAndIncrement());
            report.setCheckedAtMs(clock.millis());
            report.setMethod(target.getProtocol() != null ? target.getProtocol().name() : null);

            // Đính kèm kết quả chẩn đoán gần nhất (nếu có) vào thông điệp báo cáo
            String diagJson = networkPathDiagnoser.getLatestDiagnosisJson(target.getId());
            if (diagJson != null) {
                report.setDiag(diagJson);
            }

            sendOrBufferReport(report);
        } finally {
            runningProbe.set(false);
        }
    }

    /**
     * Xử lý yêu cầu đo tức thời (Force Check) từ Server
     */
    private void handleForceCheck(ForceCheckRequest req) {
        monitorScheduler.submit(() -> {
            try {
                ServerTarget target = (req.getTarget() != null)
                        ? req.getTarget().toServerTarget()
                        : currentTargets.get(req.getTargetId());

                if (target == null) {
                    LoggerUtil.warn("[NcasAgent] Nhận ForceCheck nhưng không có dữ liệu mục tiêu #" + req.getTargetId());
                    return;
                }

                LoggerUtil.info(String.format("⚡ [ForceCheck] Đang thực hiện đo tức thời mục tiêu #%d '%s' (%s:%d)...",
                        target.getId(), target.getName(), target.getHost(), target.getPort()));

                IProtocolChecker checker = CheckerFactory.getChecker(target.getProtocol());
                CheckResult result = checker.check(target);

                ReportResultMessage report = new ReportResultMessage(
                        config.getAgentId(),
                        target.getId(),
                        clock.millis(),
                        result.isSuccess(),
                        result.getLatencyMs(),
                        result.getResponseCode(),
                        null,
                        result.getMessage()
                );

                ForceCheckResponse resp = new ForceCheckResponse(target.getId(), report);
                sendFrame(Frame.create(MessageType.FORCE_CHECK_RESP, resp));

                LoggerUtil.info(String.format("✅ [ForceCheck] Hoàn tất đo tức thời #%d: Thành công=%b, Độ trễ=%d ms",
                        target.getId(), result.isSuccess(), result.getLatencyMs()));
            } catch (Exception e) {
                LoggerUtil.error("[NcasAgent] Lỗi thực thi ForceCheck: " + e.getMessage());
            }
        });
    }

    /**
     * Gửi báo cáo kết quả kiểm tra lên Server; luôn lưu vào Spool bền vững (ERRATA §E4.1) và ưu tiên gửi Live (ERRATA §E4.3)
     */
    public void sendOrBufferReport(ReportResultMessage report) {
        if (report == null) return;
        if (report.getStreamId() == null) {
            report.setStreamId(this.streamId);
        }
        if (report.getSeq() == null) {
            report.setSeq(seqCounter.getAndIncrement());
        }

        // 1. Ghi bền vững vào Disk Spool (ERRATA §E4.1)
        if (spoolManager != null) {
            try {
                spoolManager.append(report);
            } catch (Exception e) {
                LoggerUtil.error("[NcasAgent] Lỗi ghi Spool đĩa: " + e.getMessage());
            }
        }

        // 2. Gửi trực tiếp nếu đang online (Live-first, ERRATA §E4.3)
        if (connected && authenticated) {
            try {
                flushOfflineQueue();
                sentInThisConnectionSeqs.add(report.getSeq());
                sendFrame(Frame.create(MessageType.REPORT_RESULT, report));
                drainBacklog();
                return;
            } catch (Exception e) {
                LoggerUtil.warn("[NcasAgent] Lỗi gửi trực tiếp báo cáo lên Server: " + e.getMessage());
            }
        }

        // 3. Đưa vào hàng đợi Store-and-Forward ngoại tuyến RAM để tương thích
        if (!offlineQueue.offer(report)) {
            offlineQueue.poll();
            offlineQueue.offer(report);
        }
    }

    /**
     * Xả các bản ghi chưa ACK từ Disk Spool theo cơ chế Backlog (ERRATA §E4.3)
     */
    public synchronized void drainBacklog() {
        if (!connected || !authenticated || spoolManager == null) return;
        List<SpoolRecord> unacked = spoolManager.getUnackedRecords();
        if (unacked.isEmpty()) return;

        int sent = 0;
        for (SpoolRecord rec : unacked) {
            if (!connected || !authenticated) break;
            long seq = rec.getSeq();
            if (sentInThisConnectionSeqs.contains(seq)) {
                continue; // Không gửi lại bản ghi đã gửi trong cùng kết nối (§E4.3)
            }
            try {
                ReportResultMessage rep = rec.getReport();
                if (rep != null) {
                    rep.setBackfilled(true);
                }
                sendFrameDirect(Frame.create(MessageType.REPORT_RESULT, rep));
                sentInThisConnectionSeqs.add(seq);
                sent++;
            } catch (Exception e) {
                LoggerUtil.warn("[NcasAgent] Tạm ngưng xả backlog do lỗi gửi: " + e.getMessage());
                break;
            }
        }
        if (sent > 0) {
            LoggerUtil.info(String.format("📦 [Backlog] Đã gửi %d bản ghi từ Disk Spool lên Server.", sent));
        }
    }

    /**
     * Xả toàn bộ các báo cáo đọng trong hàng đợi ngoại tuyến lên Server khi mạng phục hồi
     */
    public synchronized void flushOfflineQueue() {
        if (!connected || !authenticated || offlineQueue.isEmpty()) {
            return;
        }

        int count = 0;
        ReportResultMessage rep;
        while ((rep = offlineQueue.poll()) != null) {
            try {
                sendFrameDirect(Frame.create(MessageType.REPORT_RESULT, rep));
                count++;
            } catch (Exception e) {
                // Gửi thất bại, đưa lại vào đầu hàng đợi
                offlineQueue.offer(rep);
                LoggerUtil.warn("[NcasAgent] Tạm ngưng xả hàng đợi offline do lỗi gửi: " + e.getMessage());
                break;
            }
        }

        if (count > 0) {
            LoggerUtil.info(String.format("📦 [StoreAndForward] Đã xả thành công %d báo cáo đo kiểm ngoại tuyến lên Server.", count));
        }
    }

    /**
     * Lập lịch gửi nhịp tim định kỳ
     */
    private synchronized void scheduleHeartbeat(int intervalSeconds) {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }

        heartbeatFuture = heartbeatScheduler.scheduleWithFixedDelay(
                this::sendHeartbeat,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        );
    }

    /**
     * Thu thập thông số tài nguyên hệ điều hành và gửi MSG_HEARTBEAT_PING
     */
    public void sendHeartbeat() {
        if (!connected || !authenticated) return;

        double cpuPercent = getSystemCpuLoad();
        long freeMemMb = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        int tasks = scheduledTasks.size();

        long now = clock.millis();
        this.lastHeartbeatSendMs = now;
        HeartbeatPing ping = new HeartbeatPing(
                config.getAgentId(),
                now,
                tasks,
                cpuPercent,
                freeMemMb
        );
        ping.setHbSeq(hbSeqCounter.getAndIncrement());
        ping.setClockMs(now);
        ping.setClockOffsetMs(clockSyncManager.getClockOffsetMs());
        ping.setClockDelayMs(clockSyncManager.getClockDelayMs());

        // Gắn các chỉ số tự giám sát Self-metrics (ERRATA §E4.8)
        if (spoolManager != null) {
            ping.setSpoolDepth(spoolManager.getSpoolDepth());
            ping.setSpoolBytes(spoolManager.getSpoolBytes());
            ping.setDroppedResults(spoolManager.getDroppedResults());
            List<DataGap> gaps = spoolManager.getPendingGaps();
            if (!gaps.isEmpty()) {
                ping.setGaps(gaps);
                spoolManager.clearPendingGaps();
            }
        }
        ping.setSkippedProbes(skippedProbesCounter.get());
        int active = 0;
        for (AtomicBoolean b : targetRunningProbes.values()) {
            if (b.get()) active++;
        }
        ping.setActiveProbes(active);

        ping.setStreams(Collections.singletonList(
                new com.networkalert.protocol.messages.StreamProgress(streamId, 1, seqCounter.get())
        ));

        try {
            sendFrame(Frame.create(MessageType.HEARTBEAT_PING, ping));
        } catch (Exception e) {
            LoggerUtil.warn("[NcasAgent] Lỗi gửi HeartbeatPing: " + e.getMessage());
        }
    }

    /**
     * Lấy % tải CPU hệ thống qua Java Management API an toàn trên mọi JVM
     */
    private double getSystemCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            Method m = osBean.getClass().getMethod("getCpuLoad");
            Object val = m.invoke(osBean);
            if (val instanceof Number) {
                double d = ((Number) val).doubleValue();
                if (d >= 0) return Math.round(d * 1000.0) / 10.0;
            }
        } catch (Exception ignored) {
            try {
                double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
                if (load >= 0) return Math.round(load * 10.0) / 10.0;
            } catch (Exception ignored2) {}
        }
        return 0.0;
    }

    /**
     * Gửi Frame an toàn qua socket
     */
    public synchronized void sendFrame(Frame frame) throws IOException {
        if (!connected || socket == null || socket.isClosed()) {
            throw new IOException("Socket Agent chưa kết nối hoặc đã đóng!");
        }
        sendFrameDirect(frame);
    }

    private void sendFrameDirect(Frame frame) throws IOException {
        FrameEncoder.writeFrame(frame, out);
    }

    /**
     * Tạo thông điệp đăng ký danh tính từ thông tin máy trạm thực tế
     */
    private RegisterRequest buildRegisterRequest() {
        RealNetworkInfo netInfo = RealNetworkService.getInstance().getNetworkInfo();

        String hostname = "unknown";
        String ip = "127.0.0.1";
        try {
            InetAddress local = InetAddress.getLocalHost();
            hostname = local.getHostName();
            ip = local.getHostAddress();
        } catch (Exception ignored) {}

        if (netInfo.getLocalIp() != null && !netInfo.getLocalIp().isEmpty()) {
            ip = netInfo.getLocalIp();
        }

        String os = System.getProperty("os.name") + " " + System.getProperty("os.arch");
        String mac = netInfo.getMacAddress() != null ? netInfo.getMacAddress() : "00:00:00:00:00:00";

        List<String> protocols = Arrays.asList("TCP", "UDP", "HTTP", "HTTPS", "ICMP", "DNS");

        // Trong kênh TLS (§E2.2): gửi authToken được bảo vệ bên trong mã hóa TLS
        // Trong chế độ --no-tls an toàn (§E2.3): KHÔNG gửi token bản rõ (trừ khi bật --allow-legacy-auth)
        String tokenToSend;
        if (config.isTlsEnabled()) {
            tokenToSend = config.getAuthToken();
        } else {
            tokenToSend = config.isAllowLegacyAuth() ? config.getAuthToken() : null;
        }

        RegisterRequest req = new RegisterRequest(
                config.getAgentId(),
                hostname,
                os,
                ip,
                mac,
                tokenToSend,
                protocols,
                "1.1.0"
        );
        req.setProtoMax("1.1");
        req.setStreamId(streamId);
        req.setClockMs(clock.millis());
        req.setAgentName("NCAS-Agent-" + config.getAgentId());
        req.setAgentVersion("1.1.0");
        req.setCapabilities(Arrays.asList("stream", "batch", "gap", "ntp"));
        return req;
    }

    /**
     * Đóng socket an toàn
     */
    private synchronized void closeSocket() {
        connected = false;
        authenticated = false;
        sentInThisConnectionSeqs.clear();
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Dừng toàn bộ hoạt động của Agent với cơ chế Tắt êm (Graceful Shutdown - ERRATA §E4.7)
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        LoggerUtil.info("[NcasAgent] Đang tiến hành dừng NCAS Agent...");

        // Gửi DisconnectFrame tới Server nếu socket còn mở
        if (connected && socket != null && !socket.isClosed()) {
            try {
                sendFrameDirect(Frame.create(MessageType.DISCONNECT,
                        DisconnectMessage.of("AGENT_SHUTDOWN", "Người dùng chủ động dừng NCAS Agent")));
            } catch (Exception ignored) {}
        }

        closeSocket();

        // Hủy toàn bộ tác vụ đo kiểm
        for (ScheduledFuture<?> f : scheduledTasks.values()) {
            f.cancel(true);
        }
        scheduledTasks.clear();
        currentTargets.clear();
        targetRunningProbes.clear();

        // Chờ các tác vụ đo đang chạy tối đa 2 giây (§E4.7)
        monitorScheduler.shutdown();
        try {
            monitorScheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        heartbeatScheduler.shutdownNow();

        // Flush và đóng Spool đĩa bền vững
        if (spoolManager != null) {
            spoolManager.close();
        }

        if (networkThread != null) {
            networkThread.interrupt();
        }

        LoggerUtil.info("✅ [NcasAgent] NCAS Agent đã dừng hoàn toàn an toàn.");
    }

    // --- Getters phục vụ kiểm thử ---
    public boolean isRunning() { return running; }
    public boolean isConnected() { return connected; }
    public boolean isAuthenticated() { return authenticated; }
    public String getAssignedSessionId() { return assignedSessionId; }
    public AgentConfig getConfig() { return config; }
    public Map<Integer, ServerTarget> getCurrentTargets() { return Collections.unmodifiableMap(currentTargets); }
    public BlockingQueue<ReportResultMessage> getOfflineQueue() { return offlineQueue; }
    public long getStreamId() { return streamId; }
    public com.networkalert.protocol.ClockSyncManager getClockSyncManager() { return clockSyncManager; }

    /**
     * Điểm khởi chạy NCAS Agent từ dòng lệnh
     */
    public static void main(String[] args) {
        LoggerUtil.ensureUtf8Console();

        AgentConfig config = AgentConfig.parse(args);
        if (!config.isTlsEnabled()) {
            LoggerUtil.warn("⚠️  [AN NINH] NCAS Agent đang chạy ở chế độ --no-tls!");
            LoggerUtil.warn("⚠️  Kênh truyền không được mã hóa TLS. Mọi gói tin truyền qua mạng bản rõ.");
        } else {
            LoggerUtil.info("🔒 [AN NINH] NCAS Agent kích hoạt mã hóa an toàn TLS 1.2/1.3.");
            LoggerUtil.info("   • Ghim Fingerprint: " + (config.getServerFingerprints().isEmpty() ? "None (Mặc định)" : String.join(", ", config.getServerFingerprints())));
        }
        NcasAgent agent = new NcasAgent(config);

        // Đăng ký hook tắt an toàn khi bấm Ctrl + C
        Runtime.getRuntime().addShutdownHook(new NamedThreadFactory("NCAS-AgentShutdownHook").newThread(agent::stop));

        agent.start();

        // Giữ luồng chính chạy và nhận lệnh tương tác CLI
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            LoggerUtil.info("Nhập 'status', 'targets', 'queue', 'ping' hoặc 'exit' để điều khiển Agent:");

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim().toLowerCase();
                if ("exit".equals(line) || "quit".equals(line)) {
                    agent.stop();
                    System.exit(0);
                    break;
                } else if ("status".equals(line)) {
                    LoggerUtil.info(String.format("📡 Agent '%s' | Trạng thái: %s | Mục tiêu đang đo: %d | Hàng đợi offline: %d",
                            config.getAgentId(),
                            agent.isConnected() ? "🟢 ONLINE" : "🔴 OFFLINE",
                            agent.getCurrentTargets().size(),
                            agent.getOfflineQueue().size()));
                } else if ("targets".equals(line)) {
                    LoggerUtil.info("Danh sách mục tiêu đang gánh:");
                    for (ServerTarget t : agent.getCurrentTargets().values()) {
                        LoggerUtil.info(String.format("   • #%d '%s' (%s:%d - %s) - Chu kỳ: %ds",
                                t.getId(), t.getName(), t.getHost(), t.getPort(), t.getProtocol(), t.getIntervalSeconds()));
                    }
                } else if ("queue".equals(line)) {
                    LoggerUtil.info("Số lượng bản ghi trong hàng đợi Store-and-Forward: " + agent.getOfflineQueue().size());
                } else if ("ping".equals(line)) {
                    agent.sendHeartbeat();
                    LoggerUtil.info("Đã gửi thủ công Heartbeat Ping.");
                }
            }
        } catch (Exception e) {
            LoggerUtil.error("Lỗi Agent CLI: " + e.getMessage());
        }
    }
}

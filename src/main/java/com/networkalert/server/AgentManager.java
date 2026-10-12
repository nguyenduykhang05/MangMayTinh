package com.networkalert.server;

import com.networkalert.model.ProblemSeverity;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.DisconnectMessage;
import com.networkalert.protocol.messages.HeartbeatPing;
import com.networkalert.protocol.messages.HeartbeatPong;
import com.networkalert.protocol.messages.RegisterRequest;
import com.networkalert.protocol.messages.RegisterResponse;
import com.networkalert.repository.ProblemRepository;
import com.networkalert.service.AlertService;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NamedThreadFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Quản lý vòng đời toàn bộ các phiên Agent kết nối tới Server (Agent Manager).
 * <p>
 * Chức năng:
 * - Xác thực token đăng ký của Agent
 * - Quản lý bảng tra cứu Agent Session (Thread-safe)
 * - Quét và phát hiện Agent chết (Dead Agent Detection qua Heartbeat Timeout 30s)
 * - Bắn cảnh báo sự cố hạ tầng và kích hoạt tái cân bằng mục tiêu
 * - Gửi lệnh thông báo ngắt kết nối an toàn khi tắt máy chủ
 * </p>
 */
public class AgentManager {
    private static volatile AgentManager instance;

    public static AgentManager getInstance() {
        return instance;
    }

    private final AlertService alertService;
    private TargetDistributor targetDistributor;
    private com.networkalert.server.ingest.ResultSink resultSink;

    public void setResultSink(com.networkalert.server.ingest.ResultSink resultSink) {
        this.resultSink = resultSink;
    }

    public com.networkalert.server.ingest.ResultSink getResultSink() {
        return resultSink;
    }

    // Lưu các phiên đang kết nối theo Agent ID và Session ID
    private final Map<String, AgentSession> sessionsByAgentId = new ConcurrentHashMap<>();
    private final Map<String, AgentSession> sessionsBySessionId = new ConcurrentHashMap<>();
    private final java.util.Set<String> knownAgentIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Quản lý Enrollment Token dùng 1 lần & Secret riêng cho từng Agent (Giai đoạn 3E)
    public static class EnrollmentToken {
        private final String token;
        private final java.time.LocalDateTime createdAt;
        private final java.time.LocalDateTime expiresAt;
        private volatile boolean used;
        private volatile String boundAgentId;

        public EnrollmentToken(String token, int validityMinutes, java.time.Clock clock) {
            this.token = token;
            this.createdAt = java.time.LocalDateTime.now(clock != null ? clock : java.time.Clock.systemDefaultZone());
            this.expiresAt = this.createdAt.plusMinutes(validityMinutes);
            this.used = false;
        }

        public EnrollmentToken(String token, int validityMinutes) {
            this(token, validityMinutes, java.time.Clock.systemDefaultZone());
        }

        public boolean isValid(java.time.Clock clock) {
            return !used && java.time.LocalDateTime.now(clock != null ? clock : java.time.Clock.systemDefaultZone()).isBefore(expiresAt);
        }

        public boolean isValid() {
            return isValid(java.time.Clock.systemDefaultZone());
        }

        public String getToken() { return token; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public java.time.LocalDateTime getExpiresAt() { return expiresAt; }
        public boolean isUsed() { return used; }
        public String getBoundAgentId() { return boundAgentId; }
    }

    private final Map<String, EnrollmentToken> enrollmentTokens = new ConcurrentHashMap<>();
    private final Map<String, String> agentSecrets = new ConcurrentHashMap<>();
    private com.networkalert.security.AgentTokenManager agentTokenManager;

    private final ScheduledExecutorService heartbeatScanner;
    private final long heartbeatTimeoutMs;
    private final int heartbeatIntervalSec;
    private final java.time.Clock clock;

    private final java.util.List<Runnable> agentChangeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addAgentChangeListener(Runnable listener) {
        if (listener != null) agentChangeListeners.add(listener);
    }

    private void fireAgentChanged() {
        for (Runnable r : agentChangeListeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    public AgentManager(AlertService alertService, long heartbeatTimeoutMs, int heartbeatIntervalSec) {
        this(alertService, heartbeatTimeoutMs, heartbeatIntervalSec, java.time.Clock.systemUTC());
    }

    public AgentManager(AlertService alertService, long heartbeatTimeoutMs, int heartbeatIntervalSec, java.time.Clock clock) {
        instance = this;
        this.alertService = alertService;
        this.clock = clock != null ? clock : java.time.Clock.systemUTC();
        this.heartbeatTimeoutMs = heartbeatTimeoutMs > 0 ? heartbeatTimeoutMs : 30_000L;
        this.heartbeatIntervalSec = heartbeatIntervalSec > 0 ? heartbeatIntervalSec : 10;

        // Khởi động luồng quét nhịp tim định kỳ mỗi 5 giây
        this.heartbeatScanner = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("NCAS-HeartbeatScanner", true));
        this.heartbeatScanner.scheduleWithFixedDelay(this::scanHeartbeats, 5, 5, TimeUnit.SECONDS);
    }

    public java.time.Clock getClock() {
        return clock;
    }

    public void setTargetDistributor(TargetDistributor targetDistributor) {
        this.targetDistributor = targetDistributor;
    }

    public com.networkalert.security.AgentTokenManager getAgentTokenManager() {
        return agentTokenManager;
    }

    public void setAgentTokenManager(com.networkalert.security.AgentTokenManager agentTokenManager) {
        this.agentTokenManager = agentTokenManager;
    }

    /**
     * Xử lý yêu cầu đăng ký MSG_REGISTER_REQ từ Agent
     */
    public synchronized boolean registerAgent(AgentSession session, RegisterRequest req, String expectedToken) {
        if (req == null || req.getAgentId() == null || req.getAgentId().trim().isEmpty()) {
            com.networkalert.protocol.ProtocolMetrics.recordAuthFailure("unknown");
            sendRejectAndClose(session, "AUTH_FAIL: Định danh Agent ID không hợp lệ hoặc để trống!");
            return false;
        }

        String aId = req.getAgentId().trim();
        String providedToken = req.getAuthToken() != null ? req.getAuthToken().trim() : "";
        String dedicatedSecret = null;

        // 1. Kiểm tra qua AgentTokenManager (nếu Agent đã được cấp token riêng)
        if (agentTokenManager != null && agentTokenManager.getEntry(aId) != null) {
            com.networkalert.security.AgentTokenEntry entry = agentTokenManager.getEntry(aId);
            if (entry.isRevoked()) {
                LoggerUtil.warn(String.format("[AgentManager] Agent '%s' (%s) bị từ chối: Token đã bị thu hồi!",
                        aId, session.getRemoteAddress()));
                com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                sendRejectAndClose(session, "AUTH_FAIL: Token của Agent đã bị thu hồi!");
                return false;
            }
            if (!agentTokenManager.verifyToken(aId, providedToken)) {
                LoggerUtil.warn(String.format("[AgentManager] Agent '%s' (%s) bị từ chối: Sai Auth Token riêng!",
                        aId, session.getRemoteAddress()));
                com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                sendRejectAndClose(session, "AUTH_FAIL: Auth Token không chính xác!");
                return false;
            }
        } else if (providedToken.startsWith("ENROLL-")) {
            EnrollmentToken et = enrollmentTokens.get(providedToken);
            if (et == null || !et.isValid(clock)) {
                LoggerUtil.warn(String.format("[AgentManager] Agent '%s' sử dụng Enrollment Token không hợp lệ hoặc đã hết hạn: %s",
                        aId, providedToken));
                com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                sendRejectAndClose(session, "AUTH_FAIL: Enrollment Token không hợp lệ hoặc đã hết hạn!");
                return false;
            }
            // Đổi Enrollment Token lấy Dedicated Secret riêng
            dedicatedSecret = "SEC-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            et.used = true;
            et.boundAgentId = aId;
            agentSecrets.put(aId, dedicatedSecret);
            LoggerUtil.info(String.format("🎟️ [AgentManager] Agent '%s' đã đổi thành công Enrollment Token lấy Secret riêng (%s)",
                    aId, dedicatedSecret));
        } else if (agentSecrets.containsKey(aId)) {
            // Agent đã có Secret riêng: Yêu cầu phải khớp đúng Secret đã cấp (Constant-time)
            String validSecret = agentSecrets.get(aId);
            byte[] validBytes = validSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] provBytes = providedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!java.security.MessageDigest.isEqual(validBytes, provBytes)) {
                LoggerUtil.warn(String.format("[AgentManager] Agent '%s' (%s) bị từ chối: Sai Dedicated Secret đã cấp!",
                        aId, session.getRemoteAddress()));
                com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                sendRejectAndClose(session, "AUTH_FAIL: Sai Dedicated Secret của Agent!");
                return false;
            }
        } else if (expectedToken != null && !expectedToken.trim().isEmpty()) {
            byte[] expBytes = expectedToken.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] provBytes = providedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!java.security.MessageDigest.isEqual(expBytes, provBytes)) {
                LoggerUtil.warn(String.format("[AgentManager] Agent '%s' (%s) bị từ chối: Sai Auth Token!",
                        req.getAgentId(), session.getRemoteAddress()));
                com.networkalert.protocol.ProtocolMetrics.recordAuthFailure(aId);
                sendRejectAndClose(session, "AUTH_FAIL: Auth Token không chính xác!");
                return false;
            }
        }

        return completeRegistration(session, req, dedicatedSecret);
    }

    /**
     * Hoàn tất phiên đăng ký cho Agent sau khi đã vượt qua vòng xác thực (hoặc Challenge-Response).
     */
    public synchronized boolean completeRegistration(AgentSession session, RegisterRequest req, String dedicatedSecret) {
        String aId = req.getAgentId().trim();

        // 2. Nếu đã có phiên cũ của cùng Agent ID, đóng phiên cũ để nhận phiên mới (Reconnection Handling)
        AgentSession existing = sessionsByAgentId.get(aId);
        if (existing != null && existing != session) {
            LoggerUtil.warn(String.format("[AgentManager] Agent '%s' kết nối lại từ địa chỉ mới (%s). Đang đóng phiên cũ...",
                    aId, session.getRemoteAddress()));
            try {
                existing.sendFrame(MessageType.DISCONNECT, DisconnectMessage.of("REPLACED", "Phiên mới đã đăng ký từ địa chỉ khác"));
            } catch (Exception ignored) {}
            existing.close();
            sessionsBySessionId.remove(existing.getSessionId());
        }

        // 3. Cập nhật metadata cho phiên làm việc
        session.setAgentId(aId);
        session.setHostname(req.getHostname());
        session.setOs(req.getOs());
        session.setLocalIp(req.getLocalIp());
        session.setMacAddress(req.getMacAddress());
        session.setSupportedProtocols(req.getSupportedProtocols());
        session.setAgentVersion(req.getVersion());

        // Đàm phán phiên bản giao thức protoMax (ERRATA §E3.1)
        String clientProto = req.getProtoMax();
        String negotiatedProto = "1.0";
        if (clientProto != null && !clientProto.trim().isEmpty()) {
            // Server hỗ trợ tối đa 1.1: proto = min("1.1", clientProto)
            negotiatedProto = ("1.1".compareTo(clientProto.trim()) <= 0) ? "1.1" : clientProto.trim();
        }
        session.setProtoVersion(negotiatedProto);
        if (req.getStreamId() != null) {
            session.setCurrentStreamId(req.getStreamId());
            long initWm = (resultSink != null) ? resultSink.getWatermark(aId, req.getStreamId()) : 0L;
            session.getOrCreateStreamTracker(req.getStreamId(), initWm);
        }

        session.setAuthenticated(true);
        session.setLastHeartbeatTime(clock.millis());

        sessionsByAgentId.put(aId, session);
        sessionsBySessionId.put(session.getSessionId(), session);
        knownAgentIds.add(aId);

        // 4. Trả lời chấp nhận MSG_REGISTER_RESP (kèm Dedicated Secret nếu là Enrollment mới)
        RegisterResponse resp = (dedicatedSecret != null)
                ? RegisterResponse.acceptedWithSecret(
                        session.getSessionId(),
                        heartbeatIntervalSec,
                        "Enrollment thành công! Dedicated Secret đã được kích hoạt.",
                        dedicatedSecret)
                : RegisterResponse.accepted(
                        session.getSessionId(),
                        heartbeatIntervalSec,
                        "Đăng ký thành công vào NCAS Server.");
        if (session.isV11OrHigher()) {
            resp.setProtoVersion(negotiatedProto);
        }
        try {
            session.sendFrame(MessageType.REGISTER_RESP, resp);
        } catch (Exception e) {
            LoggerUtil.error("[AgentManager] Lỗi gửi phản hồi đăng ký cho Agent " + aId + ": " + e.getMessage());
            unregisterAgent(aId, "SEND_REGISTER_RESP_ERROR");
            return false;
        }

        LoggerUtil.info(String.format("🚀 [AgentManager] Agent '%s' (%s - %s) đã đăng ký thành công! Tổng số Agent trực tuyến: %d",
                aId, req.getHostname(), req.getLocalIp(), sessionsByAgentId.size()));

        // Bắn thông báo cảnh báo/hệ thống
        if (alertService != null) {
            alertService.notifyAgentConnected(aId, req.getLocalIp());
        }
        ProblemRepository.getInstance().resolveProblemsForSource("Agent " + aId, "Agent '" + aId + "' đã kết nối lại bình thường");

        // Tái cân bằng phân công mục tiêu
        if (targetDistributor != null) {
            targetDistributor.rebalance();
        }

        fireAgentChanged();
        return true;
    }

    private void sendRejectAndClose(AgentSession session, String reason) {
        try {
            RegisterResponse resp = RegisterResponse.rejected(reason);
            session.sendFrame(MessageType.REGISTER_RESP, resp);
        } catch (Exception ignored) {}
        session.close();
    }

    /**
     * Xử lý nhịp tim MSG_HEARTBEAT_PING từ Agent
     */
    public void handleHeartbeat(AgentSession session, HeartbeatPing ping) {
        session.setLastHeartbeatTime(clock.millis());
        if (ping != null) {
            session.setLastCpuUsage(ping.getCpuUsagePercent());
            session.setLastFreeMemoryMb(ping.getFreeMemoryMb());
            session.setActiveTasks(ping.getActiveTasks());

            if (ping.getClockOffsetMs() != null) {
                session.setClockOffsetMs(ping.getClockOffsetMs());
                session.setClockSkewDetected(Math.abs(ping.getClockOffsetMs()) > 5000L);
            }
            if (ping.getClockDelayMs() != null) {
                session.setClockDelayMs(ping.getClockDelayMs());
            }
            if (ping.getGaps() != null) {
                for (com.networkalert.protocol.messages.DataGap gap : ping.getGaps()) {
                    StreamTracker st = session.getOrCreateStreamTracker(gap.getStreamId());
                    st.applyGap(gap.getFromSeq(), gap.getToSeq());
                }
            }
        }

        // Cập nhật trạng thái Agent
        fireAgentChanged();
    }

    /**
     * Hủy đăng ký và đóng phiên của một Agent
     */
    public synchronized void unregisterAgent(String agentId, String reason) {
        if (agentId == null) return;
        AgentSession session = sessionsByAgentId.remove(agentId);
        if (session != null) {
            sessionsBySessionId.remove(session.getSessionId());
            session.close();

            LoggerUtil.alert(String.format("⚠️ [AgentManager] Agent '%s' đã ngắt kết nối. Lý do: %s. Số Agent còn lại: %d",
                    agentId, reason, sessionsByAgentId.size()));

            if (alertService != null) {
                alertService.notifyAgentDisconnected(agentId, session.getLocalIp(), reason);
            }

            ProblemRepository.getInstance().createOrGetActiveProblem(
                    0,
                    "Trạm đo " + agentId,
                    "Agent " + agentId,
                    ProblemSeverity.HIGH,
                    "Mất kết nối Agent: " + agentId,
                    "Agent ngắt kết nối: " + reason
            );

            if (targetDistributor != null) {
                targetDistributor.handleAgentDisconnected(agentId);
            }

            fireAgentChanged();
        }
    }

    /**
     * Quét định kỳ phát hiện Agent bị rớt mạng / chết đột tử (Dead Agent Detection)
     */
    public void scanHeartbeats() {
        for (AgentSession session : new ArrayList<>(sessionsByAgentId.values())) {
            if (session.isAuthenticated() && session.isTimedOut(heartbeatTimeoutMs, clock)) {
                String aId = session.getAgentId();
                long idleSec = (clock.millis() - session.getLastHeartbeatTime()) / 1000;
                String reason = String.format("Mất tín hiệu nhịp tim quá %d giây (vượt ngưỡng %d giây)",
                        idleSec, heartbeatTimeoutMs / 1000);
                unregisterAgent(aId, reason);
            }
        }
    }

    /**
     * Phát quảng bá Frame tới toàn bộ Agent đang trực tuyến
     */
    public void broadcast(Frame frame) {
        for (AgentSession session : sessionsByAgentId.values()) {
            if (session.isAuthenticated()) {
                try {
                    session.sendFrame(frame);
                } catch (Exception e) {
                    LoggerUtil.warn("[AgentManager] Lỗi broadcast tới Agent " + session.getAgentId() + ": " + e.getMessage());
                }
            }
        }
    }

    public Collection<AgentSession> getActiveSessions() {
        return Collections.unmodifiableCollection(new ArrayList<>(sessionsByAgentId.values()));
    }

    public Collection<AgentSession> getConnectedSessions() {
        return getActiveSessions();
    }

    public AgentSession getSession(String agentId) {
        return agentId != null ? sessionsByAgentId.get(agentId) : null;
    }

    public AgentSession getSessionById(String sessionId) {
        return sessionId != null ? sessionsBySessionId.get(sessionId) : null;
    }

    public int getActiveAgentCount() {
        return sessionsByAgentId.size();
    }

    public int getLostAgentCount() {
        return Math.max(0, knownAgentIds.size() - sessionsByAgentId.size());
    }

    public java.util.Set<String> getKnownAgentIds() {
        return Collections.unmodifiableSet(knownAgentIds);
    }

    // --- Quản lý Enrollment Tokens & Dedicated Secrets (Giai đoạn 3E) ---

    public EnrollmentToken generateEnrollmentToken(int validityMinutes) {
        String code = "ENROLL-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        EnrollmentToken token = new EnrollmentToken(code, validityMinutes > 0 ? validityMinutes : 15);
        enrollmentTokens.put(code, token);
        LoggerUtil.info(String.format("🎟️ [AgentManager] Đã tạo Enrollment Token mới: %s (Hạn dùng: %d phút)", code, validityMinutes));
        return token;
    }

    public boolean isValidEnrollmentToken(String token) {
        if (token == null) return false;
        EnrollmentToken et = enrollmentTokens.get(token.trim());
        return et != null && et.isValid();
    }

    public boolean revokeAgentSecret(String agentId) {
        if (agentId == null) return false;
        String removed = agentSecrets.remove(agentId);
        if (removed != null) {
            LoggerUtil.alert(String.format("🚫 [AgentManager] Đã thu hồi Dedicated Secret của Agent '%s'", agentId));
            // Đóng phiên nếu Agent đang trực tuyến
            unregisterAgent(agentId, "DEDICATED_SECRET_REVOKED");
            return true;
        }
        return false;
    }

    public Map<String, String> getAllAgentSecrets() {
        return Collections.unmodifiableMap(new HashMap<>(agentSecrets));
    }

    public List<EnrollmentToken> getAllEnrollmentTokens() {
        return new ArrayList<>(enrollmentTokens.values());
    }

    /**
     * Đóng an toàn toàn bộ các phiên Agent khi máy chủ tắt
     */
    public synchronized void shutdown() {
        heartbeatScanner.shutdownNow();

        Frame disconnectFrame = Frame.create(MessageType.DISCONNECT,
                DisconnectMessage.of("SERVER_SHUTDOWN", "Máy chủ NCAS Server đang dừng hoạt động"));

        for (AgentSession session : sessionsByAgentId.values()) {
            try {
                session.sendFrame(disconnectFrame);
            } catch (Exception ignored) {}
            session.close();
        }
        sessionsByAgentId.clear();
        sessionsBySessionId.clear();
        LoggerUtil.info("[AgentManager] Đã đóng toàn bộ phiên kết nối của các Agent.");
    }
}

package com.networkalert.server;

import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.ProtocolException;
import com.networkalert.util.LoggerUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý phiên làm việc TCP của một Agent kết nối tới Server.
 * <p>
 * Lưu giữ Socket, thông tin định danh máy trạm (Agent ID, Hostname, OS, IP, MAC),
 * danh sách mục tiêu đo kiểm được phân công, và đồng bộ gửi/nhận Frame giao thức NCAS-P.
 * </p>
 */
public class AgentSession {
    private final String sessionId;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Clock clock;
    private final long connectedAt;

    private volatile String agentId;
    private volatile String hostname;
    private volatile String os;
    private volatile String localIp;
    private volatile String macAddress;
    private volatile List<String> supportedProtocols = Collections.emptyList();
    private volatile String agentVersion;

    private volatile boolean authenticated = false;
    private volatile com.networkalert.protocol.messages.RegisterRequest pendingRegisterRequest;
    private volatile long lastHeartbeatTime;
    private volatile double lastCpuUsage = 0.0;
    private volatile long lastFreeMemoryMb = 0;
    private volatile int activeTasks = 0;
    private volatile boolean closed = false;

    // Danh sách ID các mục tiêu giám sát đang được giao cho Agent này xử lý
    private final Set<Integer> assignedTargetIds = ConcurrentHashMap.newKeySet();

    // Giao thức NCAS-P v1.1 & Quản lý luồng Stream (ERRATA §E3.1 - §E3.3)
    private volatile String protoVersion = "1.0"; // Mặc định v1.0 nếu client không khai protoMax
    private volatile Long currentStreamId;
    private final Map<Long, StreamTracker> streamTrackers = new ConcurrentHashMap<>();

    // Đồng bộ đồng hồ phân tán & cảnh báo lệch đồng hồ (ERRATA §E3.7)
    private volatile Long clockOffsetMs = 0L;
    private volatile Long clockDelayMs = 0L;
    private volatile boolean clockSkewDetected = false;

    public AgentSession(Socket socket) throws IOException {
        this(socket, Clock.systemUTC());
    }

    public AgentSession(Socket socket, Clock clock) throws IOException {
        this.sessionId = UUID.randomUUID().toString();
        this.socket = socket;
        this.socket.setTcpNoDelay(true); // Vô hiệu hóa Nagle Algorithm để giảm độ trễ điều khiển
        this.socket.setSoTimeout(5000);  // Timeout bắt tay tối đa 5 giây (E2.5, E3.8)
        this.in = new BufferedInputStream(socket.getInputStream(), 16384);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 16384);
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.connectedAt = this.clock.millis();
        this.lastHeartbeatTime = this.connectedAt;
    }

    /**
     * Gửi Frame giao thức an toàn (Thread-safe) sang máy trạm
     */
    public synchronized void sendFrame(Frame frame) throws IOException {
        if (closed || socket.isClosed()) {
            throw new IOException("AgentSession " + sessionId + " đã bị đóng!");
        }
        FrameEncoder.encodeToStream(frame, out);
    }

    /**
     * Tạo và gửi nhanh một Frame giao thức sang máy trạm
     */
    public synchronized void sendFrame(MessageType type, Object payloadObj) throws IOException {
        Frame frame = Frame.create(type, payloadObj);
        sendFrame(frame);
    }

    /**
     * Đọc khối Frame giao thức tiếp theo từ luồng vào Socket với giới hạn phòng thủ
     */
    public Frame readNextFrame() throws IOException, ProtocolException {
        if (closed || socket.isClosed()) {
            return null;
        }
        int maxAllowed = authenticated ? FrameDecoder.getMaxPayloadSize() : FrameDecoder.PRE_AUTH_MAX_PAYLOAD_SIZE;
        return FrameDecoder.readFrame(in, maxAllowed);
    }

    /**
     * Kiểm tra phiên có bị quá thời gian nhận nhịp tim hay không
     */
    public boolean isTimedOut(long timeoutMs) {
        return (clock.millis() - lastHeartbeatTime) > timeoutMs;
    }

    public boolean isTimedOut(long timeoutMs, Clock overrideClock) {
        long now = overrideClock != null ? overrideClock.millis() : clock.millis();
        return (now - lastHeartbeatTime) > timeoutMs;
    }

    /**
     * Kiểm tra Agent này có hỗ trợ một giao thức mạng cụ thể hay không (TCP, UDP, HTTP, HTTPS, ICMP, DNS)
     */
    public boolean supportsProtocol(String protocol) {
        if (protocol == null || supportedProtocols == null || supportedProtocols.isEmpty()) {
            return true; // Mặc định hỗ trợ nếu không khai báo
        }
        for (String p : supportedProtocols) {
            if (p.equalsIgnoreCase(protocol)) return true;
        }
        return false;
    }

    /**
     * Đóng an toàn kết nối socket và tài nguyên luồng
     */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {}
    }

    // --- Getters & Setters ---

    public String getSessionId() { return sessionId; }
    public Socket getSocket() { return socket; }
    public long getConnectedAt() { return connectedAt; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }

    public String getLocalIp() { return localIp; }
    public void setLocalIp(String localIp) { this.localIp = localIp; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public List<String> getSupportedProtocols() { return supportedProtocols; }
    public void setSupportedProtocols(List<String> supportedProtocols) {
        this.supportedProtocols = supportedProtocols != null ? new ArrayList<>(supportedProtocols) : Collections.emptyList();
    }

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public com.networkalert.protocol.messages.RegisterRequest getPendingRegisterRequest() { return pendingRegisterRequest; }
    public void setPendingRegisterRequest(com.networkalert.protocol.messages.RegisterRequest req) { this.pendingRegisterRequest = req; }

    public boolean isAuthenticated() { return authenticated; }
    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
        if (authenticated && socket != null && !socket.isClosed()) {
            try {
                // Sau khi xác thực: kết nối rảnh tối đa 3 chu kỳ nhịp tim (30 giây)
                socket.setSoTimeout(30_000);
            } catch (Exception ignored) {}
        }
    }

    public long getLastHeartbeatTime() { return lastHeartbeatTime; }
    public void setLastHeartbeatTime(long lastHeartbeatTime) { this.lastHeartbeatTime = lastHeartbeatTime; }

    public double getLastCpuUsage() { return lastCpuUsage; }
    public void setLastCpuUsage(double lastCpuUsage) { this.lastCpuUsage = lastCpuUsage; }

    public long getLastFreeMemoryMb() { return lastFreeMemoryMb; }
    public void setLastFreeMemoryMb(long lastFreeMemoryMb) { this.lastFreeMemoryMb = lastFreeMemoryMb; }

    public int getActiveTasks() { return activeTasks; }
    public void setActiveTasks(int activeTasks) { this.activeTasks = activeTasks; }

    public boolean isClosed() { return closed; }

    public Set<Integer> getAssignedTargetIds() { return assignedTargetIds; }

    public void setAssignedTargetIds(java.util.Collection<Integer> targetIds) {
        assignedTargetIds.clear();
        if (targetIds != null) {
            assignedTargetIds.addAll(targetIds);
        }
    }

    public String getRemoteAddress() {
        if (socket != null && socket.getRemoteSocketAddress() != null) {
            return socket.getRemoteSocketAddress().toString();
        }
        return "unknown";
    }

    /**
     * Địa chỉ IP remote thực tế từ tầng mạng TCP Socket của kết nối Agent
     */
    public String getRemoteIp() {
        if (socket != null && socket.getInetAddress() != null) {
            String ip = socket.getInetAddress().getHostAddress();
            if (ip != null && ip.startsWith("/")) {
                ip = ip.substring(1);
            }
            return ip;
        }
        return "unknown";
    }

    /**
     * Cổng TCP nguồn (Source Port) của Agent kết nối tới Server
     */
    public int getRemotePort() {
        if (socket != null) {
            return socket.getPort();
        }
        return 0;
    }

    public String getProtoVersion() {
        return protoVersion;
    }

    public void setProtoVersion(String protoVersion) {
        this.protoVersion = (protoVersion != null && !protoVersion.trim().isEmpty()) ? protoVersion.trim() : "1.0";
    }

    public boolean isV11OrHigher() {
        return !"1.0".equals(protoVersion);
    }

    public Long getCurrentStreamId() {
        return currentStreamId;
    }

    public void setCurrentStreamId(Long currentStreamId) {
        this.currentStreamId = currentStreamId;
    }

    public StreamTracker getOrCreateStreamTracker(long streamId) {
        return getOrCreateStreamTracker(streamId, 0L);
    }

    public StreamTracker getOrCreateStreamTracker(long streamId, long initialWatermark) {
        if (streamTrackers.size() >= 32 && !streamTrackers.containsKey(streamId)) {
            // Giới hạn tối đa 32 streams mỗi Agent theo §E3.3 (loại bỏ stream đầu tiên nếu quá ngưỡng)
            Long oldestKey = streamTrackers.keySet().iterator().next();
            streamTrackers.remove(oldestKey);
        }
        return streamTrackers.computeIfAbsent(streamId, id -> new StreamTracker(id, initialWatermark));
    }

    public Map<Long, StreamTracker> getStreamTrackers() {
        return Collections.unmodifiableMap(streamTrackers);
    }

    public Long getClockOffsetMs() { return clockOffsetMs; }
    public void setClockOffsetMs(Long clockOffsetMs) { this.clockOffsetMs = clockOffsetMs; }

    public Long getClockDelayMs() { return clockDelayMs; }
    public void setClockDelayMs(Long clockDelayMs) { this.clockDelayMs = clockDelayMs; }

    public boolean isClockSkewDetected() { return clockSkewDetected; }
    public void setClockSkewDetected(boolean clockSkewDetected) { this.clockSkewDetected = clockSkewDetected; }

    @Override
    public String toString() {
        return String.format("AgentSession[id=%s, agent=%s, ip=%s, host=%s, targets=%d]",
                sessionId, agentId != null ? agentId : "unauth", localIp != null ? localIp : getRemoteAddress(),
                hostname != null ? hostname : "?", assignedTargetIds.size());
    }
}

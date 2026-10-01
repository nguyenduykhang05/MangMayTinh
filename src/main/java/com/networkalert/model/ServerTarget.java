package com.networkalert.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mục tiêu máy chủ / dịch vụ mạng cần giám sát
 */
public class ServerTarget {
    private int id;
    private String name;
    private String host;
    private int port;
    private ProtocolType protocol;
    private int intervalSeconds;
    private int timeoutMs;
    private boolean enabled;

    // Phạm vi giám sát và phân bổ trạm đo (Scope: SERVER, AGENT, MULTI)
    private TargetScope scope = TargetScope.MULTI;
    private String group = "Default";
    private String tags = "";
    private List<String> assignedStations = new ArrayList<>();

    // Cấu hình kiểm tra kiểu Nagios (SOFT / HARD, Parent, Retry interval)
    private Integer parentId = null;
    private String parentAgent = null;
    private int maxCheckAttempts = 3;
    private int retryIntervalSeconds = 2;

    // Mở rộng Giai đoạn 3E: HttpChecker (Keyword, TLS Expiry) & Push Monitor
    private String expectedKeyword = null;
    private int tlsExpiryDays = -1;
    private java.time.LocalDate tlsExpiryDate = null;
    private String pushToken = null;
    private int pushGraceSeconds = 120;
    private long lastPushTimestamp = 0;

    // Các trường dữ liệu trạng thái thời gian thực (Runtime Metrics)
    private volatile ServerStatus status;
    private volatile long currentLatency;
    private volatile LocalDateTime lastCheckTime;
    private volatile LocalDateTime lastStatusChangeTime;
    private int totalChecks;
    private int successfulChecks;
    private int consecutiveFailures;
    private String lastErrorMessage;

    // Lưu trữ kết quả kiểm tra gần nhất theo từng trạm đo (Station ID -> CheckResult)
    private final Map<String, CheckResult> stationResults = new ConcurrentHashMap<>();
    // Lưu lịch sử độ trễ theo từng trạm đo để vẽ biểu đồ đa đường (Multi-line Latency Chart)
    private final Map<String, List<Long>> stationLatencies = new ConcurrentHashMap<>();
    private final Map<String, Long> stationLastCheckTimes = new ConcurrentHashMap<>();

    // Lưu trữ điểm độ trễ gần nhất để vẽ biểu đồ Real-time Latency Chart (tối đa 720 mẫu = 1 giờ ở chu kỳ 5s)
    private final List<Long> recentLatencies = new ArrayList<>();
    private static final int MAX_LATENCY_HISTORY = 720;
    private long warningThresholdMs = 0; // 0 = dùng ngưỡng toàn cục

    public ServerTarget() {
        this.status = ServerStatus.UNCHECKED;
        this.enabled = true;
        this.intervalSeconds = 5;
        this.timeoutMs = 1500;
        this.protocol = ProtocolType.TCP;
        this.port = 80;
        this.currentLatency = -1;
        this.scope = TargetScope.MULTI;
        this.group = "Default";
    }

    public ServerTarget(int id, String name, String host, int port, ProtocolType protocol, int intervalSeconds, int timeoutMs, boolean enabled) {
        this(id, name, host, port, protocol, intervalSeconds, timeoutMs, 0, enabled);
    }

    public ServerTarget(int id, String name, String host, int port, ProtocolType protocol, int intervalSeconds, int timeoutMs, long warningThresholdMs, boolean enabled) {
        this();
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.protocol = protocol;
        this.intervalSeconds = intervalSeconds;
        this.timeoutMs = timeoutMs;
        this.warningThresholdMs = warningThresholdMs;
        this.enabled = enabled;
    }

    public ServerTarget(int id, String name, String host, int port, ProtocolType protocol, int intervalSeconds, int timeoutMs, long warningThresholdMs, TargetScope scope, String group, boolean enabled) {
        this(id, name, host, port, protocol, intervalSeconds, timeoutMs, warningThresholdMs, enabled);
        this.scope = (scope != null) ? scope : TargetScope.MULTI;
        this.group = (group != null && !group.trim().isEmpty()) ? group : "Default";
    }

    // Quản lý chuyển đổi trạng thái mạng qua State Machine (POJO Hysteresis)
    private TargetStateMachine stateMachine;

    // Bộ đệm nhịp tim 60 lần kiểm tra gần nhất (Ring Buffer trong RAM kiểu Uptime Kuma)
    public static class HeartbeatRecord {
        private final long timestamp;
        private final ServerStatus status;
        private final long latencyMs;
        private final String stationId;
        private final String message;

        public HeartbeatRecord(long timestamp, ServerStatus status, long latencyMs, String stationId, String message) {
            this.timestamp = timestamp;
            this.status = status;
            this.latencyMs = latencyMs;
            this.stationId = stationId != null ? stationId : "Server";
            this.message = message != null ? message : "";
        }

        public long getTimestamp() { return timestamp; }
        public ServerStatus getStatus() { return status; }
        public long getLatencyMs() { return latencyMs; }
        public String getStationId() { return stationId; }
        public String getMessage() { return message; }
    }

    private final List<HeartbeatRecord> heartbeatBuffer = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HEARTBEATS = 60;

    // Tổng hợp kết quả theo phút phục vụ tính Uptime 24h / 7d / 30d
    public static class MinuteBucket {
        private final long minuteEpoch;
        private int totalChecks;
        private int okChecks;

        public MinuteBucket(long minuteEpoch) {
            this.minuteEpoch = minuteEpoch;
        }

        public synchronized void addCheck(boolean ok) {
            totalChecks++;
            if (ok) okChecks++;
        }

        public long getMinuteEpoch() { return minuteEpoch; }
        public int getTotalChecks() { return totalChecks; }
        public int getOkChecks() { return okChecks; }
    }

    private final Map<Long, MinuteBucket> minuteBuckets = new ConcurrentHashMap<>();

    public synchronized TargetStateMachine getStateMachine() {
        if (stateMachine == null) {
            stateMachine = new TargetStateMachine(this.id, this.name != null ? this.name : "", this.maxCheckAttempts, 2, this.retryIntervalSeconds);
            stateMachine.setParentId(this.parentId);
            stateMachine.setParentAgent(this.parentAgent);
        }
        return stateMachine;
    }

    /**
     * Ghi nhận kết quả đo từ một trạm đo cụ thể (Station: LOCAL hoặc Agent ID)
     * Đồng thời tự động tổng hợp Quorum khi có nhiều trạm kiểm tra
     */
    public synchronized Optional<TargetStateMachine.StateTransition> recordStationResult(String stationId, CheckResult result, long warningLatencyThreshold) {
        if (stationId == null || stationId.trim().isEmpty()) {
            stationId = (assignedAgent != null && !assignedAgent.isEmpty()) ? assignedAgent : "LOCAL";
        }

        stationResults.put(stationId, result);
        stationLastCheckTimes.put(stationId, System.currentTimeMillis());

        // Ghi lại sliding window độ trễ của riêng trạm này
        List<Long> latList = stationLatencies.computeIfAbsent(stationId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (latList) {
            latList.add(result.isSuccess() ? result.getLatencyMs() : 0L);
            if (latList.size() > MAX_LATENCY_HISTORY) {
                latList.remove(0);
            }
        }

        // Tính kết quả tổng hợp (Quorum Calculation)
        CheckResult aggregatedResult = result;
        if (scope == TargetScope.MULTI && stationResults.size() > 1) {
            int okCount = 0;
            int total = stationResults.size();
            long totalLat = 0;
            String lastErr = null;
            for (CheckResult r : stationResults.values()) {
                if (r.isSuccess()) {
                    okCount++;
                    totalLat += r.getLatencyMs();
                } else {
                    lastErr = r.getMessage();
                }
            }

            // Quorum: Quá bán (hoặc ít nhất 1 trạm tốt nếu chỉ có 2 trạm)
            boolean quorumSuccess = (okCount * 2 >= total) && (okCount > 0);
            long avgLat = (okCount > 0) ? (totalLat / okCount) : -1;
            String msg = String.format("Quorum %d/%d trạm OK%s", okCount, total, (lastErr != null ? " (" + lastErr + ")" : ""));
            aggregatedResult = new CheckResult(quorumSuccess, avgLat, msg);
        }

        return recordCheckResultInternal(aggregatedResult, warningLatencyThreshold, stationId);
    }

    public synchronized Optional<TargetStateMachine.StateTransition> recordCheckResult(CheckResult result, long warningLatencyThreshold) {
        String defaultStation = (assignedAgent != null && !assignedAgent.isEmpty()) ? assignedAgent : "LOCAL";
        return recordStationResult(defaultStation, result, warningLatencyThreshold);
    }

    private synchronized Optional<TargetStateMachine.StateTransition> recordCheckResultInternal(CheckResult result, long warningLatencyThreshold, String stationId) {
        this.lastCheckTime = LocalDateTime.now(); if (result != null && result.getMethod() != null) this.lastMethod = result.getMethod();

        String effectiveStation = (stationId != null && !stationId.trim().isEmpty())
                ? stationId
                : ((assignedAgent != null && !assignedAgent.isEmpty()) ? assignedAgent : "Server");

        // Kiểm tra xem mục tiêu có đang trong cửa sổ bảo trì (Downtime) hay không
        boolean underMaintenance = com.networkalert.service.MaintenanceService.getInstance().isUnderMaintenance(this);
        if (underMaintenance) {
            this.status = ServerStatus.MAINTENANCE;
            this.currentLatency = result.isSuccess() ? result.getLatencyMs() : -1;
            if (!result.isSuccess()) {
                this.lastErrorMessage = result.getMessage();
            } else {
                this.lastErrorMessage = null;
            }

            // Ghi nhận vào nhịp tim để hiển thị màu xanh dương (MAINTENANCE) trên giao diện Uptime Kuma
            synchronized (heartbeatBuffer) {
                heartbeatBuffer.add(new HeartbeatRecord(
                        System.currentTimeMillis(),
                        ServerStatus.MAINTENANCE,
                        this.currentLatency,
                        effectiveStation,
                        "Đang trong cửa sổ bảo trì (Maintenance Downtime)"
                ));
                if (heartbeatBuffer.size() > MAX_HEARTBEATS) {
                    heartbeatBuffer.remove(0);
                }
            }

            // Loại bỏ hoàn toàn khỏi tính SLA: KHÔNG tăng totalChecks, KHÔNG ghi vào minuteBuckets
            return Optional.empty();
        }

        this.totalChecks++;

        long effectiveThreshold = (this.warningThresholdMs > 0) ? this.warningThresholdMs : warningLatencyThreshold;

        if (result.isSuccess()) {
            this.successfulChecks++;
            this.currentLatency = result.getLatencyMs();
            this.lastErrorMessage = null;
        } else {
            this.currentLatency = -1;
            this.lastErrorMessage = result.getMessage();
        }

        // Chuyển đổi trạng thái mạng qua TargetStateMachine (Hysteresis & Chống flapping)
        Optional<TargetStateMachine.StateTransition> transition = getStateMachine().processResult(result, effectiveThreshold);
        this.status = getStateMachine().getCurrentStatus();
        this.consecutiveFailures = getStateMachine().getConsecutiveFailures();

        // Lưu vào sliding window độ trễ chung
        synchronized (recentLatencies) {
            recentLatencies.add(result.isSuccess() ? result.getLatencyMs() : 0L);
            if (recentLatencies.size() > MAX_LATENCY_HISTORY) {
                recentLatencies.remove(0);
            }
        }

        // Lưu vào dải 60 nhịp tim (Heartbeat Ring Buffer kiểu Uptime Kuma)
        ServerStatus heartbeatStatus;
        if (!this.enabled) {
            heartbeatStatus = ServerStatus.PAUSED;
        } else if (!result.isSuccess()) {
            heartbeatStatus = ServerStatus.OFFLINE;
        } else if (result.getLatencyMs() > effectiveThreshold) {
            heartbeatStatus = ServerStatus.WARNING;
        } else {
            heartbeatStatus = ServerStatus.ONLINE;
        }


        synchronized (heartbeatBuffer) {
            heartbeatBuffer.add(new HeartbeatRecord(
                    System.currentTimeMillis(),
                    heartbeatStatus,
                    this.currentLatency,
                    effectiveStation,
                    this.lastErrorMessage
            ));
            if (heartbeatBuffer.size() > MAX_HEARTBEATS) {
                heartbeatBuffer.remove(0);
            }
        }

        // Cập nhật bucket theo phút cho Uptime 24h / 7d / 30d
        long currentMinute = System.currentTimeMillis() / 60000;
        minuteBuckets.computeIfAbsent(currentMinute, MinuteBucket::new).addCheck(result.isSuccess());

        if (transition.isPresent()) {
            this.lastStatusChangeTime = LocalDateTime.now();
        }
        return transition;
    }

    /**
     * Chuỗi tóm tắt trạm đo / Quorum phục vụ hiển thị trên bảng
     */
    public String getQuorumSummary() {
        if (scope == TargetScope.SERVER) {
            return "trên Server";
        }
        if (scope == TargetScope.AGENT) {
            return (assignedAgent != null && !assignedAgent.isEmpty()) ? assignedAgent : "Chưa gán";
        }
        if (stationResults.isEmpty()) {
            return "Đang chờ";
        }
        long okCount = stationResults.values().stream().filter(CheckResult::isSuccess).count();
        return okCount + "/" + stationResults.size() + " OK";
    }

    public synchronized double getUptimePercentage() {
        if (totalChecks == 0) return 100.0;
        return (successfulChecks * 100.0) / totalChecks;
    }

    public synchronized double getUptime24h() {
        return calculateUptime(1440);
    }

    public synchronized double getUptime7d() {
        return calculateUptime(10080);
    }

    public synchronized double getUptime30d() {
        return calculateUptime(43200);
    }

    private synchronized double calculateUptime(int minuteWindow) {
        if (minuteBuckets.isEmpty()) {
            return getUptimePercentage();
        }
        long nowMinute = System.currentTimeMillis() / 60000;
        long startMinute = nowMinute - minuteWindow;
        long total = 0;
        long ok = 0;
        for (Map.Entry<Long, MinuteBucket> entry : minuteBuckets.entrySet()) {
            if (entry.getKey() >= startMinute) {
                MinuteBucket b = entry.getValue();
                total += b.getTotalChecks();
                ok += b.getOkChecks();
            }
        }
        if (total == 0) {
            return getUptimePercentage();
        }
        return (ok * 100.0) / total;
    }

    public synchronized List<HeartbeatRecord> getHeartbeats() {
        synchronized (heartbeatBuffer) {
            return new ArrayList<>(heartbeatBuffer);
        }
    }

    public String getStatusDurationFormatted() {
        LocalDateTime start = (lastStatusChangeTime != null) ? lastStatusChangeTime : lastCheckTime;
        if (start == null) return "Chưa xác định";
        java.time.Duration d = java.time.Duration.between(start, LocalDateTime.now());
        long mins = d.toMinutes();
        if (mins < 1) return "Vừa mới đây";
        if (mins < 60) return mins + " phút";
        long hours = d.toHours();
        long remMins = mins % 60;
        if (hours < 24) {
            return hours + " giờ" + (remMins > 0 ? " " + remMins + " phút" : "");
        }
        long days = d.toDays();
        long remHours = hours % 24;
        return days + " ngày" + (remHours > 0 ? " " + remHours + " giờ" : "");
    }

    public synchronized List<Long> getRecentLatencies() {
        synchronized (recentLatencies) {
            return new ArrayList<>(recentLatencies);
        }
    }

    public synchronized List<Long> getRecentLatencies(int count) {
        synchronized (recentLatencies) {
            if (count <= 0 || recentLatencies.size() <= count) {
                return new ArrayList<>(recentLatencies);
            }
            int from = recentLatencies.size() - count;
            return new ArrayList<>(recentLatencies.subList(from, recentLatencies.size()));
        }
    }

    public synchronized Map<String, CheckResult> getStationResults() {
        return new ConcurrentHashMap<>(stationResults);
    }

    public synchronized Map<String, List<Long>> getStationLatencies() {
        Map<String, List<Long>> copy = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<Long>> entry : stationLatencies.entrySet()) {
            synchronized (entry.getValue()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return copy;
    }

    public synchronized void resetStatistics() {
        this.totalChecks = 0;
        this.successfulChecks = 0;
        this.consecutiveFailures = 0;
        this.currentLatency = -1;
        if (stateMachine != null) {
            stateMachine.reset();
        }
        this.status = ServerStatus.UNCHECKED;
        synchronized (recentLatencies) {
            recentLatencies.clear();
        }
        stationResults.clear();
        stationLatencies.clear();
        stationLastCheckTimes.clear();
        synchronized (heartbeatBuffer) {
            heartbeatBuffer.clear();
        }
        minuteBuckets.clear();
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public ProtocolType getProtocol() { return protocol; }
    public void setProtocol(ProtocolType protocol) { this.protocol = protocol; }

    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            getStateMachine().pause();
            this.status = ServerStatus.PAUSED;
        } else if (this.status == ServerStatus.PAUSED) {
            getStateMachine().resume();
            this.status = getStateMachine().getCurrentStatus();
        }
    }

    public ServerStatus getStatus() { return status; }
    public void setStatus(ServerStatus status) { this.status = status; }

    public long getCurrentLatency() { return currentLatency; }
    public LocalDateTime getLastCheckTime() { return lastCheckTime; }
    public LocalDateTime getLastStatusChangeTime() { return lastStatusChangeTime; }
    public int getTotalChecks() { return totalChecks; }
    public int getSuccessfulChecks() { return successfulChecks; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public long getWarningThresholdMs() { return warningThresholdMs; }
    public void setWarningThresholdMs(long warningThresholdMs) { this.warningThresholdMs = warningThresholdMs; }

    private volatile String assignedAgent = null;

    public String getAssignedAgent() { return assignedAgent; }
    public void setAssignedAgent(String assignedAgent) { this.assignedAgent = assignedAgent; }

    public TargetScope getScope() { return scope; }
    public void setScope(TargetScope scope) { this.scope = (scope != null) ? scope : TargetScope.MULTI; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = (group != null && !group.trim().isEmpty()) ? group : "Default"; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = (tags != null) ? tags : ""; }

    public List<String> getAssignedStations() { return assignedStations; }
    public void setAssignedStations(List<String> stations) {
        this.assignedStations = (stations != null) ? new ArrayList<>(stations) : new ArrayList<>();
    }

    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) {
        this.parentId = parentId;
        if (stateMachine != null) stateMachine.setParentId(parentId);
    }

    public String getParentAgent() { return parentAgent; }
    public void setParentAgent(String parentAgent) {
        this.parentAgent = parentAgent;
        if (stateMachine != null) stateMachine.setParentAgent(parentAgent);
    }

    public int getMaxCheckAttempts() { return maxCheckAttempts; }
    public void setMaxCheckAttempts(int maxCheckAttempts) {
        this.maxCheckAttempts = Math.max(1, maxCheckAttempts);
        if (stateMachine != null) stateMachine.setMaxCheckAttempts(this.maxCheckAttempts);
    }

    public int getRetryIntervalSeconds() { return retryIntervalSeconds; }
    public void setRetryIntervalSeconds(int retryIntervalSeconds) {
        this.retryIntervalSeconds = Math.max(1, retryIntervalSeconds);
        if (stateMachine != null) stateMachine.setRetryIntervalSeconds(this.retryIntervalSeconds);
    }

    public StateType getStateType() {
        return getStateMachine().getStateType();
    }

    public boolean isFlapping() {
        return getStateMachine().isFlapping();
    }

    public double getFlappingRatio() {
        return getStateMachine().getFlappingRatio();
    }

    public int getCurrentAttempt() {
        return getStateMachine().getCurrentAttempt();
    }

    public String getAttemptDisplay() {
        return getStateMachine().getAttemptDisplay();
    }

    public int getEffectiveIntervalSeconds() {
        if (getStateType() == StateType.SOFT) {
            return retryIntervalSeconds > 0 ? retryIntervalSeconds : 2;
        }
        return intervalSeconds > 0 ? intervalSeconds : 5;
    }

    public String getExpectedKeyword() { return expectedKeyword; }
    public void setExpectedKeyword(String expectedKeyword) { this.expectedKeyword = expectedKeyword; }

    public int getTlsExpiryDays() { return tlsExpiryDays; }
    public void setTlsExpiryDays(int tlsExpiryDays) { this.tlsExpiryDays = tlsExpiryDays; }

    public java.time.LocalDate getTlsExpiryDate() { return tlsExpiryDate; }
    public void setTlsExpiryDate(java.time.LocalDate tlsExpiryDate) { this.tlsExpiryDate = tlsExpiryDate; }

    public String getPushToken() { return pushToken; }
    public void setPushToken(String pushToken) { this.pushToken = pushToken; }

    public int getPushGraceSeconds() { return pushGraceSeconds; }
    public void setPushGraceSeconds(int pushGraceSeconds) { this.pushGraceSeconds = pushGraceSeconds; }

    public long getLastPushTimestamp() { return lastPushTimestamp; }
    public void setLastPushTimestamp(long lastPushTimestamp) { this.lastPushTimestamp = lastPushTimestamp; }

    private volatile String lastMethod;
    public String getLastMethod() { return lastMethod; }
    public void setLastMethod(String lastMethod) { this.lastMethod = lastMethod; }
    public boolean isIcmpFallback() {
        return protocol == ProtocolType.ICMP && "tcp".equalsIgnoreCase(lastMethod);
    }

    private volatile String lastDiagnosis;
    public String getLastDiagnosis() { return lastDiagnosis; }
    public void setLastDiagnosis(String lastDiagnosis) { this.lastDiagnosis = lastDiagnosis; }
    public String getLastDiagnosisSummary() {
        if (lastDiagnosis == null || lastDiagnosis.isEmpty()) return null;
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(lastDiagnosis).getAsJsonObject();
            if (obj.has("summary")) return obj.get("summary").getAsString();
        } catch (Exception ignored) {}
        return lastDiagnosis;
    }

    @Override
    public String toString() {
        return String.format("%s (%s:%d - %s, scope=%s)", name, host, port, protocol, scope);
    }
}


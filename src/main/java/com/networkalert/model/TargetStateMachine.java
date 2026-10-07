package com.networkalert.model;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Máy trạng thái hữu hạn (Finite State Machine) quản lý vòng đời trạng thái của một mục tiêu giám sát mạng.
 * <p>
 * Hiện thực đầy đủ mô hình giám sát chuyên nghiệp kiểu Nagios / Zabbix:
 * <ul>
 *   <li><b>SOFT / HARD State</b>: Khi gặp lỗi mới, trạng thái là SOFT (thử lại lần 1/3, 2/3 với tần suất dày hơn theo {@code retryInterval}).
 *       Chỉ khi thất bại vượt quá {@code maxCheckAttempts} thì mới chính thức chuyển sang HARD.
 *       <b>Chỉ HARD mới sinh cảnh báo (Toast, Email, Telegram, Sound)</b>. Khi phục hồi từ HARD cũng sinh thông báo.</li>
 *   <li><b>UNREACHABLE theo quan hệ Cha–Con (Parent Dependency)</b>: Khi thiết bị cha (Parent target hoặc Agent) bị HARD DOWN / LOST,
 *       các mục tiêu con sẽ lập tức chuyển sang trạng thái {@link ServerStatus#UNREACHABLE},
 *       <b>không</b> gửi cảnh báo riêng cho con mà chỉ cảnh báo thiết bị cha. Có thuật toán phát hiện và từ chối vòng lặp (Parent Cycle).</li>
 *   <li><b>Chống rung lắc Flapping (Flapping Detection)</b>: Tính toán tỷ lệ biến thiên trạng thái trong $N$ kết quả gần nhất ($N=20$).
 *       Vượt ngưỡng cao (mặc định 25%) thì đánh dấu FLAPPING và tạm gộp thông báo; xuống dưới ngưỡng thấp (mặc định 10%) thì tự động bỏ.</li>
 * </ul>
 * </p>
 */
public class TargetStateMachine {

    public static final int DEFAULT_FAIL_THRESHOLD = 3;
    public static final int DEFAULT_RECOVERY_THRESHOLD = 2;
    public static final int DEFAULT_RETRY_INTERVAL_SECONDS = 2;

    private final int targetId;
    private final String targetName;

    private ServerStatus currentStatus = ServerStatus.UNKNOWN;
    private StateType stateType = StateType.HARD;

    private int failThreshold = DEFAULT_FAIL_THRESHOLD; // maxCheckAttempts
    private int recoveryThreshold = DEFAULT_RECOVERY_THRESHOLD;
    private int retryIntervalSeconds = DEFAULT_RETRY_INTERVAL_SECONDS;

    private int consecutiveFailures = 0;
    private int consecutiveSuccesses = 0;

    // Quan hệ phụ thuộc thiết bị cha (Parent)
    private Integer parentId = null;
    private String parentAgent = null;

    // Bộ nhớ đệm phục vụ thuật toán phát hiện rung lắc mạng (Flapping Detection)
    private static final int FLAPPING_HISTORY_SIZE = 20;
    private final List<ServerStatus> recentStatusHistory = new ArrayList<>();
    private boolean flapping = false;
    private double flappingRatio = 0.0;
    private double flappingHighThreshold = 25.0; // Ngưỡng kích hoạt flapping (%)
    private double flappingLowThreshold = 10.0;  // Ngưỡng kết thúc flapping (%)

    /**
     * Sự kiện ghi nhận một lần chuyển đổi trạng thái mạng (State Transition)
     */
    public static class StateTransition {
        private final int targetId;
        private final String targetName;
        private final ServerStatus fromStatus;
        private final ServerStatus toStatus;
        private final StateType stateType;
        private final int currentAttempt;
        private final int maxAttempts;
        private final boolean hardTransition;
        private final boolean flapping;
        private final CheckResult checkResult;
        private final String description;
        private final long timestamp;

        public StateTransition(int targetId, String targetName, ServerStatus fromStatus, ServerStatus toStatus,
                               CheckResult checkResult, String description) {
            this(targetId, targetName, fromStatus, toStatus, StateType.HARD, 1, 1, true, false, checkResult, description);
        }

        public StateTransition(int targetId, String targetName, ServerStatus fromStatus, ServerStatus toStatus,
                               StateType stateType, int currentAttempt, int maxAttempts, boolean hardTransition,
                               boolean flapping, CheckResult checkResult, String description, long timestamp) {
            this.targetId = targetId;
            this.targetName = targetName;
            this.fromStatus = fromStatus;
            this.toStatus = toStatus;
            this.stateType = stateType;
            this.currentAttempt = currentAttempt;
            this.maxAttempts = maxAttempts;
            this.hardTransition = hardTransition;
            this.flapping = flapping;
            this.checkResult = checkResult;
            this.description = description;
            this.timestamp = timestamp;
        }

        public StateTransition(int targetId, String targetName, ServerStatus fromStatus, ServerStatus toStatus,
                               StateType stateType, int currentAttempt, int maxAttempts, boolean hardTransition,
                               boolean flapping, CheckResult checkResult, String description) {
            this(targetId, targetName, fromStatus, toStatus, stateType, currentAttempt, maxAttempts, hardTransition, flapping, checkResult, description, System.currentTimeMillis());
        }

        public int getTargetId() { return targetId; }
        public String getTargetName() { return targetName; }
        public ServerStatus getFromStatus() { return fromStatus; }
        public ServerStatus getToStatus() { return toStatus; }
        public StateType getStateType() { return stateType; }
        public int getCurrentAttempt() { return currentAttempt; }
        public int getMaxAttempts() { return maxAttempts; }
        public boolean isHardTransition() { return hardTransition; }
        public boolean isFlapping() { return flapping; }
        public CheckResult getCheckResult() { return checkResult; }
        public String getDescription() { return description; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("[%s -> %s (%s %d/%d)] Mục tiêu '%s' (#%d)%s: %s",
                    fromStatus, toStatus, stateType, currentAttempt, maxAttempts,
                    targetName, targetId, flapping ? " [FLAPPING]" : "", description);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StateTransition that = (StateTransition) o;
            return targetId == that.targetId && fromStatus == that.fromStatus && toStatus == that.toStatus;
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetId, fromStatus, toStatus);
        }
    }

    private final Clock clock;

    public TargetStateMachine(int targetId, String targetName) {
        this(targetId, targetName, DEFAULT_FAIL_THRESHOLD, DEFAULT_RECOVERY_THRESHOLD);
    }

    public TargetStateMachine(int targetId, String targetName, int failThreshold, int recoveryThreshold) {
        this(targetId, targetName, failThreshold, recoveryThreshold, DEFAULT_RETRY_INTERVAL_SECONDS);
    }

    public TargetStateMachine(int targetId, String targetName, int failThreshold, int recoveryThreshold, int retryIntervalSeconds) {
        this(targetId, targetName, failThreshold, recoveryThreshold, retryIntervalSeconds, Clock.systemUTC());
    }

    public TargetStateMachine(int targetId, String targetName, int failThreshold, int recoveryThreshold, int retryIntervalSeconds, Clock clock) {
        this.targetId = targetId;
        this.targetName = targetName;
        this.failThreshold = Math.max(1, failThreshold);
        this.recoveryThreshold = Math.max(1, recoveryThreshold);
        this.retryIntervalSeconds = Math.max(1, retryIntervalSeconds);
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public Clock getClock() {
        return clock;
    }

    /**
     * Tiếp nhận kết quả đo lường và tính toán chuyển đổi trạng thái SOFT/HARD và Flapping
     */
    public synchronized Optional<StateTransition> processResult(CheckResult result, long warningLatencyThreshold) {
        if (currentStatus == ServerStatus.PAUSED) {
            return Optional.empty();
        }

        // UNKNOWN (lỗi nội bộ checker, sai cấu hình) KHÔNG tính vào failThreshold (ERRATA §E6.5)
        if (result != null && result.isUnknown()) {
            return Optional.empty();
        }

        boolean success = result != null && result.isSuccess();
        long latency = result != null ? result.getLatencyMs() : 0;
        boolean isHighLatency = success && (warningLatencyThreshold > 0) && (latency > warningLatencyThreshold);

        // Ghi lại trạng thái đơn lẻ vào lịch sử để tính rung lắc (Flapping)
        ServerStatus evaluatedSingleStatus = success
                ? (isHighLatency ? ServerStatus.WARNING : ServerStatus.ONLINE)
                : ServerStatus.OFFLINE;
        recordStatusHistory(evaluatedSingleStatus);

        if (success) {
            consecutiveSuccesses++;
            consecutiveFailures = 0;
            this.stateType = StateType.HARD;

            switch (currentStatus) {
                case UNKNOWN:
                case UNCHECKED:
                case UNREACHABLE:
                    ServerStatus initialStatus = isHighLatency ? ServerStatus.WARNING : ServerStatus.ONLINE;
                    this.stateType = StateType.HARD;
                    return transitionTo(initialStatus, StateType.HARD, 1, failThreshold, true, result,
                            "Khởi tạo trạng thái lần đầu: " + initialStatus + " (" + latency + " ms)");

                case OFFLINE:
                    if (consecutiveSuccesses >= recoveryThreshold) {
                        ServerStatus recoveredStatus = isHighLatency ? ServerStatus.WARNING : ServerStatus.ONLINE;
                        this.stateType = StateType.HARD;
                        String desc = isHighLatency
                                ? String.format("Khôi phục sau sự cố nhưng độ trễ cao (%d ms > %d ms ngưỡng)", latency, warningLatencyThreshold)
                                : String.format("Khôi phục hoạt động bình thường sau %d lần thành công (%d ms)", consecutiveSuccesses, latency);
                        // Hồi phục từ HARD OFFLINE chính thức -> sinh thông báo recovery
                        return transitionTo(recoveredStatus, StateType.HARD, 1, failThreshold, true, result, desc);
                    }
                    // Chưa đủ M lần thành công, tiếp tục giữ OFFLINE để xác thực độ ổn định
                    return Optional.empty();

                case ONLINE:
                    if (isHighLatency) {
                        return transitionTo(ServerStatus.WARNING, StateType.HARD, 1, failThreshold, true, result,
                                String.format("Độ trễ vượt ngưỡng: %d ms > %d ms", latency, warningLatencyThreshold));
                    }
                    return Optional.empty();

                case WARNING:
                    if (!isHighLatency) {
                        return transitionTo(ServerStatus.ONLINE, StateType.HARD, 1, failThreshold, true, result,
                                String.format("Độ trễ đã giảm về mức bình thường: %d ms <= %d ms", latency, warningLatencyThreshold));
                    }
                    return Optional.empty();

                default:
                    return Optional.empty();
            }

        } else {
            // Kiểm tra thất bại (Timeout, Refused, Packet Loss)
            consecutiveFailures++;
            consecutiveSuccesses = 0;

            if (currentStatus == ServerStatus.OFFLINE) {
                this.stateType = StateType.HARD;
                return Optional.empty();
            }

            // SOFT Phase: Nếu chưa đủ số lần thử lại tối đa (maxCheckAttempts), chuyển SOFT
            if (consecutiveFailures < failThreshold) {
                this.stateType = StateType.SOFT;
                // SOFT không chuyển trạng thái chính thức, không sinh cảnh báo
                return Optional.empty();
            }

            // HARD Phase: Khi thất bại đã đủ N lần liên tiếp -> Chuyển HARD OFFLINE & Kích hoạt Alert
            this.stateType = StateType.HARD;
            ServerStatus old = this.currentStatus;
            return transitionTo(ServerStatus.OFFLINE, StateType.HARD, consecutiveFailures, failThreshold, true, result,
                    String.format("Mất kết nối hoàn toàn sau %d lần thất bại liên tiếp (HARD): %s",
                            consecutiveFailures, result.getMessage()));
        }
    }

    /**
     * Ghi nhận và đánh giá tỷ lệ rung lắc mạng (Flapping Detection theo chuẩn Nagios)
     */
    private void recordStatusHistory(ServerStatus st) {
        recentStatusHistory.add(st);
        if (recentStatusHistory.size() > FLAPPING_HISTORY_SIZE) {
            recentStatusHistory.remove(0);
        }

        if (recentStatusHistory.size() >= 4) {
            int changes = 0;
            for (int i = 1; i < recentStatusHistory.size(); i++) {
                if (recentStatusHistory.get(i) != recentStatusHistory.get(i - 1)) {
                    changes++;
                }
            }
            double ratio = ((double) changes / (recentStatusHistory.size() - 1)) * 100.0;
            this.flappingRatio = ratio;

            if (!this.flapping && ratio >= flappingHighThreshold) {
                this.flapping = true;
            } else if (this.flapping && ratio <= flappingLowThreshold) {
                this.flapping = false;
            }
        }
    }

    /**
     * Đánh dấu mục tiêu là UNREACHABLE do thiết bị mạng cấp trên (Parent) hoặc Agent bị sự cố.
     * Khi chuyển UNREACHABLE: Không gửi cảnh báo riêng cho mục tiêu con.
     */
    public synchronized Optional<StateTransition> markUnreachable(String reason) {
        if (currentStatus == ServerStatus.UNREACHABLE) {
            return Optional.empty();
        }
        ServerStatus old = this.currentStatus;
        this.currentStatus = ServerStatus.UNREACHABLE;
        this.stateType = StateType.HARD;
        // hardTransition = false để không sinh cảnh báo cho con
        return Optional.of(new StateTransition(
                targetId, targetName, old, ServerStatus.UNREACHABLE,
                StateType.HARD, 1, failThreshold, false, flapping,
                CheckResult.failure(reason), reason, clock.millis()
        ));
    }

    /**
     * Xóa cờ UNREACHABLE khi thiết bị mạng cấp trên (Parent) đã hồi phục
     */
    public synchronized Optional<StateTransition> clearUnreachable() {
        if (currentStatus != ServerStatus.UNREACHABLE) {
            return Optional.empty();
        }
        ServerStatus old = this.currentStatus;
        this.currentStatus = ServerStatus.UNKNOWN;
        this.consecutiveFailures = 0;
        this.consecutiveSuccesses = 0;
        this.stateType = StateType.HARD;
        return Optional.of(new StateTransition(
                targetId, targetName, old, ServerStatus.UNKNOWN,
                StateType.HARD, 1, failThreshold, false, flapping,
                CheckResult.success(0, "Thiết bị cha đã khôi phục"),
                "Thiết bị cha đã khôi phục, sẵn sàng kiểm tra lại", clock.millis()
        ));
    }

    /**
     * Kiểm tra phát hiện vòng lặp phân cấp thiết bị cha (Parent Cycle Detection).
     *
     * @param targetId    ID mục tiêu đang cấu hình
     * @param newParentId ID mục tiêu cha dự kiến gán
     * @param allTargets  Bản đồ toàn bộ mục tiêu trong hệ thống
     * @return true nếu xuất hiện vòng lặp, false nếu hợp lệ
     */
    public static boolean wouldCreateCycle(int targetId, Integer newParentId, Map<Integer, ServerTarget> allTargets) {
        if (newParentId == null || newParentId <= 0) {
            return false;
        }
        if (newParentId == targetId) {
            return true; // Tự trỏ vào chính mình
        }

        Set<Integer> visited = new HashSet<>();
        visited.add(targetId);

        Integer current = newParentId;
        while (current != null && current > 0) {
            if (visited.contains(current)) {
                return true; // Phát hiện chu trình khép kín!
            }
            visited.add(current);

            ServerTarget parentTarget = (allTargets != null) ? allTargets.get(current) : null;
            if (parentTarget == null) {
                break;
            }
            current = parentTarget.getParentId();
        }
        return false;
    }

    /**
     * Tạm dừng giám sát mục tiêu
     */
    public synchronized Optional<StateTransition> pause() {
        if (currentStatus == ServerStatus.PAUSED) {
            return Optional.empty();
        }
        return transitionTo(ServerStatus.PAUSED, StateType.HARD, 1, 1, false,
                CheckResult.failure("Giám sát đã bị tạm dừng"), "Người dùng tạm dừng giám sát");
    }

    /**
     * Tiếp tục giám sát mục tiêu
     */
    public synchronized Optional<StateTransition> resume() {
        if (currentStatus != ServerStatus.PAUSED) {
            return Optional.empty();
        }
        consecutiveFailures = 0;
        consecutiveSuccesses = 0;
        this.stateType = StateType.HARD;
        return transitionTo(ServerStatus.UNKNOWN, StateType.HARD, 1, 1, false,
                CheckResult.success(0, "Tiếp tục giám sát"), "Tiếp tục giám sát mục tiêu");
    }

    /**
     * Đặt lại trạng thái về ban đầu (UNKNOWN)
     */
    public synchronized void reset() {
        this.currentStatus = ServerStatus.UNKNOWN;
        this.stateType = StateType.HARD;
        this.consecutiveFailures = 0;
        this.consecutiveSuccesses = 0;
        this.recentStatusHistory.clear();
        this.flapping = false;
        this.flappingRatio = 0.0;
    }

    private Optional<StateTransition> transitionTo(ServerStatus newStatus, StateType type, int attempt, int maxAttempts,
                                                   boolean hardTransition, CheckResult result, String description) {
        ServerStatus old = this.currentStatus;
        this.currentStatus = newStatus;
        this.stateType = type;
        return Optional.of(new StateTransition(targetId, targetName, old, newStatus, type, attempt, maxAttempts,
                hardTransition, flapping, result, description, clock.millis()));
    }

    public synchronized ServerStatus getCurrentStatus() { return currentStatus; }
    public synchronized StateType getStateType() { return stateType; }
    public synchronized int getConsecutiveFailures() { return consecutiveFailures; }
    public synchronized int getConsecutiveSuccesses() { return consecutiveSuccesses; }
    public synchronized int getCurrentAttempt() {
        if (consecutiveFailures > 0) {
            return Math.min(consecutiveFailures, failThreshold);
        }
        return 1;
    }
    public synchronized String getAttemptDisplay() {
        if (stateType == StateType.SOFT) {
            return "lần " + consecutiveFailures + "/" + failThreshold;
        }
        return (currentStatus == ServerStatus.OFFLINE)
                ? ("lần " + failThreshold + "/" + failThreshold + " (HARD)")
                : "HARD";
    }

    public int getFailThreshold() { return failThreshold; }
    public void setFailThreshold(int failThreshold) { this.failThreshold = Math.max(1, failThreshold); }
    public int getMaxCheckAttempts() { return failThreshold; }
    public void setMaxCheckAttempts(int maxCheckAttempts) { setFailThreshold(maxCheckAttempts); }

    public int getRecoveryThreshold() { return recoveryThreshold; }
    public void setRecoveryThreshold(int recoveryThreshold) { this.recoveryThreshold = Math.max(1, recoveryThreshold); }

    public int getRetryIntervalSeconds() { return retryIntervalSeconds; }
    public void setRetryIntervalSeconds(int retryIntervalSeconds) { this.retryIntervalSeconds = Math.max(1, retryIntervalSeconds); }

    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }

    public String getParentAgent() { return parentAgent; }
    public void setParentAgent(String parentAgent) { this.parentAgent = parentAgent; }

    public synchronized boolean isFlapping() { return flapping; }
    public synchronized void setFlapping(boolean flapping) { this.flapping = flapping; }
    public synchronized double getFlappingRatio() { return flappingRatio; }

    public double getFlappingHighThreshold() { return flappingHighThreshold; }
    public void setFlappingHighThreshold(double high) { this.flappingHighThreshold = high; }

    public double getFlappingLowThreshold() { return flappingLowThreshold; }
    public void setFlappingLowThreshold(double low) { this.flappingLowThreshold = low; }

    public int getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
}

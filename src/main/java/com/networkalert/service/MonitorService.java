package com.networkalert.service;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ProblemSeverity;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetStateMachine;
import com.networkalert.repository.ProblemRepository;
import com.networkalert.service.checker.CheckerFactory;
import com.networkalert.service.checker.IProtocolChecker;
import com.networkalert.util.LoggerUtil;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Dịch vụ giám sát mạng đa luồng (Multi-threaded Network Monitoring Engine)
 * Sử dụng ScheduledThreadPoolExecutor để kiểm tra song song nhiều mục tiêu với chu kỳ riêng biệt.
 */
public class MonitorService {
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<Integer, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final List<ServerTarget> targets = new CopyOnWriteArrayList<>();
    private final AlertService alertService;
    private final Clock clock;
    private final List<BiConsumer<ServerTarget, CheckResult>> updateListeners = new CopyOnWriteArrayList<>();
    private boolean running = false;

    public MonitorService(AlertService alertService) {
        this(alertService, Clock.systemUTC());
    }

    public MonitorService(AlertService alertService, Clock clock) {
        this.alertService = alertService;
        this.clock = clock != null ? clock : Clock.systemUTC();
        // Khởi tạo ThreadPool với 8 worker threads
        this.scheduler = new ScheduledThreadPoolExecutor(8, r -> {
            Thread t = new Thread(r);
            t.setName("NetworkMonitor-Worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public Clock getClock() {
        return clock;
    }

    public void addUpdateListener(BiConsumer<ServerTarget, CheckResult> listener) {
        updateListeners.add(listener);
    }

    public synchronized void setTargets(List<ServerTarget> newTargets) {
        stopAll();
        targets.clear();
        targets.addAll(newTargets);
    }

    public List<ServerTarget> getTargets() {
        return targets;
    }

    public synchronized void startAll() {
        if (running) return;
        running = true;
        LoggerUtil.info("Bắt đầu khởi động giám sát toàn bộ máy chủ...");
        for (ServerTarget target : targets) {
            scheduleTarget(target);
        }
    }

    public synchronized void stopAll() {
        running = false;
        LoggerUtil.info("Tạm dừng toàn bộ quá trình giám sát.");
        for (ScheduledFuture<?> task : scheduledTasks.values()) {
            task.cancel(true);
        }
        scheduledTasks.clear();
    }

    public synchronized void scheduleTarget(ServerTarget target) {
        // Hủy lịch cũ nếu có
        ScheduledFuture<?> existing = scheduledTasks.remove(target.getId());
        if (existing != null) {
            existing.cancel(true);
        }

        if (!running || !target.isEnabled()) {
            return;
        }

        int interval = Math.max(1, target.getEffectiveIntervalSeconds());
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> performCheck(target),
                0, interval, TimeUnit.SECONDS
        );
        scheduledTasks.put(target.getId(), future);
    }

    public void checkTargetNow(ServerTarget target) {
        scheduler.submit(() -> performCheck(target));
    }

    public ServerTarget findTargetById(int id) {
        for (ServerTarget t : targets) {
            if (t.getId() == id) return t;
        }
        return null;
    }

    private void performCheck(ServerTarget target) {
        if (!target.isEnabled()) return;

        // 1. Kiểm tra quan hệ phụ thuộc thiết bị cha (Parent Dependency theo chuẩn Nagios)
        if (target.getParentId() != null && target.getParentId() > 0) {
            ServerTarget parent = findTargetById(target.getParentId());
            if (parent != null && parent.getStatus() == ServerStatus.OFFLINE && parent.getStateType() == com.networkalert.model.StateType.HARD) {
                // Thiết bị cha đang HARD DOWN -> Con lập tức chuyển UNREACHABLE, không sinh cảnh báo cho con
                target.getStateMachine().markUnreachable(
                        String.format("Thiết bị mạng cấp trên '%s' đang bị sự cố (HARD DOWN)", parent.getName())
                );
                target.setStatus(ServerStatus.UNREACHABLE);
                notifyUpdateListeners(target, CheckResult.failure("Thiết bị cấp trên đang mất kết nối (UNREACHABLE)"));
                return;
            } else if (target.getStatus() == ServerStatus.UNREACHABLE) {
                // Thiết bị cha đã khôi phục -> Xóa trạng thái UNREACHABLE để kiểm tra lại
                target.getStateMachine().clearUnreachable();
                target.setStatus(target.getStateMachine().getCurrentStatus());
            }
        }

        // Đồng bộ số lần thử lại tối đa (Fail threshold) từ cấu hình
        if (alertService != null && alertService.getConfig() != null) {
            int maxRetries = alertService.getConfig().getMaxRetries();
            if (maxRetries > 0) {
                target.getStateMachine().setFailThreshold(maxRetries);
            }
        }

        int prevEffectiveInterval = target.getEffectiveIntervalSeconds();

        // 2. Thực thi kiểm tra giao thức mạng
        IProtocolChecker checker = CheckerFactory.getChecker(target.getProtocol());
        CheckResult result = checker.check(target);

        long threshold = (alertService != null && alertService.getConfig() != null)
                ? alertService.getConfig().getLatencyThresholdMs()
                : 300;
        Optional<TargetStateMachine.StateTransition> transitionOpt = target.recordCheckResult(result, threshold);

        // 3. Xử lý chuyển đổi trạng thái: CHỈ HARD MỚI SINH CẢNH BÁO, GỘP THÔNG BÁO KHI FLAPPING
        if (transitionOpt.isPresent() && alertService != null) {
            TargetStateMachine.StateTransition transition = transitionOpt.get();

            if (transition.isHardTransition() && !transition.isFlapping()) {
                ServerStatus from = transition.getFromStatus();
                ServerStatus to = transition.getToStatus();

                if (to == ServerStatus.OFFLINE) {
                    alertService.notifyOffline(target, result.getMessage());
                    ProblemRepository.getInstance().createOrGetActiveProblem(
                            target.getId(),
                            target.getName(),
                            (target.getAssignedAgent() != null && !target.getAssignedAgent().isEmpty()) ? target.getAssignedAgent() : "Server",
                            ProblemSeverity.HIGH,
                            "Mất kết nối (HARD DOWN): " + target.getName(),
                            (result.getMessage() != null ? result.getMessage() : "Mục tiêu không phản hồi qua giao thức " + target.getProtocol())
                    );
                } else if (from == ServerStatus.OFFLINE && to == ServerStatus.ONLINE) {
                    alertService.notifyRecovered(target, target.getCurrentLatency());
                    ProblemRepository.getInstance().resolveActiveProblemsForTarget(
                            target.getId(),
                            String.format("Mục tiêu đã hồi phục kết nối (HARD ONLINE - RTT: %d ms)", target.getCurrentLatency())
                    );
                } else if (from == ServerStatus.OFFLINE && to == ServerStatus.WARNING) {
                    // Phục hồi sau sự cố nhưng trễ cao: Báo độ trễ cao, tránh bắn liên tiếp 2 alert
                    alertService.notifyHighLatency(target, target.getCurrentLatency());
                    ProblemRepository.getInstance().resolveActiveProblemsForTarget(
                            target.getId(),
                            String.format("Mục tiêu đã hồi phục nhưng trễ cao (%d ms)", target.getCurrentLatency())
                    );
                    ProblemRepository.getInstance().createOrGetActiveProblem(
                            target.getId(),
                            target.getName(),
                            (target.getAssignedAgent() != null && !target.getAssignedAgent().isEmpty()) ? target.getAssignedAgent() : "Server",
                            ProblemSeverity.WARNING,
                            "Độ trễ cao: " + target.getName(),
                            String.format("Độ trễ %d ms vượt ngưỡng (%d ms)", target.getCurrentLatency(), threshold)
                    );
                } else if (from != ServerStatus.WARNING && to == ServerStatus.WARNING) {
                    alertService.notifyHighLatency(target, target.getCurrentLatency());
                    ProblemRepository.getInstance().createOrGetActiveProblem(
                            target.getId(),
                            target.getName(),
                            (target.getAssignedAgent() != null && !target.getAssignedAgent().isEmpty()) ? target.getAssignedAgent() : "Server",
                            ProblemSeverity.WARNING,
                            "Độ trễ cao: " + target.getName(),
                            String.format("Độ trễ %d ms vượt ngưỡng (%d ms)", target.getCurrentLatency(), threshold)
                    );
                } else if (from == ServerStatus.WARNING && to == ServerStatus.ONLINE) {
                    alertService.notifyLatencyRecovered(target, target.getCurrentLatency());
                    ProblemRepository.getInstance().resolveActiveProblemsForTarget(
                            target.getId(),
                            String.format("Độ trễ mạng đã bình thường trở lại (%d ms)", target.getCurrentLatency())
                    );
                }
            } else if (transition.isFlapping()) {
                LoggerUtil.warn(String.format("⚠️ [Chống Flapping] Mục tiêu '%s' đang rung lắc (%.1f%%). Đã tạm gộp cảnh báo.",
                        target.getName(), target.getFlappingRatio()));
            }
        }

        // 4. Nếu chu kỳ kiểm tra thay đổi giữa SOFT (retry_interval) và HARD (intervalSeconds) -> Điều chỉnh lại lịch
        if (target.getEffectiveIntervalSeconds() != prevEffectiveInterval) {
            scheduleTarget(target);
        }

        // 5. Bắn sự kiện cập nhật giao diện
        notifyUpdateListeners(target, result);
    }

    private void notifyUpdateListeners(ServerTarget target, CheckResult result) {
        for (BiConsumer<ServerTarget, CheckResult> listener : updateListeners) {
            try {
                listener.accept(target, result);
            } catch (Exception ignored) {}
        }
    }

    public synchronized void addTarget(ServerTarget target) {
        targets.add(target);
        if (running && target.isEnabled()) {
            scheduleTarget(target);
        }
    }

    public synchronized void updateTarget(ServerTarget target) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getId() == target.getId()) {
                targets.set(i, target);
                break;
            }
        }
        if (running) {
            scheduleTarget(target);
        }
    }

    public synchronized void removeTarget(int targetId) {
        ScheduledFuture<?> f = scheduledTasks.remove(targetId);
        if (f != null) {
            f.cancel(true);
        }
        targets.removeIf(t -> t.getId() == targetId);
    }

    public boolean isRunning() {
        return running;
    }

    public void shutdown() {
        stopAll();
        scheduler.shutdownNow();
    }
}

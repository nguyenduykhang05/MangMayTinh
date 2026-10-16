package com.networkalert.service;

import com.networkalert.model.MaintenanceScope;
import com.networkalert.model.MaintenanceWindow;
import com.networkalert.model.ServerTarget;
import com.networkalert.util.LoggerUtil;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Quản lý các cửa sổ bảo trì hệ thống (Maintenance / Downtime Engine)
 */
public class MaintenanceService {
    private static final MaintenanceService INSTANCE = new MaintenanceService();

    private final Clock clock;
    private final List<MaintenanceWindow> windows = new CopyOnWriteArrayList<>();

    public static MaintenanceService getInstance() {
        return INSTANCE;
    }

    public MaintenanceService() {
        this(Clock.systemDefaultZone());
    }

    public MaintenanceService(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        // Khởi tạo một số cửa sổ mẫu
        windows.add(new MaintenanceWindow(
                1, "Bảo trì định kỳ Chủ Nhật", MaintenanceScope.ALL, "",
                java.time.DayOfWeek.SUNDAY, java.time.LocalTime.of(3, 0), java.time.LocalTime.of(5, 0)
        ));
    }

    public Clock getClock() {
        return clock;
    }

    public List<MaintenanceWindow> getAllWindows() {
        return Collections.unmodifiableList(new ArrayList<>(windows));
    }

    public void addWindow(MaintenanceWindow window) {
        if (window == null) return;
        if (window.getId() <= 0) {
            int nextId = windows.stream().mapToInt(MaintenanceWindow::getId).max().orElse(0) + 1;
            window.setId(nextId);
        }
        windows.add(window);
        LoggerUtil.info(String.format("🔧 [Bảo trì] Đã thiết lập cửa sổ bảo trì: '%s' (%s)",
                window.getName(), window.getScheduleDisplay()));
    }

    public boolean removeWindow(int id) {
        boolean removed = windows.removeIf(w -> w.getId() == id);
        if (removed) {
            LoggerUtil.info("🔧 [Bảo trì] Đã xóa cửa sổ bảo trì #" + id);
        }
        return removed;
    }

    /**
     * Kiểm tra xem mục tiêu có đang nằm trong thời gian bảo trì (Downtime) hay không
     */
    public boolean isUnderMaintenance(ServerTarget target) {
        return isUnderMaintenance(target, this.clock);
    }

    /**
     * Kiểm tra xem mục tiêu có đang nằm trong thời gian bảo trì với Clock tùy chọn hay không
     */
    public boolean isUnderMaintenance(ServerTarget target, Clock customClock) {
        if (target == null) return false;
        Clock clk = customClock != null ? customClock : this.clock;
        for (MaintenanceWindow w : windows) {
            if (w.appliesTo(target, clk)) {
                return true;
            }
        }
        return false;
    }
}

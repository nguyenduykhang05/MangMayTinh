package com.networkalert.model;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Cửa sổ bảo trì định kỳ hoặc một lần (Maintenance Window / Downtime)
 */
public class MaintenanceWindow {
    private int id;
    private String name;
    private MaintenanceScope scopeType = MaintenanceScope.ALL;
    private String scopeValue = ""; // targetId, groupName, hoặc agentId
    private boolean recurring = false; // true = lặp theo thứ trong tuần, false = 1 lần

    // Cấu hình một lần (One-off)
    private LocalDateTime startDateTime;
    private LocalDateTime endDateTime;

    // Cấu hình lặp lại theo tuần (Weekly Recurring)
    private DayOfWeek dayOfWeek = DayOfWeek.SUNDAY;
    private LocalTime startTime = LocalTime.of(2, 0); // 02:00
    private LocalTime endTime = LocalTime.of(4, 0);   // 04:00

    private boolean enabled = true;

    public MaintenanceWindow() {}

    public MaintenanceWindow(int id, String name, MaintenanceScope scopeType, String scopeValue,
                             LocalDateTime startDateTime, LocalDateTime endDateTime) {
        this.id = id;
        this.name = name;
        this.scopeType = scopeType != null ? scopeType : MaintenanceScope.ALL;
        this.scopeValue = scopeValue != null ? scopeValue : "";
        this.recurring = false;
        this.startDateTime = startDateTime;
        this.endDateTime = endDateTime;
        this.enabled = true;
    }

    public MaintenanceWindow(int id, String name, MaintenanceScope scopeType, String scopeValue,
                             DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.id = id;
        this.name = name;
        this.scopeType = scopeType != null ? scopeType : MaintenanceScope.ALL;
        this.scopeValue = scopeValue != null ? scopeValue : "";
        this.recurring = true;
        this.dayOfWeek = dayOfWeek != null ? dayOfWeek : DayOfWeek.SUNDAY;
        this.startTime = startTime != null ? startTime : LocalTime.of(2, 0);
        this.endTime = endTime != null ? endTime : LocalTime.of(4, 0);
        this.enabled = true;
    }

    /**
     * Kiểm tra xem cửa sổ bảo trì này có đang hiệu lực ở thời điểm do Clock cung cấp hay không
     */
    public boolean isActive(Clock clock) {
        if (!enabled) return false;
        LocalDateTime now = LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());

        if (!recurring) {
            if (startDateTime == null || endDateTime == null) return false;
            return !now.isBefore(startDateTime) && !now.isAfter(endDateTime);
        } else {
            if (now.getDayOfWeek() != dayOfWeek) return false;
            LocalTime curTime = now.toLocalTime();
            if (startTime == null || endTime == null) return false;
            if (startTime.isAfter(endTime)) {
                // Khung giờ vắt qua nửa đêm (ví dụ: 23:00 -> 02:00)
                return !curTime.isBefore(startTime) || !curTime.isAfter(endTime);
            } else {
                return !curTime.isBefore(startTime) && !curTime.isAfter(endTime);
            }
        }
    }

    /**
     * Kiểm tra xem cửa sổ bảo trì này có đang hiệu lực ở thời điểm hiện tại hay không
     */
    public boolean isActiveNow() {
        return isActive(Clock.systemDefaultZone());
    }

    /**
     * Kiểm tra xem cửa sổ bảo trì này có áp dụng cho một mục tiêu cụ thể tại thời điểm do Clock cung cấp hay không
     */
    public boolean appliesTo(ServerTarget target, Clock clock) {
        if (target == null) return false;
        if (!isActive(clock)) return false;

        switch (scopeType) {
            case ALL:
                return true;
            case TARGET:
                try {
                    int tId = Integer.parseInt(scopeValue.trim());
                    return target.getId() == tId;
                } catch (Exception e) {
                    return target.getName().equalsIgnoreCase(scopeValue.trim());
                }
            case GROUP:
                return target.getGroup() != null && target.getGroup().equalsIgnoreCase(scopeValue.trim());
            case AGENT:
                return (target.getAssignedAgent() != null && target.getAssignedAgent().equalsIgnoreCase(scopeValue.trim()))
                        || (target.getParentAgent() != null && target.getParentAgent().equalsIgnoreCase(scopeValue.trim()));
            default:
                return false;
        }
    }

    /**
     * Kiểm tra xem cửa sổ bảo trì này có áp dụng cho một mục tiêu cụ thể hay không
     */
    public boolean appliesTo(ServerTarget target) {
        return appliesTo(target, Clock.systemDefaultZone());
    }

    public String getScheduleDisplay() {
        if (!recurring) {
            return String.format("Một lần: %s -> %s",
                    startDateTime != null ? startDateTime.toString().replace('T', ' ') : "?",
                    endDateTime != null ? endDateTime.toString().replace('T', ' ') : "?");
        } else {
            return String.format("Lặp lại: %s (%s - %s)", dayOfWeek, startTime, endTime);
        }
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public MaintenanceScope getScopeType() { return scopeType; }
    public void setScopeType(MaintenanceScope scopeType) { this.scopeType = scopeType; }

    public String getScopeValue() { return scopeValue; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }

    public boolean isRecurring() { return recurring; }
    public void setRecurring(boolean recurring) { this.recurring = recurring; }

    public LocalDateTime getStartDateTime() { return startDateTime; }
    public void setStartDateTime(LocalDateTime startDateTime) { this.startDateTime = startDateTime; }

    public LocalDateTime getEndDateTime() { return endDateTime; }
    public void setEndDateTime(LocalDateTime endDateTime) { this.endDateTime = endDateTime; }

    public DayOfWeek getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(DayOfWeek dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

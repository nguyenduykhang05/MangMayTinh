package com.networkalert.model;

/**
 * Phạm vi áp dụng của cửa sổ bảo trì (Maintenance Scope)
 */
public enum MaintenanceScope {
    TARGET("Mục tiêu đơn"),
    GROUP("Nhóm mục tiêu"),
    AGENT("Trạm Agent"),
    ALL("Toàn bộ hệ thống");

    private final String label;

    MaintenanceScope(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}

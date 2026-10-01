package com.networkalert.model;

import java.awt.Color;

/**
 * Trạng thái kết nối của máy chủ được giám sát
 */
public enum ServerStatus {
    ONLINE("ONLINE", new Color(46, 204, 113), new Color(39, 174, 96)),       // Xanh lục
    OFFLINE("OFFLINE", new Color(231, 76, 60), new Color(192, 57, 43)),      // Đỏ
    WARNING("WARNING", new Color(241, 196, 15), new Color(212, 172, 13)),    // Vàng cam (chậm / lag)
    PAUSED("PAUSED", new Color(149, 165, 166), new Color(127, 140, 141)),    // Xám
    UNKNOWN("UNKNOWN", new Color(189, 195, 199), new Color(149, 165, 166)),  // Chưa xác định (khởi động)
    UNCHECKED("UNCHECKED", new Color(189, 195, 199), new Color(149, 165, 166)), // Tương thích ngược
    UNREACHABLE("UNREACHABLE", new Color(148, 163, 184), new Color(100, 116, 139)), // Không thể tới (Thiết bị cha hoặc Agent lỗi)
    MAINTENANCE("MAINTENANCE", new Color(59, 130, 246), new Color(37, 99, 235)); // Đang bảo trì hệ thống (Màu xanh dương)

    private final String label;
    private final Color primaryColor;
    private final Color darkColor;

    ServerStatus(String label, Color primaryColor, Color darkColor) {
        this.label = label;
        this.primaryColor = primaryColor;
        this.darkColor = darkColor;
    }

    public String getLabel() {
        return label;
    }

    public Color getPrimaryColor() {
        return primaryColor;
    }

    public Color getDarkColor() {
        return darkColor;
    }
}

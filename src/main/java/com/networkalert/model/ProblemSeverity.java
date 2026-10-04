package com.networkalert.model;

import java.awt.Color;

/**
 * Mức độ nghiêm trọng của sự cố theo chuẩn Zabbix / Nagios (Problem Severity)
 */
public enum ProblemSeverity {
    INFORMATION("Thông tin", new Color(0x3B, 0x82, 0xF6), new Color(0x1D, 0x4E, 0xD8)),  // Xanh lam
    WARNING("Cảnh báo", new Color(0xF5, 0x9E, 0x0B), new Color(0xB4, 0x53, 0x09)),       // Vàng hổ phách
    HIGH("Nghiêm trọng", new Color(0xF9, 0x73, 0x16), new Color(0xC2, 0x41, 0x0C)),      // Cam đậm
    DISASTER("Thảm họa", new Color(0xEF, 0x44, 0x44), new Color(0xB9, 0x1C, 0x1C));       // Đỏ tươi

    private final String label;
    private final Color primaryColor;
    private final Color darkColor;

    ProblemSeverity(String label, Color primaryColor, Color darkColor) {
        this.label = label;
        this.primaryColor = primaryColor;
        this.darkColor = darkColor;
    }

    public String getLabel() { return label; }
    public Color getPrimaryColor() { return primaryColor; }
    public Color getDarkColor() { return darkColor; }

    public static ProblemSeverity fromString(String str) {
        if (str == null || str.trim().isEmpty()) return HIGH;
        for (ProblemSeverity s : values()) {
            if (s.name().equalsIgnoreCase(str.trim()) || s.label.equalsIgnoreCase(str.trim())) {
                return s;
            }
        }
        return HIGH;
    }
}

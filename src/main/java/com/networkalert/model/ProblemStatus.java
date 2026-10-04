package com.networkalert.model;

/**
 * Trạng thái xử lý sự cố (Problem Status)
 */
public enum ProblemStatus {
    ACTIVE("Đang diễn ra"),
    ACKNOWLEDGED("Đã xác nhận (Ack)"),
    RESOLVED("Đã giải quyết");

    private final String label;

    ProblemStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    public static ProblemStatus fromString(String str) {
        if (str == null || str.trim().isEmpty()) return ACTIVE;
        for (ProblemStatus s : values()) {
            if (s.name().equalsIgnoreCase(str.trim()) || s.label.equalsIgnoreCase(str.trim())) {
                return s;
            }
        }
        return ACTIVE;
    }
}

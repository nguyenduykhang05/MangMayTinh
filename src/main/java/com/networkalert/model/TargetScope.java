package com.networkalert.model;

/**
 * Phạm vi đo kiểm của mục tiêu (Monitoring Scope)
 */
public enum TargetScope {
    /**
     * Đo kiểm trực tiếp trên máy Server trung tâm (127.0.0.1 là loopback của Server, Mock Server, thiết bị nội bộ).
     */
    SERVER("Server"),

    /**
     * Phân công cho duy nhất một trạm Agent đo.
     */
    AGENT("Agent"),

    /**
     * Đo kiểm phân tán từ nhiều trạm (Quorum / Multi-Station) gồm LOCAL và/hoặc nhiều Agent.
     */
    MULTI("Đa trạm");

    private final String displayName;

    TargetScope(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static TargetScope fromString(String str) {
        if (str == null || str.trim().isEmpty()) {
            return MULTI;
        }
        for (TargetScope s : values()) {
            if (s.name().equalsIgnoreCase(str.trim()) || s.displayName.equalsIgnoreCase(str.trim())) {
                return s;
            }
        }
        return MULTI;
    }
}

package com.networkalert.model;

/**
 * Loại trạng thái kiểm tra theo mô hình Nagios (State Type)
 */
public enum StateType {
    /**
     * Trạng thái mềm (SOFT):
     * Đang trong các lần thử lại từ 1 đến max_check_attempts - 1.
     * Chưa sinh cảnh báo (Alert) cho người dùng để tránh báo động giả.
     */
    SOFT("SOFT"),

    /**
     * Trạng thái cứng (HARD):
     * Khi đã vượt quá số lần thử lại tối đa (max_check_attempts)
     * hoặc khi mục tiêu đã chính thức khôi phục (Recovery).
     * Chỉ trạng thái HARD mới kích hoạt các cảnh báo (Toast, Email, Telegram, Sound).
     */
    HARD("HARD");

    private final String label;

    StateType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

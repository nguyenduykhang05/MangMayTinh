package com.networkalert.model;

/**
 * Các trạng thái plugin theo chuẩn quy cách Nagios Core (Plugin API Specification, ERRATA §E9.1):
 * <ul>
 *   <li><b>OK (0)</b>: Dịch vụ hoạt động hoàn toàn bình thường. Ánh xạ sang {@link ServerStatus#ONLINE}.</li>
 *   <li><b>WARN (1)</b>: Cảnh báo độ trễ hoặc vượt ngưỡng nhẹ. Ánh xạ sang {@link ServerStatus#WARNING}.</li>
 *   <li><b>CRIT (2)</b>: Sự cố nghiêm trọng (mất kết nối, timeout, rớt gói). Ánh xạ sang {@link ServerStatus#OFFLINE}.</li>
 *   <li><b>UNKNOWN (3)</b>: Lỗi nội bộ checker, sai cấu hình, thiếu quyền. Ánh xạ sang {@link ServerStatus#UNKNOWN}.</li>
 * </ul>
 */
public enum NagiosStatus {
    OK(0, "OK"),
    WARN(1, "WARNING"),
    CRIT(2, "CRITICAL"),
    UNKNOWN(3, "UNKNOWN");

    private final int code;
    private final String description;

    NagiosStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Chuyển đổi sang ServerStatus tương ứng của giao diện NCAS
     */
    public ServerStatus toServerStatus() {
        switch (this) {
            case OK:
                return ServerStatus.ONLINE;
            case WARN:
                return ServerStatus.WARNING;
            case CRIT:
                return ServerStatus.OFFLINE;
            case UNKNOWN:
            default:
                return ServerStatus.UNKNOWN;
        }
    }

    /**
     * Ánh xạ từ ServerStatus sang NagiosStatus tương ứng
     */
    public static NagiosStatus fromServerStatus(ServerStatus status) {
        if (status == null) {
            return UNKNOWN;
        }
        switch (status) {
            case ONLINE:
                return OK;
            case WARNING:
                return WARN;
            case OFFLINE:
                return CRIT;
            default:
                return UNKNOWN;
        }
    }

    /**
     * Khởi tạo từ mã trả về của tiến trình (Exit code 0..3)
     */
    public static NagiosStatus fromCode(int code) {
        switch (code) {
            case 0: return OK;
            case 1: return WARN;
            case 2: return CRIT;
            case 3:
            default: return UNKNOWN;
        }
    }
}

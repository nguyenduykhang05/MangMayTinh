package com.networkalert.protocol;

/**
 * Định danh các loại thông điệp trong giao thức mạng phân tán NCAS-P v1 (2 bytes short)
 */
public enum MessageType {
    // 0x0001 - 0x0004: Khởi tạo kết nối & Nhịp tim Keepalive
    REGISTER_REQ((short) 0x0001, "Agent đăng ký danh tính với Server"),
    REGISTER_RESP((short) 0x0002, "Server phản hồi kết quả đăng ký"),
    HEARTBEAT_PING((short) 0x0003, "Agent gửi nhịp tim định kỳ"),
    HEARTBEAT_PONG((short) 0x0004, "Server phản hồi nhịp tim"),

    // 0x0005 - 0x0008: Điều phối & Báo cáo kết quả đo kiểm mạng
    ASSIGN_TARGETS((short) 0x0005, "Server phân công danh sách mục tiêu cho Agent"),
    REPORT_RESULT((short) 0x0006, "Agent báo cáo kết quả kiểm tra mục tiêu"),
    FORCE_CHECK_REQ((short) 0x0007, "Yêu cầu Agent đo tức thời mục tiêu"),
    FORCE_CHECK_RESP((short) 0x0008, "Agent trả kết quả đo tức thời"),

    // 0x0009 - 0x000C: Console Quản trị & Stream sự kiện Real-time
    CONSOLE_SUBSCRIBE((short) 0x0009, "Console đăng ký nhận stream sự kiện"),
    CONSOLE_EVENT_STREAM((short) 0x000A, "Server phát sóng sự kiện chuyển trạng thái & cảnh báo"),
    CONSOLE_COMMAND((short) 0x000B, "Console gửi lệnh quản trị CLI"),
    CONSOLE_COMMAND_RESP((short) 0x000C, "Server phản hồi kết quả lệnh quản trị"),

    // 0x000D - 0x000E: Xác thực an toàn Challenge-Response (NCAS 2.0 / ERRATA §E2.3 & §E3.1)
    AUTH_CHALLENGE((short) 0x000D, "Server gửi challenge nonce cho Agent"),
    AUTH_RESPONSE((short) 0x000E, "Agent gửi chữ ký phản hồi challenge"),

    // 0x000F - 0x0010: Xác nhận lưu bền & Báo cáo theo lô (NCAS-P v1.1 / ERRATA §E3.1)
    ACK((short) 0x000F, "Server phản hồi xác nhận ACK đã lưu bền cho stream và seq"),
    REPORT_BATCH((short) 0x0010, "Agent gửi lô kết quả đo kiểm kèm khoảng gap"),

    // 0x00FF: Ngắt kết nối an toàn
    DISCONNECT((short) 0x00FF, "Thông báo ngắt kết nối phiên làm việc");

    private final short code;
    private final String description;

    MessageType(short code, String description) {
        this.code = code;
        this.description = description;
    }

    public short getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static MessageType fromCode(short code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException(String.format("Không nhận diện được MessageType mã 0x%04X trong giao thức NCAS-P v1", code));
    }
}

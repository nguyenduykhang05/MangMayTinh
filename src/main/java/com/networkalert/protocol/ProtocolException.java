package com.networkalert.protocol;

/**
 * Ngoại lệ chuyên biệt cho các lỗi xử lý tầng Giao thức mạng NCAS-P v1
 * (Sai lệch Magic Bytes, không khớp CRC32 Checksum, vượt quá kích thước Frame tối đa, v.v.)
 */
public class ProtocolException extends Exception {

    private final String reason;

    public ProtocolException(String message) {
        this(classifyReason(message), message, null);
    }

    public ProtocolException(String message, Throwable cause) {
        this(classifyReason(message), message, cause);
    }

    public ProtocolException(String reason, String message) {
        this(reason, message, null);
    }

    public ProtocolException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = (reason != null && !reason.trim().isEmpty()) ? reason.trim().toLowerCase() : "unknown";
    }

    public String getReason() {
        return reason;
    }

    private static String classifyReason(String message) {
        if (message == null) return "unknown";
        String lower = message.toLowerCase();
        if (lower.contains("magic")) return ProtocolMetrics.REASON_MAGIC;
        if (lower.contains("version") || lower.contains("phiên bản")) return ProtocolMetrics.REASON_VERSION;
        if (lower.contains("crc") || lower.contains("toàn vẹn")) return ProtocolMetrics.REASON_CRC;
        if (lower.contains("payload") || lower.contains("kích thước") || lower.contains("oversize") || lower.contains("size")) return ProtocolMetrics.REASON_OVERSIZE;
        if (lower.contains("slowloris") || lower.contains("timeout") || lower.contains("dở")) return ProtocolMetrics.REASON_SLOWLORIS;
        if (lower.contains("idle") || lower.contains("rảnh")) return ProtocolMetrics.REASON_IDLE;
        return "protocol_error";
    }
}

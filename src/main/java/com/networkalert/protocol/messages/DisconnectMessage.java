package com.networkalert.protocol.messages;

/**
 * Thông điệp ngắt kết nối phiên làm việc an toàn (MSG_DISCONNECT - 0x00FF)
 */
public class DisconnectMessage {
    private String reason; // SHUTDOWN, TIMEOUT, KICKED, AUTH_FAILED
    private String details;
    private long timestamp;

    public DisconnectMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public DisconnectMessage(String reason, String details) {
        this.reason = reason;
        this.details = details;
        this.timestamp = System.currentTimeMillis();
    }

    public static DisconnectMessage of(String reason, String details) {
        return new DisconnectMessage(reason, details);
    }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return String.format("DisconnectMessage[reason=%s, details='%s']", reason, details);
    }
}

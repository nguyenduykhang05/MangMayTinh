package com.networkalert.protocol.messages;

/**
 * Thông điệp phản hồi nhịp tim từ Server về Agent (MSG_HEARTBEAT_PONG - 0x0004)
 */
public class HeartbeatPong {
    private long timestamp;
    private String serverStatus;

    public HeartbeatPong() {}

    public HeartbeatPong(long timestamp, String serverStatus) {
        this.timestamp = timestamp;
        this.serverStatus = serverStatus;
    }

    public static HeartbeatPong ack() {
        return new HeartbeatPong(System.currentTimeMillis(), "OK");
    }

    public static HeartbeatPong ok() {
        return ack();
    }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getServerStatus() { return serverStatus; }
    public void setServerStatus(String serverStatus) { this.serverStatus = serverStatus; }

    @Override
    public String toString() {
        return String.format("HeartbeatPong[time=%d, status=%s]", timestamp, serverStatus);
    }
}

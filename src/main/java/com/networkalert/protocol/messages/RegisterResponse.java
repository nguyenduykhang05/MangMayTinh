package com.networkalert.protocol.messages;

/**
 * Thông điệp phản hồi kết quả đăng ký từ Server cho Agent (MSG_REGISTER_RESP - 0x0002)
 */
public class RegisterResponse {
    private String status; // ACCEPTED hoặc REJECTED
    private String assignedSessionId;
    private int heartbeatIntervalSeconds;
    private long serverTime;
    private String message;
    private String dedicatedSecret; // Secret riêng cấp cho Agent khi enrollment
    private String protoVersion; // Phiên bản giao thức đàm phán (ERRATA §E3.1)

    public RegisterResponse() {}

    public RegisterResponse(String status, String assignedSessionId, int heartbeatIntervalSeconds, long serverTime, String message) {
        this(status, assignedSessionId, heartbeatIntervalSeconds, serverTime, message, null);
    }

    public RegisterResponse(String status, String assignedSessionId, int heartbeatIntervalSeconds, long serverTime, String message, String dedicatedSecret) {
        this.status = status;
        this.assignedSessionId = assignedSessionId;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.serverTime = serverTime;
        this.message = message;
        this.dedicatedSecret = dedicatedSecret;
    }

    public static RegisterResponse accepted(String sessionId, int heartbeatIntervalSec, String msg) {
        return new RegisterResponse("ACCEPTED", sessionId, heartbeatIntervalSec, System.currentTimeMillis(), msg, null);
    }

    public static RegisterResponse acceptedWithSecret(String sessionId, int heartbeatIntervalSec, String msg, String dedicatedSecret) {
        return new RegisterResponse("ACCEPTED", sessionId, heartbeatIntervalSec, System.currentTimeMillis(), msg, dedicatedSecret);
    }

    public static RegisterResponse rejected(String msg) {
        return new RegisterResponse("REJECTED", null, 0, System.currentTimeMillis(), msg, null);
    }

    public static RegisterResponse accepted(String sessionId, int heartbeatIntervalSec, String msg, String protoVersion) {
        RegisterResponse r = accepted(sessionId, heartbeatIntervalSec, msg);
        r.setProtoVersion(protoVersion);
        return r;
    }

    public boolean isAccepted() {
        return "ACCEPTED".equalsIgnoreCase(status);
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAssignedSessionId() { return assignedSessionId; }
    public void setAssignedSessionId(String assignedSessionId) { this.assignedSessionId = assignedSessionId; }

    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) { this.heartbeatIntervalSeconds = heartbeatIntervalSeconds; }

    public long getServerTime() { return serverTime; }
    public void setServerTime(long serverTime) { this.serverTime = serverTime; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDedicatedSecret() { return dedicatedSecret; }
    public void setDedicatedSecret(String dedicatedSecret) { this.dedicatedSecret = dedicatedSecret; }

    public String getProtoVersion() { return protoVersion; }
    public void setProtoVersion(String protoVersion) { this.protoVersion = protoVersion; }

    @Override
    public String toString() {
        return String.format("RegisterResponse[status=%s, sessionId=%s, hbInterval=%ds]", status, assignedSessionId, heartbeatIntervalSeconds);
    }
}

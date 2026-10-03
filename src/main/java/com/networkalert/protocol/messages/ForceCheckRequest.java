package com.networkalert.protocol.messages;

/**
 * Thông điệp yêu cầu đo tức thời từ Server gửi xuống Agent (MSG_FORCE_CHECK_REQ - 0x0007)
 */
public class ForceCheckRequest {
    private int targetId;
    private TargetDto target;
    private long requestedAt;

    public ForceCheckRequest() {
        this.requestedAt = System.currentTimeMillis();
    }

    public ForceCheckRequest(int targetId, TargetDto target) {
        this.targetId = targetId;
        this.target = target;
        this.requestedAt = System.currentTimeMillis();
    }

    public int getTargetId() { return targetId; }
    public void setTargetId(int targetId) { this.targetId = targetId; }

    public TargetDto getTarget() { return target; }
    public void setTarget(TargetDto target) { this.target = target; }

    public long getRequestedAt() { return requestedAt; }
    public void setRequestedAt(long requestedAt) { this.requestedAt = requestedAt; }

    @Override
    public String toString() {
        return String.format("ForceCheckRequest[targetId=%d, requestedAt=%d]", targetId, requestedAt);
    }
}

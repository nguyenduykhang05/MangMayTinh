package com.networkalert.protocol.messages;

/**
 * Phản hồi kết quả đo tức thời từ Agent trả về cho Server (MSG_FORCE_CHECK_RESP - 0x0008)
 */
public class ForceCheckResponse {
    private int targetId;
    private ReportResultMessage result;

    public ForceCheckResponse() {}

    public ForceCheckResponse(int targetId, ReportResultMessage result) {
        this.targetId = targetId;
        this.result = result;
    }

    public int getTargetId() { return targetId; }
    public void setTargetId(int targetId) { this.targetId = targetId; }

    public ReportResultMessage getResult() { return result; }
    public void setResult(ReportResultMessage result) { this.result = result; }

    @Override
    public String toString() {
        return String.format("ForceCheckResponse[targetId=%d, result=%s]", targetId, result);
    }
}

package com.networkalert.protocol.messages;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerStatus;

/**
 * Thông điệp báo cáo kết quả kiểm tra mục tiêu từ Agent gửi về Server (MSG_REPORT_RESULT - 0x0006)
 */
public class ReportResultMessage {
    private String agentId;
    private int targetId;
    private long timestamp;
    private boolean success;
    private long latencyMs;
    private int responseCode;
    private MetricBreakdownDto metrics;
    private String message;

    public ReportResultMessage() {}

    public ReportResultMessage(String agentId, int targetId, long timestamp, boolean success,
                               long latencyMs, int responseCode, MetricBreakdownDto metrics, String message) {
        this.agentId = agentId;
        this.targetId = targetId;
        this.timestamp = timestamp;
        this.success = success;
        this.latencyMs = latencyMs;
        this.responseCode = responseCode;
        this.metrics = metrics;
        this.message = message;
    }

    public static ReportResultMessage fromCheckResult(String agentId, int targetId, CheckResult result) {
        return new ReportResultMessage(
                agentId,
                targetId,
                System.currentTimeMillis(),
                result.isSuccess(),
                result.getLatencyMs(),
                result.getResponseCode(),
                null,
                result.getMessage()
        );
    }

    public ReportResultMessage(int targetId, boolean success, long latencyMs, int responseCode, String message, long checkedAtMs) {
        this(null, targetId, checkedAtMs, success, latencyMs, responseCode, null, message);
        this.checkedAtMs = checkedAtMs;
    }

    public CheckResult toCheckResult() {
        ServerStatus st = success ? ServerStatus.ONLINE : ServerStatus.OFFLINE;
        return new CheckResult(success, latencyMs, responseCode, message, st);
    }

    // Các trường mở rộng NCAS-P v1.1 (ERRATA §E3.6)
    private Long streamId;
    private Long seq;
    private Long checkedAtMs;
    private String errorCode;
    private java.util.Map<String, Object> perf;
    private String method;
    private Boolean backfilled;
    private String diag;

    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }

    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }

    public Long getCheckedAtMs() { return checkedAtMs; }
    public void setCheckedAtMs(Long checkedAtMs) { this.checkedAtMs = checkedAtMs; }

    public long getCheckedAt() { return checkedAtMs != null ? checkedAtMs : timestamp; }
    public void setCheckedAt(long checkedAt) {
        this.checkedAtMs = checkedAt;
        this.timestamp = checkedAt;
    }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public java.util.Map<String, Object> getPerf() { return perf; }
    public void setPerf(java.util.Map<String, Object> perf) { this.perf = perf; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Boolean getBackfilled() { return backfilled; }
    public void setBackfilled(Boolean backfilled) { this.backfilled = backfilled; }

    public String getDiag() { return diag; }
    public void setDiag(String diag) { this.diag = diag; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public int getTargetId() { return targetId; }
    public void setTargetId(int targetId) { this.targetId = targetId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public int getResponseCode() { return responseCode; }
    public void setResponseCode(int responseCode) { this.responseCode = responseCode; }

    public MetricBreakdownDto getMetrics() { return metrics; }
    public void setMetrics(MetricBreakdownDto metrics) { this.metrics = metrics; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public String toString() {
        return String.format("ReportResult[agent=%s, target=#%d, success=%b, latency=%dms, msg='%s']",
                agentId, targetId, success, latencyMs, message);
    }
}

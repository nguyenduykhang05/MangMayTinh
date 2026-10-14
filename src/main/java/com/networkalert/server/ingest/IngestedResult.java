package com.networkalert.server.ingest;

import com.networkalert.protocol.messages.ReportResultMessage;

/**
 * Đại diện cho một kết quả đo kiểm đã qua các bước thẩm định và chuẩn hóa của đường ống Ingestor (ERRATA §E5.1)
 */
public class IngestedResult {
    private final String agentId;
    private final long streamId;
    private final long seq;
    private final int targetId;
    private final long checkedAtServerMs;
    private final long serverRecvMs;
    private final boolean success;
    private final long latencyMs;
    private final int responseCode;
    private final String message;
    private final String method;
    private final boolean backfilled;
    private final ReportResultMessage originalReport;

    public IngestedResult(String agentId, long streamId, long seq, int targetId,
                          long checkedAtServerMs, long serverRecvMs, boolean success,
                          long latencyMs, int responseCode, String message, String method,
                          boolean backfilled, ReportResultMessage originalReport) {
        this.agentId = agentId;
        this.streamId = streamId;
        this.seq = seq;
        this.targetId = targetId;
        this.checkedAtServerMs = checkedAtServerMs;
        this.serverRecvMs = serverRecvMs;
        this.success = success;
        this.latencyMs = latencyMs;
        this.responseCode = responseCode;
        this.message = message;
        this.method = method;
        this.backfilled = backfilled;
        this.originalReport = originalReport;
    }

    public String getAgentId() { return agentId; }
    public long getStreamId() { return streamId; }
    public long getSeq() { return seq; }
    public int getTargetId() { return targetId; }
    public long getCheckedAtServerMs() { return checkedAtServerMs; }
    public long getServerRecvMs() { return serverRecvMs; }
    public boolean isSuccess() { return success; }
    public long getLatencyMs() { return latencyMs; }
    public int getResponseCode() { return responseCode; }
    public String getMessage() { return message; }
    public String getMethod() { return method; }
    public boolean isBackfilled() { return backfilled; }
    public ReportResultMessage getOriginalReport() { return originalReport; }
}

package com.networkalert.agent.spool;

import com.networkalert.protocol.Frame;
import com.networkalert.protocol.messages.ReportResultMessage;

/**
 * Đại diện cho một bản ghi kết quả đo kiểm trong bộ đệm đĩa Spool (ERRATA §E4.1)
 */
public class SpoolRecord {
    private final Frame frame;
    private final ReportResultMessage report;
    private final long streamId;
    private final long seq;
    private final String segmentFileName;
    private final long fileOffset;
    private final int recordLength;
    private volatile boolean acknowledged = false;

    public SpoolRecord(Frame frame, ReportResultMessage report, String segmentFileName, long fileOffset, int recordLength) {
        this.frame = frame;
        this.report = report;
        this.streamId = (report != null && report.getStreamId() != null) ? report.getStreamId() : 0L;
        this.seq = (report != null && report.getSeq() != null) ? report.getSeq() : 0L;
        this.segmentFileName = segmentFileName;
        this.fileOffset = fileOffset;
        this.recordLength = recordLength;
    }

    public Frame getFrame() {
        return frame;
    }

    public ReportResultMessage getReport() {
        return report;
    }

    public long getStreamId() {
        return streamId;
    }

    public long getSeq() {
        return seq;
    }

    public String getSegmentFileName() {
        return segmentFileName;
    }

    public long getFileOffset() {
        return fileOffset;
    }

    public int getRecordLength() {
        return recordLength;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }
}

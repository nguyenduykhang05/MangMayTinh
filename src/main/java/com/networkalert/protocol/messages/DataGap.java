package com.networkalert.protocol.messages;

import java.util.Objects;

/**
 * Khai báo khoảng seq dữ liệu bị bỏ theo NCAS-P v1.1 (ERRATA §E3.4).
 *
 * <p>Bất biến I4: Agent PHẢI khai báo mọi khoảng seq nó đã bỏ và sẽ không bao giờ gửi.
 * Lý do: {@code "dropped"} (bỏ do spool đầy) hoặc {@code "sampled"} (lược bớt bản ghi OK).
 */
public class DataGap {
    public static final String REASON_DROPPED = "dropped";
    public static final String REASON_SAMPLED = "sampled";

    private long streamId;
    private long fromSeq;
    private long toSeq;
    private String reason;

    public DataGap() {}

    public DataGap(long streamId, long fromSeq, long toSeq, String reason) {
        this.streamId = streamId;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
        this.reason = reason;
    }

    public long getStreamId() {
        return streamId;
    }

    public void setStreamId(long streamId) {
        this.streamId = streamId;
    }

    public long getFromSeq() {
        return fromSeq;
    }

    public void setFromSeq(long fromSeq) {
        this.fromSeq = fromSeq;
    }

    public long getToSeq() {
        return toSeq;
    }

    public void setToSeq(long toSeq) {
        this.toSeq = toSeq;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataGap)) return false;
        DataGap dataGap = (DataGap) o;
        return streamId == dataGap.streamId && fromSeq == dataGap.fromSeq &&
                toSeq == dataGap.toSeq && Objects.equals(reason, dataGap.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, fromSeq, toSeq, reason);
    }

    @Override
    public String toString() {
        return "DataGap{" +
                "streamId=" + streamId +
                ", fromSeq=" + fromSeq +
                ", toSeq=" + toSeq +
                ", reason='" + reason + '\'' +
                '}';
    }
}

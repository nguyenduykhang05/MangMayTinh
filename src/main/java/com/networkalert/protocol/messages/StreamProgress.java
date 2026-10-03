package com.networkalert.protocol.messages;

import java.util.Objects;

/**
 * Báo cáo tiến trình stream trong HEARTBEAT theo NCAS-P v1.1 (ERRATA §E3.4 & §E3.6).
 *
 * <p>Bất biến I6: {@code lowSeq} là seq cũ nhất Agent còn giữ trong spool;
 * {@code lastSeq} là seq mới nhất Agent đã sinh.
 */
public class StreamProgress {
    private long streamId;
    private long lowSeq;
    private long lastSeq;

    public StreamProgress() {}

    public StreamProgress(long streamId, long lowSeq, long lastSeq) {
        this.streamId = streamId;
        this.lowSeq = lowSeq;
        this.lastSeq = lastSeq;
    }

    public long getStreamId() {
        return streamId;
    }

    public void setStreamId(long streamId) {
        this.streamId = streamId;
    }

    public long getLowSeq() {
        return lowSeq;
    }

    public void setLowSeq(long lowSeq) {
        this.lowSeq = lowSeq;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StreamProgress)) return false;
        StreamProgress that = (StreamProgress) o;
        return streamId == that.streamId && lowSeq == that.lowSeq && lastSeq == that.lastSeq;
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, lowSeq, lastSeq);
    }

    @Override
    public String toString() {
        return "StreamProgress{" +
                "streamId=" + streamId +
                ", lowSeq=" + lowSeq +
                ", lastSeq=" + lastSeq +
                '}';
    }
}

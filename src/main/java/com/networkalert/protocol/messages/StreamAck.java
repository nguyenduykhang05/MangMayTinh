package com.networkalert.protocol.messages;

import java.util.Objects;

/**
 * Cặp mốc xác nhận lưu bền (streamId, ackSeq) theo NCAS-P v1.1 (ERRATA §E3.3).
 *
 * <p>Bất biến I1: {@code ackSeq} là mốc liên tục cao nhất Server đã LƯU BỀN (commit CSDL),
 * tính cả các khoảng gap đã khai báo.
 */
public class StreamAck {
    private long streamId;
    private long ackSeq;

    public StreamAck() {}

    public StreamAck(long streamId, long ackSeq) {
        this.streamId = streamId;
        this.ackSeq = ackSeq;
    }

    public long getStreamId() {
        return streamId;
    }

    public void setStreamId(long streamId) {
        this.streamId = streamId;
    }

    public long getAckSeq() {
        return ackSeq;
    }

    public void setAckSeq(long ackSeq) {
        this.ackSeq = ackSeq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StreamAck)) return false;
        StreamAck streamAck = (StreamAck) o;
        return streamId == streamAck.streamId && ackSeq == streamAck.ackSeq;
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, ackSeq);
    }

    @Override
    public String toString() {
        return "StreamAck{" +
                "streamId=" + streamId +
                ", ackSeq=" + ackSeq +
                '}';
    }
}

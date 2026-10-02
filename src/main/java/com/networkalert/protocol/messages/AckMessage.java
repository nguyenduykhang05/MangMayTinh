package com.networkalert.protocol.messages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Thông điệp xác nhận lưu bền ACK (0x000F) theo NCAS-P v1.1 (ERRATA §E3.3 & §E3.6).
 *
 * <p>Bất biến I1: ACK nghĩa là "đã lưu bền vào CSDL", không phải chỉ là "đã nhận".
 * Mang 2 mốc thời gian máy chủ: {@code serverRecvMs} (t1) và {@code serverSendMs} (t2) phục vụ
 * đồng bộ đồng hồ kiểu NTP 4 mốc thời gian.
 */
public class AckMessage {
    private List<StreamAck> streams = new ArrayList<>();
    private Long hbSeq;
    private Long serverRecvMs;
    private Long serverSendMs;

    public AckMessage() {}

    public AckMessage(List<StreamAck> streams, Long hbSeq, Long serverRecvMs, Long serverSendMs) {
        if (streams != null) {
            this.streams = new ArrayList<>(streams);
        }
        this.hbSeq = hbSeq;
        this.serverRecvMs = serverRecvMs;
        this.serverSendMs = serverSendMs;
    }

    public static AckMessage ofStream(long streamId, long ackSeq, Long serverRecvMs, Long serverSendMs) {
        AckMessage ack = new AckMessage();
        ack.addStreamAck(new StreamAck(streamId, ackSeq));
        ack.setServerRecvMs(serverRecvMs);
        ack.setServerSendMs(serverSendMs);
        return ack;
    }

    public List<StreamAck> getStreams() {
        return Collections.unmodifiableList(streams);
    }

    public void setStreams(List<StreamAck> streams) {
        this.streams = streams != null ? new ArrayList<>(streams) : new ArrayList<>();
    }

    public void addStreamAck(StreamAck ack) {
        if (ack != null) {
            this.streams.add(ack);
        }
    }

    public Long getWatermarkForStream(long streamId) {
        if (streams != null) {
            for (StreamAck sa : streams) {
                if (sa.getStreamId() == streamId) {
                    return sa.getAckSeq();
                }
            }
        }
        return null;
    }

    public Long getHbSeq() {
        return hbSeq;
    }

    public void setHbSeq(Long hbSeq) {
        this.hbSeq = hbSeq;
    }

    public Long getServerRecvMs() {
        return serverRecvMs;
    }

    public void setServerRecvMs(Long serverRecvMs) {
        this.serverRecvMs = serverRecvMs;
    }

    public Long getServerSendMs() {
        return serverSendMs;
    }

    public void setServerSendMs(Long serverSendMs) {
        this.serverSendMs = serverSendMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AckMessage)) return false;
        AckMessage that = (AckMessage) o;
        return Objects.equals(streams, that.streams) &&
                Objects.equals(hbSeq, that.hbSeq) &&
                Objects.equals(serverRecvMs, that.serverRecvMs) &&
                Objects.equals(serverSendMs, that.serverSendMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streams, hbSeq, serverRecvMs, serverSendMs);
    }

    @Override
    public String toString() {
        return "AckMessage{" +
                "streams=" + streams +
                ", hbSeq=" + hbSeq +
                ", serverRecvMs=" + serverRecvMs +
                ", serverSendMs=" + serverSendMs +
                '}';
    }
}

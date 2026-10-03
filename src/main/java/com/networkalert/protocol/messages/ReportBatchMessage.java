package com.networkalert.protocol.messages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Thông điệp gửi kết quả theo lô REPORT_BATCH (0x0010) theo NCAS-P v1.1 (ERRATA §E3.5 & §E3.6).
 *
 * <p>Sử dụng cho làn xả nền (Backlog queue): gửi theo lô tối đa 100 bản ghi và 256 KiB,
 * mang khoảng seq {@code [firstSeq, lastSeq]} và các khoảng hụt {@code gaps[]}.
 */
public class ReportBatchMessage {
    private long streamId;
    private long firstSeq;
    private long lastSeq;
    private List<ReportResultMessage> items = new ArrayList<>();
    private List<DataGap> gaps = new ArrayList<>();

    public ReportBatchMessage() {}

    public ReportBatchMessage(long streamId, long firstSeq, long lastSeq,
                              List<ReportResultMessage> items, List<DataGap> gaps) {
        this.streamId = streamId;
        this.firstSeq = firstSeq;
        this.lastSeq = lastSeq;
        if (items != null) {
            this.items = new ArrayList<>(items);
        }
        if (gaps != null) {
            this.gaps = new ArrayList<>(gaps);
        }
    }

    public ReportBatchMessage(long streamId, List<ReportResultMessage> items, List<DataGap> gaps) {
        this(streamId,
                (items != null && !items.isEmpty() && items.get(0).getSeq() != null) ? items.get(0).getSeq() : 0L,
                (items != null && !items.isEmpty() && items.get(items.size() - 1).getSeq() != null) ? items.get(items.size() - 1).getSeq() : 0L,
                items, gaps);
    }

    public long getStreamId() {
        return streamId;
    }

    public void setStreamId(long streamId) {
        this.streamId = streamId;
    }

    public long getFirstSeq() {
        return firstSeq;
    }

    public void setFirstSeq(long firstSeq) {
        this.firstSeq = firstSeq;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public List<ReportResultMessage> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void setItems(List<ReportResultMessage> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public void addItem(ReportResultMessage item) {
        if (item != null) {
            this.items.add(item);
        }
    }

    public List<DataGap> getGaps() {
        return Collections.unmodifiableList(gaps);
    }

    public void setGaps(List<DataGap> gaps) {
        this.gaps = gaps != null ? new ArrayList<>(gaps) : new ArrayList<>();
    }

    public void addGap(DataGap gap) {
        if (gap != null) {
            this.gaps.add(gap);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReportBatchMessage)) return false;
        ReportBatchMessage that = (ReportBatchMessage) o;
        return streamId == that.streamId &&
                firstSeq == that.firstSeq &&
                lastSeq == that.lastSeq &&
                Objects.equals(items, that.items) &&
                Objects.equals(gaps, that.gaps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, firstSeq, lastSeq, items, gaps);
    }

    @Override
    public String toString() {
        return "ReportBatchMessage{" +
                "streamId=" + streamId +
                ", firstSeq=" + firstSeq +
                ", lastSeq=" + lastSeq +
                ", itemsCount=" + items.size() +
                ", gapsCount=" + gaps.size() +
                '}';
    }
}

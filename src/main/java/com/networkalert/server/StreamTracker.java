package com.networkalert.server;

import java.util.BitSet;

/**
 * Bộ theo dõi tiến trình số thứ tự (Sequence Number) và Watermark cho từng luồng (Stream)
 * của Agent trên Server theo NCAS 2.0 (ERRATA §E3.3 & §E3.4).
 *
 * <p>Nguyên lý hoạt động:
 * <ul>
 *   <li>{@code watermark}: Mốc số thứ tự liên tục cao nhất đã ghi nhận/lưu bền. Bắt đầu từ 0.</li>
 *   <li>{@code window}: Cửa sổ trượt {@link BitSet} chứa các seq đến lệch thứ tự (tối đa 65.536 seq).</li>
 *   <li>Bản ghi có {@code seq <= watermark}: Được nhận diện là trùng lặp (Duplicate).</li>
 *   <li>Khi nhận khoảng Gap: Watermark được đẩy vượt qua khoảng Gap mà không làm kẹt luồng ACK.</li>
 * </ul>
 */
public class StreamTracker {

    public static final int MAX_WINDOW_SIZE = 65536;

    public enum SeqStatus {
        ACCEPTED_IN_ORDER,
        ACCEPTED_OUT_OF_ORDER,
        DUPLICATE,
        OUT_OF_WINDOW
    }

    private final long streamId;
    private long watermark = 0;
    private final BitSet outOfOrderBits = new BitSet(MAX_WINDOW_SIZE);

    public StreamTracker(long streamId) {
        this.streamId = streamId;
    }

    public StreamTracker(long streamId, long initialWatermark) {
        this.streamId = streamId;
        this.watermark = Math.max(0, initialWatermark);
    }

    public long getStreamId() {
        return streamId;
    }

    public synchronized long getWatermark() {
        return watermark;
    }

    /**
     * Tiếp nhận một số thứ tự seq mới trong stream
     */
    public synchronized SeqStatus processSeq(long seq) {
        if (seq <= watermark) {
            return SeqStatus.DUPLICATE;
        }

        long diff = seq - watermark;
        if (diff > MAX_WINDOW_SIZE) {
            return SeqStatus.OUT_OF_WINDOW;
        }

        if (seq == watermark + 1) {
            watermark++;
            shiftBitSet(1);
            while (outOfOrderBits.get(0)) {
                watermark++;
                shiftBitSet(1);
            }
            return SeqStatus.ACCEPTED_IN_ORDER;
        }

        // Đến lệch thứ tự (Out-of-order)
        int bitIndex = (int) (diff - 1);
        if (outOfOrderBits.get(bitIndex)) {
            return SeqStatus.DUPLICATE;
        }
        outOfOrderBits.set(bitIndex);
        return SeqStatus.ACCEPTED_OUT_OF_ORDER;
    }

    /**
     * Áp dụng khoảng Gap đã khai báo để kéo tiến Watermark (ERRATA §E3.4)
     */
    public synchronized long applyGap(long fromSeq, long toSeq) {
        if (toSeq <= watermark) {
            return 0; // Gap nằm hoàn toàn trước watermark
        }

        long start = Math.max(fromSeq, watermark + 1);
        if (start > toSeq) {
            return 0;
        }

        long count = toSeq - start + 1;
        int shift = (int) (toSeq - watermark);
        watermark = toSeq;
        shiftBitSet(shift);
        while (outOfOrderBits.get(0)) {
            watermark++;
            shiftBitSet(1);
        }
        return count;
    }

    private void shiftBitSet(int shift) {
        if (shift <= 0) return;
        if (shift >= MAX_WINDOW_SIZE) {
            outOfOrderBits.clear();
            return;
        }
        BitSet shifted = new BitSet(MAX_WINDOW_SIZE);
        for (int i = shift; i < MAX_WINDOW_SIZE; i++) {
            if (outOfOrderBits.get(i)) {
                shifted.set(i - shift);
            }
        }
        outOfOrderBits.clear();
        outOfOrderBits.or(shifted);
    }
}

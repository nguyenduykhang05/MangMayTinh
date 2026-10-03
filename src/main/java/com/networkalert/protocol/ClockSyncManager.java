package com.networkalert.protocol;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Quản lý đồng bộ đồng hồ phân tán theo giải thuật NTP 4 mốc thời gian (ERRATA §E3.7).
 *
 * <p>Quy trình trao đổi:
 * <ul>
 *   <li>t0: Agent gửi HEARTBEAT mang {@code clockMs}.</li>
 *   <li>t1: Server nhận HEARTBEAT (ghi nhận {@code serverRecvMs}).</li>
 *   <li>t2: Server gửi ACK phản hồi (ghi nhận {@code serverSendMs}).</li>
 *   <li>t3: Agent nhận được ACK từ Server.</li>
 * </ul>
 *
 * <p>Công thức tính toán:
 * <ul>
 *   <li>{@code offset = ((t1 - t0) + (t2 - t3)) / 2} (dương nghĩa là Server nhanh hơn Agent).</li>
 *   <li>{@code delay = (t3 - t0) - (t2 - t1)}.</li>
 * </ul>
 *
 * Giữ 8 mẫu gần nhất, chọn mẫu có {@code delay} nhỏ nhất làm giá trị đại diện.
 * Cờ {@code clock_skew} được kích hoạt nếu {@code |offset| > 5000 ms}.
 */
public class ClockSyncManager {

    public static final int MAX_SAMPLES = 8;
    public static final long CLOCK_SKEW_THRESHOLD_MS = 5000L;

    public static class NtpSample {
        public final long t0;
        public final long t1;
        public final long t2;
        public final long t3;
        public final long offset;
        public final long delay;

        public NtpSample(long t0, long t1, long t2, long t3) {
            this.t0 = t0;
            this.t1 = t1;
            this.t2 = t2;
            this.t3 = t3;
            this.offset = ((t1 - t0) + (t2 - t3)) / 2;
            this.delay = Math.max(0, (t3 - t0) - (t2 - t1));
        }
    }

    private final Deque<NtpSample> samples = new ArrayDeque<>();
    private Long bestOffset = null;
    private Long bestDelay = null;

    public synchronized void recordSample(long t0, long t1, long t2, long t3) {
        NtpSample sample = new NtpSample(t0, t1, t2, t3);
        if (samples.size() >= MAX_SAMPLES) {
            samples.pollFirst();
        }
        samples.addLast(sample);

        // Chọn mẫu có round-trip delay nhỏ nhất
        NtpSample best = null;
        for (NtpSample s : samples) {
            if (best == null || s.delay < best.delay) {
                best = s;
            }
        }
        if (best != null) {
            this.bestOffset = best.offset;
            this.bestDelay = best.delay;
        }
    }

    public synchronized Long getClockOffsetMs() {
        return bestOffset;
    }

    public synchronized Long getClockDelayMs() {
        return bestDelay;
    }

    public synchronized boolean isClockSkew() {
        return bestOffset != null && Math.abs(bestOffset) > CLOCK_SKEW_THRESHOLD_MS;
    }

    /**
     * Chuẩn hóa thời điểm đo của Agent theo giờ Server:
     * {@code checkedAtServer = min(checkedAtMs + offset, serverRecvMs)} (ERRATA §E3.7)
     */
    public synchronized long adjustTimestamp(long agentCheckedAtMs, long serverRecvMs) {
        if (bestOffset == null) {
            return Math.min(agentCheckedAtMs, serverRecvMs);
        }
        long adjusted = agentCheckedAtMs + bestOffset;
        return Math.min(adjusted, serverRecvMs);
    }

    public synchronized void reset() {
        samples.clear();
        bestOffset = null;
        bestDelay = null;
    }
}

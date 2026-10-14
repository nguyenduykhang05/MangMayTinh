package com.networkalert.agent.backoff;

import java.util.Random;

/**
 * Thuật toán tái kết nối Full Jitter Exponential Backoff theo ERRATA §E4.4:
 * sleep = random(0, min(60 s, 2^n s))
 * n reset về 0 sau khi kết nối duy trì ổn định >= 60 giây.
 */
public class FullJitterBackoff {
    public static final long DEFAULT_INITIAL_BASE_MS = 1000L;
    public static final long DEFAULT_MAX_DELAY_MS = 60_000L;
    public static final long CONFIG_ERROR_BACKOFF_MS = 60_000L;
    public static final long STABLE_CONNECTION_THRESHOLD_MS = 60_000L;

    private final long initialBaseMs;
    private final long maxDelayMs;
    private final Random random;

    private int attempt = 0;
    private long connectionEstablishedMs = -1L;

    public FullJitterBackoff() {
        this(DEFAULT_INITIAL_BASE_MS, DEFAULT_MAX_DELAY_MS, new Random());
    }

    public FullJitterBackoff(long initialBaseMs, long maxDelayMs, Random random) {
        this.initialBaseMs = Math.max(100L, initialBaseMs);
        this.maxDelayMs = Math.max(this.initialBaseMs, maxDelayMs);
        this.random = random != null ? random : new Random();
    }

    /**
     * Tính toán thời gian ngủ tiếp theo cho lần thử lại thứ n:
     * ceiling = min(maxDelayMs, initialBaseMs * 2^attempt)
     * delay = random(0, ceiling)
     */
    public synchronized long nextDelayMs() {
        long factor = 1L << Math.min(attempt, 30);
        long exp = initialBaseMs * factor;
        // Chống tràn số hoặc vượt ngưỡng tối đa
        if (exp < 0 || exp > maxDelayMs) {
            exp = maxDelayMs;
        }
        long ceiling = Math.min(maxDelayMs, exp);
        long delay = (long) (random.nextDouble() * ceiling);
        attempt++;
        return delay;
    }

    /**
     * Thời gian backoff cố định cho lỗi cấu hình (AUTH_FAIL, sai fingerprint, sai TLS)
     */
    public long getConfigurationErrorBackoffMs() {
        return Math.max(CONFIG_ERROR_BACKOFF_MS, maxDelayMs);
    }

    /**
     * Ghi nhận thời điểm kết nối thành công
     */
    public synchronized void onConnected(long nowMs) {
        this.connectionEstablishedMs = nowMs;
    }

    /**
     * Ghi nhận thời điểm mất kết nối; nếu kết nối đã ổn định >= 60s thì reset attempt về 0
     */
    public synchronized void onDisconnected(long nowMs) {
        if (connectionEstablishedMs > 0 && (nowMs - connectionEstablishedMs >= STABLE_CONNECTION_THRESHOLD_MS)) {
            reset();
        }
        connectionEstablishedMs = -1L;
    }

    public synchronized void reset() {
        this.attempt = 0;
    }

    public synchronized int getAttempt() {
        return attempt;
    }
}

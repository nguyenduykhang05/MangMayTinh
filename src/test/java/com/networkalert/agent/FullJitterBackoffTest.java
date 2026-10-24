package com.networkalert.agent.backoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kiểm thử FullJitterBackoff (ERRATA §E4.4)")
class FullJitterBackoffTest {

    @Test
    @DisplayName("Độ trễ jitter nằm trong [0, min(60s, 2^n s)] và tăng dần trần mũ")
    void testJitterDelayRangeAndExponentialCeiling() {
        Random fixedRandom = new Random(42);
        FullJitterBackoff backoff = new FullJitterBackoff(1000L, 60_000L, fixedRandom);

        for (int n = 0; n < 10; n++) {
            long ceiling = Math.min(60_000L, 1000L * (1L << n));
            long delay = backoff.nextDelayMs();
            assertTrue(delay >= 0 && delay <= ceiling,
                    String.format("Lần %d: delay %d phải nằm trong [0, %d]", n, delay, ceiling));
        }

        assertEquals(10, backoff.getAttempt());
    }

    @Test
    @DisplayName("Reset số lần thử khi kết nối ổn định >= 60 giây")
    void testResetAttemptAfterStableConnection() {
        FullJitterBackoff backoff = new FullJitterBackoff();
        backoff.nextDelayMs();
        backoff.nextDelayMs();
        assertEquals(2, backoff.getAttempt());

        // Kết nối ổn định 70s (> 60s)
        long t0 = 100_000L;
        backoff.onConnected(t0);
        backoff.onDisconnected(t0 + 70_000L);

        assertEquals(0, backoff.getAttempt(), "Attempt phải reset về 0 sau khi duy trì kết nối >= 60 giây");
    }

    @Test
    @DisplayName("Không reset số lần thử nếu kết nối rớt trước 60 giây")
    void testDoNotResetIfDisconnectedBefore60s() {
        FullJitterBackoff backoff = new FullJitterBackoff();
        backoff.nextDelayMs();
        backoff.nextDelayMs();
        assertEquals(2, backoff.getAttempt());

        // Mất kết nối chỉ sau 15 giây
        long t0 = 100_000L;
        backoff.onConnected(t0);
        backoff.onDisconnected(t0 + 15_000L);

        assertEquals(2, backoff.getAttempt(), "Attempt không được reset nếu chưa đạt 60 giây ổn định");
    }

    @Test
    @DisplayName("Lỗi cấu hình (AUTH_FAIL, TLS, Fingerprint) áp dụng backoff cố định >= 60 giây")
    void testConfigurationErrorBackoff() {
        FullJitterBackoff backoff = new FullJitterBackoff();
        assertTrue(backoff.getConfigurationErrorBackoffMs() >= 60_000L);
    }
}

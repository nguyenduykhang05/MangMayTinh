package com.networkalert.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Đồng hồ giả lập (Test Clock) phục vụ kiểm thử đơn vị tất định.
 * Cho phép dịch chuyển thời gian về phía trước/sau mà không cần sử dụng Thread.sleep().
 */
public class TestClock extends Clock {

    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    public TestClock(Instant initialInstant, ZoneId zone) {
        this.currentInstant = new AtomicReference<>(initialInstant != null ? initialInstant : Instant.EPOCH);
        this.zone = zone != null ? zone : ZoneOffset.UTC;
    }

    public static TestClock fixed(Instant instant, ZoneId zone) {
        return new TestClock(instant, zone);
    }

    public static TestClock fixed(Instant instant) {
        return new TestClock(instant, ZoneOffset.UTC);
    }

    public static TestClock of(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    public void advance(Duration duration) {
        if (duration != null) {
            currentInstant.updateAndGet(prev -> prev.plus(duration));
        }
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    public void setInstant(Instant instant) {
        if (instant != null) {
            currentInstant.set(instant);
        }
    }

    public void setMillis(long millis) {
        currentInstant.set(Instant.ofEpochMilli(millis));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (zone.equals(this.zone)) {
            return this;
        }
        return new TestClock(currentInstant.get(), zone);
    }

    @Override
    public Instant instant() {
        return currentInstant.get();
    }

    @Override
    public long millis() {
        return currentInstant.get().toEpochMilli();
    }

    @Override
    public String toString() {
        return "TestClock[" + currentInstant.get() + ", zone=" + zone + "]";
    }
}

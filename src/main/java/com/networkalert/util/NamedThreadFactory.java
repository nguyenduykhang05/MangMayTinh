package com.networkalert.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThreadFactory có định danh tên luồng rõ ràng và cấu hình cờ daemon.
 * Đáp ứng yêu cầu quản lý luồng tập trung theo AGENTS.md §3.4.
 */
public class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger counter = new AtomicInteger(1);

    public NamedThreadFactory(String prefix) {
        this(prefix, true);
    }

    public NamedThreadFactory(String prefix, boolean daemon) {
        this.prefix = prefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + (counter != null ? "-" + counter.getAndIncrement() : ""));
        t.setDaemon(daemon);
        return t;
    }

    public static Thread createThread(String name, Runnable runnable, boolean daemon) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(daemon);
        return t;
    }
}

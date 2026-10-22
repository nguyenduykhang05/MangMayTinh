package com.networkalert.scanner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Dịch vụ quét cổng mạng TCP đa luồng cực nhanh (Multi-threaded TCP Port Scanner)
 */
public class PortScannerService {
    private ExecutorService executor;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Bảng tra cứu tên dịch vụ chuẩn theo cổng mạng
    private static final Map<Integer, String> COMMON_SERVICES = new HashMap<>();

    static {
        COMMON_SERVICES.put(20, "FTP Data");
        COMMON_SERVICES.put(21, "FTP Control");
        COMMON_SERVICES.put(22, "SSH Remote Login");
        COMMON_SERVICES.put(23, "Telnet");
        COMMON_SERVICES.put(25, "SMTP Mail");
        COMMON_SERVICES.put(53, "DNS Domain Service");
        COMMON_SERVICES.put(80, "HTTP Web Service");
        COMMON_SERVICES.put(110, "POP3 Mail");
        COMMON_SERVICES.put(143, "IMAP Mail");
        COMMON_SERVICES.put(443, "HTTPS Secure Web");
        COMMON_SERVICES.put(465, "SMTPS Secure Mail");
        COMMON_SERVICES.put(587, "SMTP Submission");
        COMMON_SERVICES.put(993, "IMAPS Secure Mail");
        COMMON_SERVICES.put(995, "POP3S Secure Mail");
        COMMON_SERVICES.put(1433, "Microsoft SQL Server");
        COMMON_SERVICES.put(1521, "Oracle Database");
        COMMON_SERVICES.put(3306, "MySQL Database");
        COMMON_SERVICES.put(3389, "RDP Remote Desktop");
        COMMON_SERVICES.put(5432, "PostgreSQL Database");
        COMMON_SERVICES.put(6379, "Redis Cache");
        COMMON_SERVICES.put(8080, "HTTP Alternate / Web Proxy");
        COMMON_SERVICES.put(8443, "HTTPS Alternate");
        COMMON_SERVICES.put(9000, "NCAS Mock TCP Server");
        COMMON_SERVICES.put(9001, "NCAS Mock UDP Server");
        COMMON_SERVICES.put(9002, "NCAS Mock HTTP Server");
    }

    public static String getServiceName(int port) {
        return COMMON_SERVICES.getOrDefault(port, "Dịch vụ mạng tùy biến");
    }

    public void scanRangeAsync(String host, int startPort, int endPort, int timeoutMs,
                               BiConsumer<PortScanResult, Integer> progressCallback,
                               Runnable onComplete) {
        cancelled.set(false);
        int totalPorts = endPort - startPort + 1;
        if (totalPorts <= 0) return;

        int poolSize = Math.min(60, Math.max(10, totalPorts / 5));
        executor = Executors.newFixedThreadPool(poolSize);
        AtomicInteger scannedCount = new AtomicInteger(0);

        new Thread(() -> {
            List<Future<?>> futures = new ArrayList<>();
            for (int p = startPort; p <= endPort; p++) {
                final int port = p;
                Future<?> f = executor.submit(() -> {
                    if (cancelled.get()) return;

                    PortScanResult result = scanSinglePort(host, port, timeoutMs);
                    int currentScanned = scannedCount.incrementAndGet();
                    int progressPercent = (int) ((currentScanned * 100.0) / totalPorts);

                    if (progressCallback != null) {
                        progressCallback.accept(result, progressPercent);
                    }
                });
                futures.add(f);
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {}
            }

            executor.shutdown();
            if (onComplete != null && !cancelled.get()) {
                onComplete.run();
            }
        }, "PortScanner-MasterThread").start();
    }

    public PortScanResult scanSinglePort(String host, int port, int timeoutMs) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setReuseAddress(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            long end = System.nanoTime();
            long latencyMs = (end - start) / 1_000_000;
            if (latencyMs == 0) latencyMs = 1;
            return new PortScanResult(port, true, latencyMs, getServiceName(port));
        } catch (IOException e) {
            return new PortScanResult(port, false, -1, getServiceName(port));
        }
    }

    public void cancel() {
        cancelled.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}

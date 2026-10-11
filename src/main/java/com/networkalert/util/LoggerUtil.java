package com.networkalert.util;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tiện ích ghi log hệ thống ra Console, File (network-alert.log) và Live Console trên UI
 */
public class LoggerUtil {
    private static final String LOG_FILE = "network-alert.log";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<Consumer<String>> logListeners = Collections.synchronizedList(new ArrayList<>());

    static {
        ensureUtf8Console();
    }

    /**
     * Ép buộc toàn bộ luồng Console JVM xuất theo mã hóa UTF-8 chuẩn
     */
    public static void ensureUtf8Console() {
        try {
            System.setProperty("file.encoding", "UTF-8");
            System.setProperty("stdout.encoding", "UTF-8");
            System.setProperty("stderr.encoding", "UTF-8");
            System.setProperty("sun.stdout.encoding", "UTF-8");
            System.setProperty("sun.stderr.encoding", "UTF-8");
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public static void addListener(Consumer<String> listener) {
        logListeners.add(listener);
    }

    public static void removeListener(Consumer<String> listener) {
        logListeners.remove(listener);
    }

    public static void info(String message) {
        log("INFO", message);
    }

    public static void warn(String message) {
        log("WARN", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    public static void alert(String message) {
        log("ALERT", message);
    }

    private static synchronized void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String logLine = String.format("[%s] [%-5s] %s", timestamp, level, message);

        // In ra console
        if ("ERROR".equals(level) || "ALERT".equals(level)) {
            System.err.println(logLine);
        } else {
            System.out.println(logLine);
        }

        // Ghi vào file network-alert.log với UTF-8
        try (FileWriter fw = new FileWriter(LOG_FILE, java.nio.charset.StandardCharsets.UTF_8, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(logLine);
        } catch (IOException e) {
            System.err.println("Không thể ghi vào log file: " + e.getMessage());
        }

        // Bắn sự kiện tới UI Live Console
        synchronized (logListeners) {
            for (Consumer<String> listener : logListeners) {
                try {
                    listener.accept(logLine);
                } catch (Exception ignored) {}
            }
        }
    }
}

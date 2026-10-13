package com.networkalert.console;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tiện ích định dạng bảng ASCII Grid đẹp mắt phục vụ hiển thị CLI
 */
public class AsciiTableFormatter {

    /**
     * Định dạng bảng lưới ASCII từ danh sách tiêu đề và các hàng dữ liệu
     */
    public static String formatTable(String[] headers, List<String[]> rows) {
        if (headers == null || headers.length == 0) return "";

        int cols = headers.length;
        int[] colWidths = new int[cols];

        for (int i = 0; i < cols; i++) {
            colWidths[i] = headers[i].length();
        }

        if (rows != null) {
            for (String[] row : rows) {
                for (int i = 0; i < cols && i < row.length; i++) {
                    String val = row[i] != null ? row[i] : "";
                    if (val.length() > colWidths[i]) {
                        colWidths[i] = val.length();
                    }
                }
            }
        }

        // Tăng khoảng trống đệm mỗi cột 2 ký tự (1 trước, 1 sau)
        StringBuilder border = new StringBuilder("+");
        for (int w : colWidths) {
            border.append("-".repeat(w + 2)).append("+");
        }
        String borderLine = border.toString();

        StringBuilder sb = new StringBuilder();
        sb.append(borderLine).append("\n");

        // Header
        sb.append("|");
        for (int i = 0; i < cols; i++) {
            sb.append(" ").append(padRight(headers[i], colWidths[i])).append(" |");
        }
        sb.append("\n");
        sb.append(borderLine).append("\n");

        // Data Rows
        if (rows != null && !rows.isEmpty()) {
            for (String[] row : rows) {
                sb.append("|");
                for (int i = 0; i < cols; i++) {
                    String cell = (i < row.length && row[i] != null) ? row[i] : "";
                    sb.append(" ").append(padRight(cell, colWidths[i])).append(" |");
                }
                sb.append("\n");
            }
        } else {
            sb.append("| (Không có dữ liệu)").append("\n");
        }
        sb.append(borderLine);

        return sb.toString();
    }

    /**
     * Định dạng danh sách Agent dạng bảng ASCII
     */
    public static String formatAgents(List<Map<String, Object>> agents) {
        if (agents == null || agents.isEmpty()) {
            return "⚠️ Hiện tại không có Agent nào đang kết nối tới Server.";
        }

        String[] headers = {"Agent ID", "Hostname", "IP Address", "Hệ điều hành", "Phiên bản", "Mục tiêu", "Nhịp tim cuối", "CPU", "RAM trống"};
        List<String[]> rows = new ArrayList<>();

        for (Map<String, Object> a : agents) {
            String agentId = String.valueOf(a.getOrDefault("agentId", ""));
            String hostname = String.valueOf(a.getOrDefault("hostname", ""));
            String ip = String.valueOf(a.getOrDefault("localIp", ""));
            String os = String.valueOf(a.getOrDefault("os", ""));
            String version = String.valueOf(a.getOrDefault("version", "1.0.0"));
            String targets = String.valueOf(a.getOrDefault("targetsCount", "0"));

            long lastSec = 0;
            Object secObj = a.get("lastHeartbeatSecAgo");
            if (secObj instanceof Number) lastSec = ((Number) secObj).longValue();
            String lastPing = lastSec + "s trước";

            double cpu = 0.0;
            Object cpuObj = a.get("cpuUsage");
            if (cpuObj instanceof Number) cpu = ((Number) cpuObj).doubleValue();
            String cpuStr = String.format("%.1f%%", cpu);

            long ram = 0;
            Object ramObj = a.get("freeMemoryMb");
            if (ramObj instanceof Number) ram = ((Number) ramObj).longValue();
            String ramStr = ram + " MB";

            rows.add(new String[]{agentId, hostname, ip, os, version, targets, lastPing, cpuStr, ramStr});
        }

        return formatTable(headers, rows);
    }

    /**
     * Định dạng danh sách Mục tiêu đo kiểm dạng bảng ASCII
     */
    public static String formatTargets(List<Map<String, Object>> targets) {
        if (targets == null || targets.isEmpty()) {
            return "⚠️ Danh sách mục tiêu đo kiểm đang trống.";
        }

        String[] headers = {"ID", "Tên mục tiêu", "Host:Port", "Giao thức", "Trạng thái", "Độ trễ", "Agent phụ trách"};
        List<String[]> rows = new ArrayList<>();

        for (Map<String, Object> t : targets) {
            String id = String.valueOf(t.getOrDefault("id", ""));
            String name = String.valueOf(t.getOrDefault("name", ""));
            String host = String.valueOf(t.getOrDefault("host", ""));
            String port = String.valueOf(t.getOrDefault("port", "0"));
            String hostPort = host + (":0".equals(":" + port) ? "" : ":" + port);
            String proto = String.valueOf(t.getOrDefault("protocol", "TCP"));

            String statusRaw = String.valueOf(t.getOrDefault("status", "UNKNOWN"));
            String status;
            if ("ONLINE".equalsIgnoreCase(statusRaw)) {
                status = "🟢 ONLINE";
            } else if ("WARNING".equalsIgnoreCase(statusRaw)) {
                status = "🟡 WARNING";
            } else if ("OFFLINE".equalsIgnoreCase(statusRaw)) {
                status = "🔴 OFFLINE";
            } else {
                status = "⚪ " + statusRaw;
            }

            long latency = -1;
            Object latObj = t.get("latency");
            if (latObj instanceof Number) latency = ((Number) latObj).longValue();
            String latencyStr = (latency >= 0) ? (latency + " ms") : "Timeout";

            String assignedAgent = String.valueOf(t.getOrDefault("assignedAgent", "Chưa gán"));
            if ("null".equals(assignedAgent) || assignedAgent.isEmpty()) {
                assignedAgent = "(Chờ gán)";
            }

            rows.add(new String[]{id, name, hostPort, proto, status, latencyStr, assignedAgent});
        }

        return formatTable(headers, rows);
    }

    private static String padRight(String s, int n) {
        if (s == null) s = "";
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }
}

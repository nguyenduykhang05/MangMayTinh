package com.networkalert.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kết quả đo kiểm chuẩn hóa theo quy cách Nagios (ERRATA §E9.1 & Task T2.1):
 * <ul>
 *   <li>{@code status} thuộc 4 mức: OK, WARN, CRIT, UNKNOWN.</li>
 *   <li>{@code message} tối đa 512 ký tự đã làm sạch ký tự điều khiển.</li>
 *   <li>{@code perfDataList}: Danh sách chỉ số hiệu năng (rta, loss, ttfb, dns, etc.).</li>
 *   <li>{@code durationMs}: Tổng thời gian thực thi (Round-Trip).</li>
 *   <li>{@code errorCode}: Mã lỗi giao thức hoặc mã trạng thái HTTP (200, 404, 500,...).</li>
 *   <li>{@code method}: Phương thức đo kiểm cụ thể (icmp, tcp, udp, http, https, dns, exec).</li>
 * </ul>
 */
public class CheckResult {

    private final boolean success;
    private final long latencyMs;
    private final int responseCode;
    private final String message;
    private final LocalDateTime timestamp;
    private final ServerStatus status;

    private final NagiosStatus nagiosStatus;
    private final List<PerfData> perfDataList;
    private final long durationMs;
    private final int errorCode;
    private final String method;

    /**
     * Hàm khởi tạo đầy đủ chuẩn Nagios
     */
    public CheckResult(NagiosStatus nagiosStatus, long durationMs, int errorCode, String message, String method, List<PerfData> perfData) {
        this.nagiosStatus = nagiosStatus != null ? nagiosStatus : NagiosStatus.UNKNOWN;
        this.status = this.nagiosStatus.toServerStatus();
        this.success = (this.nagiosStatus == NagiosStatus.OK || this.nagiosStatus == NagiosStatus.WARN);
        this.durationMs = durationMs >= 0 ? durationMs : 0;
        this.latencyMs = this.durationMs;
        this.errorCode = errorCode;
        this.responseCode = errorCode;
        this.message = sanitizeMessage(message);
        this.method = method != null ? method.toLowerCase() : "unknown";
        this.perfDataList = (perfData != null) ? Collections.unmodifiableList(new ArrayList<>(perfData)) : Collections.emptyList();
        this.timestamp = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    /**
     * Tương thích ngược: Khởi tạo với ServerStatus truyền thống
     */
    public CheckResult(boolean success, long latencyMs, int responseCode, String message, ServerStatus status) {
        this(NagiosStatus.fromServerStatus(status != null ? status : (success ? ServerStatus.ONLINE : ServerStatus.OFFLINE)),
             latencyMs, responseCode, message, "unknown",
             Collections.singletonList(PerfData.of("time", latencyMs >= 0 ? latencyMs : 0, "ms")));
    }

    /**
     * Tương thích ngược: Khởi tạo 3 tham số cơ bản
     */
    public CheckResult(boolean success, long latencyMs, String message) {
        this(success, latencyMs, success ? 200 : 0, message, success ? ServerStatus.ONLINE : ServerStatus.OFFLINE);
    }

    // =========================================================================
    // Static Factories theo chuẩn Nagios
    // =========================================================================

    public static CheckResult ok(long durationMs, int responseCode, String message, String method, List<PerfData> perfData) {
        return new CheckResult(NagiosStatus.OK, durationMs, responseCode, message, method, perfData);
    }

    public static CheckResult ok(long durationMs, String message, String method, List<PerfData> perfData) {
        return ok(durationMs, 200, message, method, perfData);
    }

    public static CheckResult ok(long durationMs, String message, String method) {
        return ok(durationMs, message, method, Collections.singletonList(PerfData.of("time", durationMs, "ms")));
    }

    public static CheckResult warn(long durationMs, String message, String method, List<PerfData> perfData) {
        return new CheckResult(NagiosStatus.WARN, durationMs, 0, message, method, perfData);
    }

    public static CheckResult warn(long durationMs, String message, String method) {
        return warn(durationMs, message, method, Collections.singletonList(PerfData.of("time", durationMs, "ms")));
    }

    public static CheckResult crit(long durationMs, int errorCode, String message, String method, List<PerfData> perfData) {
        return new CheckResult(NagiosStatus.CRIT, durationMs, errorCode, message, method, perfData);
    }

    public static CheckResult crit(long durationMs, int errorCode, String message, String method) {
        return crit(durationMs, errorCode, message, method, Collections.singletonList(PerfData.of("time", durationMs, "ms")));
    }

    public static CheckResult crit(String message, String method) {
        return new CheckResult(NagiosStatus.CRIT, 0, 2, message, method, null);
    }

    public static CheckResult unknown(long durationMs, int errorCode, String message, String method) {
        return new CheckResult(NagiosStatus.UNKNOWN, durationMs, errorCode, message, method, null);
    }

    public static CheckResult unknown(String message, String method) {
        return new CheckResult(NagiosStatus.UNKNOWN, 0, 3, message, method, null);
    }

    // =========================================================================
    // Legacy Factories tương thích ngược
    // =========================================================================

    public static CheckResult success(long latencyMs, String message) {
        return ok(latencyMs, message, "unknown", Collections.singletonList(PerfData.of("time", latencyMs, "ms")));
    }

    public static CheckResult warning(long latencyMs, String message) {
        return warn(latencyMs, message, "unknown", Collections.singletonList(PerfData.of("time", latencyMs, "ms")));
    }

    public static CheckResult failure(String message) {
        return crit(0, 2, message, "unknown", null);
    }

    public static CheckResult http(int code, long latencyMs, String message) {
        boolean isSuccess = (code >= 200 && code < 400);
        NagiosStatus ns = isSuccess ? NagiosStatus.OK : NagiosStatus.CRIT;
        return new CheckResult(ns, latencyMs, code, message, "http",
                Collections.singletonList(PerfData.of("time", latencyMs, "ms")));
    }

    // =========================================================================
    // Getters & Tiện ích
    // =========================================================================

    public boolean isSuccess() { return success; }
    public long getLatencyMs() { return latencyMs; }
    public int getResponseCode() { return responseCode; }
    public String getMessage() { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public ServerStatus getStatus() { return status; }

    public NagiosStatus getNagiosStatus() { return nagiosStatus; }
    public List<PerfData> getPerfDataList() { return perfDataList; }
    public long getDurationMs() { return durationMs; }
    public int getErrorCode() { return errorCode; }
    public String getMethod() { return method; }

    public boolean isOk() { return nagiosStatus == NagiosStatus.OK; }
    public boolean isWarn() { return nagiosStatus == NagiosStatus.WARN; }
    public boolean isCrit() { return nagiosStatus == NagiosStatus.CRIT; }
    public boolean isUnknown() { return nagiosStatus == NagiosStatus.UNKNOWN; }

    /**
     * Định dạng toàn bộ perfdata thành chuỗi chuẩn Nagios (phục vụ log, xuất đồ thị độ trễ)
     */
    public String getPerfDataString() {
        if (perfDataList.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perfDataList.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(perfDataList.get(i).toNagiosString());
        }
        return sb.toString();
    }

    /**
     * Làm sạch thông điệp kết quả và giới hạn tối đa 512 ký tự (ERRATA §E9.1)
     */
    private static String sanitizeMessage(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // Giữ lại ký tự in được và khoảng trắng/tab/xuống dòng
            if (c >= 32 || c == '\t' || c == '\n') {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.length() > 512) {
            return cleaned.substring(0, 509) + "...";
        }
        return cleaned;
    }

    @Override
    public String toString() {
        String perfStr = getPerfDataString();
        if (!perfStr.isEmpty()) {
            return String.format("[%s] %s | %s", nagiosStatus, message, perfStr);
        }
        return String.format("[%s] Latency: %d ms, Msg: %s", status, latencyMs, message);
    }
}

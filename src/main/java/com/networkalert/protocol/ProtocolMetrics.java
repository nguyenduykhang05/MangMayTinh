package com.networkalert.protocol;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quản lý các chỉ số đo lường lỗi giao thức mạng NCAS-P (Protocol Metrics)
 * <p>
 * Tuân thủ chuẩn nhãn Prometheus: {@code protocol_errors_total{reason="..."}}
 * Các lý do lỗi bắt buộc theo đặc tả TASKS.md §T1.1 và ERRATA §E3.8:
 * <ul>
 *   <li>{@code oversize}: Kích thước Payload vượt quá trần cấu hình hoặc trần trước xác thực 4 KiB</li>
 *   <li>{@code magic}: Magic Bytes sai lệch (không phải 'NCAS')</li>
 *   <li>{@code version}: Phiên bản giao thức không tương thích</li>
 *   <li>{@code crc}: Mã kiểm tra toàn vẹn CRC32 không khớp</li>
 *   <li>{@code slowloris}: Khung truyền bị nghẽn/cắt dở quá 10 giây hoặc đóng kết nối giữa chừng</li>
 *   <li>{@code idle}: Kết nối rảnh không có hoạt động quá 3 chu kỳ nhịp tim</li>
 * </ul>
 * </p>
 */
public final class ProtocolMetrics {

    public static final String REASON_OVERSIZE = "oversize";
    public static final String REASON_MAGIC = "magic";
    public static final String REASON_VERSION = "version";
    public static final String REASON_CRC = "crc";
    public static final String REASON_SLOWLORIS = "slowloris";
    public static final String REASON_IDLE = "idle";

    private static final Map<String, AtomicLong> ERROR_COUNTERS = new ConcurrentHashMap<>();
    private static final AtomicLong AUTH_FAILURES_TOTAL = new AtomicLong(0);
    private static final Map<String, AtomicLong> AUTH_FAILURES_BY_AGENT = new ConcurrentHashMap<>();

    static {
        // Khởi tạo sẵn các metric counter chuẩn để luôn có giá trị ban đầu = 0
        ERROR_COUNTERS.put(REASON_OVERSIZE, new AtomicLong(0));
        ERROR_COUNTERS.put(REASON_MAGIC, new AtomicLong(0));
        ERROR_COUNTERS.put(REASON_VERSION, new AtomicLong(0));
        ERROR_COUNTERS.put(REASON_CRC, new AtomicLong(0));
        ERROR_COUNTERS.put(REASON_SLOWLORIS, new AtomicLong(0));
        ERROR_COUNTERS.put(REASON_IDLE, new AtomicLong(0));
    }

    private ProtocolMetrics() {}

    /**
     * Ghi nhận một lần thất bại xác thực danh tính (ERRATA §E2.5 & §E5.7: auth_failures_total)
     */
    public static void recordAuthFailure(String agentId) {
        AUTH_FAILURES_TOTAL.incrementAndGet();
        if (agentId != null && !agentId.trim().isEmpty()) {
            AUTH_FAILURES_BY_AGENT.computeIfAbsent(agentId.trim(), k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * Lấy tổng số lần thất bại xác thực toàn hệ thống
     */
    public static long getAuthFailuresTotal() {
        return AUTH_FAILURES_TOTAL.get();
    }

    /**
     * Lấy số lần thất bại xác thực của một Agent cụ thể
     */
    public static long getAuthFailuresForAgent(String agentId) {
        if (agentId == null) return 0L;
        AtomicLong c = AUTH_FAILURES_BY_AGENT.get(agentId.trim());
        return c != null ? c.get() : 0L;
    }

    /**
     * Tăng số đếm lỗi giao thức theo lý do
     */
    public static void recordError(String reason) {
        String key = (reason != null && !reason.trim().isEmpty()) ? reason.trim().toLowerCase() : "unknown";
        ERROR_COUNTERS.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Lấy số lượng lỗi theo lý do cụ thể
     */
    public static long getErrorCount(String reason) {
        String key = (reason != null && !reason.trim().isEmpty()) ? reason.trim().toLowerCase() : "unknown";
        AtomicLong counter = ERROR_COUNTERS.get(key);
        return counter != null ? counter.get() : 0L;
    }

    /**
     * Lấy tổng số lỗi của tất cả các lý do
     */
    public static long getTotalErrors() {
        return ERROR_COUNTERS.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /**
     * Lấy snapshot bản đồ đếm lỗi hiện tại (bất biến)
     */
    public static Map<String, Long> getAllErrorCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        ERROR_COUNTERS.forEach((k, v) -> result.put(k, v.get()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Reset toàn bộ bộ đếm về 0 (Phục vụ kiểm thử đơn vị hoặc khởi động lại)
     */
    public static void reset() {
        ERROR_COUNTERS.values().forEach(c -> c.set(0));
        AUTH_FAILURES_TOTAL.set(0);
        AUTH_FAILURES_BY_AGENT.clear();
    }
}

package com.networkalert.server.ingest;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bộ đếm chỉ số đo lường hiệu năng và độ tin cậy của đường ống Ingestor (ERRATA §E5.7)
 * <p>
 * Theo dõi các chỉ số phục vụ Prometheus /panel Agent:
 * <ul>
 *   <li>{@code results_received_total}: Tổng số kết quả đo kiểm nhận được từ các Agent</li>
 *   <li>{@code duplicates_total}: Số kết quả đo kiểm bị loại do trùng lặp số thứ tự (seq)</li>
 *   <li>{@code out_of_order_total}: Số kết quả đo kiểm đến lệch thứ tự (seq nhảy cóc)</li>
 *   <li>{@code backfilled_total}: Số kết quả đo kiểm được phân loại là dữ liệu bù lịch sử (backfill)</li>
 *   <li>{@code lost_total}: Số mốc seq bị mất mát (do Agent drop hoặc gap nhảy cóc)</li>
 *   <li>{@code sampled_total}: Số kết quả đo kiểm bị lấy mẫu giảm tải (downsampling)</li>
 *   <li>{@code ack_regression_total}: Số lần nhận diện nguy cơ watermark bị tụt lùi</li>
 *   <li>{@code auth_failures_total}: Số lần từ chối kết quả do sai phân quyền Target hoặc Agent</li>
 *   <li>{@code protocol_errors_total{reason}}: Phân loại lỗi giao thức theo lý do</li>
 *   <li>{@code ingest_queue_depth}: Độ sâu hàng đợi xử lý nạp dữ liệu hiện thời</li>
 *   <li>{@code ingest_latency_ms}: Độ trễ xử lý nạp dữ liệu (ms) của lượt gần nhất</li>
 * </ul>
 * </p>
 */
public class IngestMetrics {

    private final AtomicLong resultsReceivedTotal = new AtomicLong(0);
    private final AtomicLong duplicatesTotal = new AtomicLong(0);
    private final AtomicLong outOfOrderTotal = new AtomicLong(0);
    private final AtomicLong backfilledTotal = new AtomicLong(0);
    private final AtomicLong lostTotal = new AtomicLong(0);
    private final AtomicLong sampledTotal = new AtomicLong(0);
    private final AtomicLong ackRegressionTotal = new AtomicLong(0);
    private final AtomicLong authFailuresTotal = new AtomicLong(0);
    private final AtomicLong ingestQueueDepth = new AtomicLong(0);
    private final AtomicLong lastIngestLatencyMs = new AtomicLong(0);
    private final Map<String, AtomicLong> protocolErrors = new ConcurrentHashMap<>();

    public IngestMetrics() {
        protocolErrors.put("invalid_payload", new AtomicLong(0));
        protocolErrors.put("target_not_found", new AtomicLong(0));
        protocolErrors.put("backpressure_drop", new AtomicLong(0));
        protocolErrors.put("sink_error", new AtomicLong(0));
    }

    public void incrementResultsReceived() {
        resultsReceivedTotal.incrementAndGet();
    }

    public void incrementResultsReceived(long count) {
        resultsReceivedTotal.addAndGet(count);
    }

    public void incrementDuplicates() {
        duplicatesTotal.incrementAndGet();
    }

    public void incrementOutOfOrder() {
        outOfOrderTotal.incrementAndGet();
    }

    public void incrementBackfilled() {
        backfilledTotal.incrementAndGet();
    }

    public void incrementLost(long count) {
        lostTotal.addAndGet(count);
    }

    public void incrementSampled() {
        sampledTotal.incrementAndGet();
    }

    public void incrementAckRegression() {
        ackRegressionTotal.incrementAndGet();
    }

    public void incrementAuthFailures() {
        authFailuresTotal.incrementAndGet();
    }

    public void setQueueDepth(long depth) {
        ingestQueueDepth.set(Math.max(0, depth));
    }

    public void recordIngestLatency(long latencyMs) {
        lastIngestLatencyMs.set(Math.max(0, latencyMs));
    }

    public void recordProtocolError(String reason) {
        String key = (reason != null && !reason.trim().isEmpty()) ? reason.trim().toLowerCase() : "unknown";
        protocolErrors.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    // --- Getters ---

    public AtomicLong getResultsReceivedTotal() { return resultsReceivedTotal; }
    public AtomicLong getDuplicatesTotal() { return duplicatesTotal; }
    public AtomicLong getOutOfOrderTotal() { return outOfOrderTotal; }
    public AtomicLong getBackfilledTotal() { return backfilledTotal; }
    public AtomicLong getLostTotal() { return lostTotal; }
    public AtomicLong getSampledTotal() { return sampledTotal; }
    public AtomicLong getAckRegressionTotal() { return ackRegressionTotal; }
    public AtomicLong getAuthFailuresTotal() { return authFailuresTotal; }
    public AtomicLong getIngestQueueDepth() { return ingestQueueDepth; }
    public AtomicLong getLastIngestLatencyMs() { return lastIngestLatencyMs; }
    public Map<String, AtomicLong> getProtocolErrors() { return Collections.unmodifiableMap(protocolErrors); }

    public long getProtocolErrorCount(String reason) {
        String key = (reason != null && !reason.trim().isEmpty()) ? reason.trim().toLowerCase() : "unknown";
        AtomicLong c = protocolErrors.get(key);
        return c != null ? c.get() : 0L;
    }

    /**
     * Đặt lại toàn bộ số đếm về 0 (phục vụ kiểm thử)
     */
    public void reset() {
        resultsReceivedTotal.set(0);
        duplicatesTotal.set(0);
        outOfOrderTotal.set(0);
        backfilledTotal.set(0);
        lostTotal.set(0);
        sampledTotal.set(0);
        ackRegressionTotal.set(0);
        authFailuresTotal.set(0);
        ingestQueueDepth.set(0);
        lastIngestLatencyMs.set(0);
        protocolErrors.values().forEach(c -> c.set(0));
    }
}

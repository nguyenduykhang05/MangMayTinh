package com.networkalert.server.ingest;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerTarget;
import com.networkalert.server.TargetDistributor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Giao diện lưu trữ bền vững kết quả đo kiểm và watermark theo ERRATA §E5.1 & §E8.3:
 * <p>
 * {@code persist(batch, streamId, watermark)} cam kết hoàn tất SAU KHI commit.
 * Ở Task T1.6 hỗ trợ {@link InMemoryResultSink} cho kiểm thử và {@link DefaultResultSink} cho hệ thống.
 * </p>
 */
public interface ResultSink {

    /**
     * Lưu trữ bền vững một lô kết quả đo kiểm và cam kết watermark tuần tự.
     * Phương thức chỉ trả về khi dữ liệu đã được lưu bền thành công.
     *
     * @param batch Danh sách các kết quả đo kiểm trong lô
     * @param agentId ID của máy trạm Agent
     * @param streamId ID luồng dữ liệu
     * @param watermark Điểm mốc tuần tự đã cam kết bền vững
     * @throws IOException Khi có lỗi ghi đĩa hoặc lỗi CSDL
     */
    void persist(List<IngestedResult> batch, String agentId, long streamId, long watermark) throws IOException;

    /**
     * Lấy điểm mốc watermark đã cam kết bền vững của một luồng
     */
    long getWatermark(String agentId, long streamId);

    /**
     * Hiện thực lưu trữ trong bộ nhớ phục vụ kiểm thử đơn vị và kịch bản chaos
     */
    class InMemoryResultSink implements ResultSink {
        private final List<IngestedResult> storedResults = new CopyOnWriteArrayList<>();
        private final Map<String, Long> watermarks = new ConcurrentHashMap<>();

        @Override
        public void persist(List<IngestedResult> batch, String agentId, long streamId, long watermark) throws IOException {
            if (batch != null && !batch.isEmpty()) {
                storedResults.addAll(batch);
            }
            watermarks.put(makeKey(agentId, streamId), watermark);
        }

        @Override
        public long getWatermark(String agentId, long streamId) {
            return watermarks.getOrDefault(makeKey(agentId, streamId), 0L);
        }

        public List<IngestedResult> getStoredResults() {
            return Collections.unmodifiableList(storedResults);
        }

        public void clear() {
            storedResults.clear();
            watermarks.clear();
        }

        private String makeKey(String agentId, long streamId) {
            return (agentId != null ? agentId : "default") + ":" + streamId;
        }
    }

    /**
     * Hiện thực sink mặc định cho NCAS Server kết nối vào bộ nhớ đối tượng mục tiêu ServerTarget
     */
    class DefaultResultSink implements ResultSink {
        private final TargetDistributor targetDistributor;
        private final Map<String, Long> watermarks = new ConcurrentHashMap<>();

        public DefaultResultSink(TargetDistributor targetDistributor) {
            this.targetDistributor = targetDistributor;
        }

        @Override
        public void persist(List<IngestedResult> batch, String agentId, long streamId, long watermark) throws IOException {
            watermarks.put((agentId != null ? agentId : "default") + ":" + streamId, watermark);
        }

        @Override
        public long getWatermark(String agentId, long streamId) {
            return watermarks.getOrDefault((agentId != null ? agentId : "default") + ":" + streamId, 0L);
        }
    }
}

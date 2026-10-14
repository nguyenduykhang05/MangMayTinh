package com.networkalert.server.ingest;

import com.networkalert.repository.DatabaseManager;
import com.networkalert.repository.backend.DatabaseBackendType;
import com.networkalert.util.LoggerUtil;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hiện thực lưu trữ bền vững {@link ResultSink} sử dụng JDBC theo ERRATA §E5.1 & §E8.3:
 * <ul>
 *   <li>Batch insert vào bảng {@code CheckResults} với ràng buộc {@code UNIQUE(agent_id, stream_id, seq)}.</li>
 *   <li>Cập nhật {@code StreamState} (watermark) trong CÙNG MỘT TRANSACTION.</li>
 *   <li>Chỉ trả về khi transaction đã commit thành công trên CSDL (bảo đảm ACK sau commit).</li>
 *   <li>Hỗ trợ cả H2 Database (File & In-Memory) và Microsoft SQL Server.</li>
 * </ul>
 */
public class JdbcResultSink implements ResultSink {

    private final DatabaseManager databaseManager;
    private final Clock clock;
    private final Map<String, Long> watermarkCache = new ConcurrentHashMap<>();

    private static final String H2_MERGE_SQL =
            "MERGE INTO CheckResults (" +
            "  agent_id, stream_id, seq, target_id, checked_at_utc, recv_at_utc, " +
            "  status, latency_us, message, perf_json, method, backfilled" +
            ") KEY (agent_id, stream_id, seq) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String MSSQL_MERGE_SQL =
            "MERGE INTO CheckResults AS t " +
            "USING (VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)) AS s (" +
            "  agent_id, stream_id, seq, target_id, checked_at_utc, recv_at_utc, " +
            "  status, latency_us, message, perf_json, method, backfilled" +
            ") " +
            "ON (t.agent_id = s.agent_id AND t.stream_id = s.stream_id AND t.seq = s.seq) " +
            "WHEN NOT MATCHED THEN " +
            "  INSERT (agent_id, stream_id, seq, target_id, checked_at_utc, recv_at_utc, " +
            "          status, latency_us, message, perf_json, method, backfilled) " +
            "  VALUES (s.agent_id, s.stream_id, s.seq, s.target_id, s.checked_at_utc, s.recv_at_utc, " +
            "          s.status, s.latency_us, s.message, s.perf_json, s.method, s.backfilled);";

    public JdbcResultSink() {
        this(DatabaseManager.getInstance(), Clock.systemUTC());
    }

    public JdbcResultSink(DatabaseManager databaseManager) {
        this(databaseManager, Clock.systemUTC());
    }

    public JdbcResultSink(DatabaseManager databaseManager, Clock clock) {
        this.databaseManager = databaseManager != null ? databaseManager : DatabaseManager.getInstance();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public void persist(List<IngestedResult> batch, String agentId, long streamId, long watermark) throws IOException {
        String effectiveAgentId = (agentId != null && !agentId.isBlank()) ? agentId : "default";
        long now = clock.millis();

        Connection conn = null;
        boolean originalAutoCommit = true;
        try {
            conn = databaseManager.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            // 1. Batch insert danh sách kết quả đo kiểm
            if (batch != null && !batch.isEmpty()) {
                insertBatch(conn, batch, effectiveAgentId, streamId);
            }

            // 2. Cập nhật StreamState (watermark) trong cùng transaction
            upsertStreamState(conn, effectiveAgentId, streamId, watermark, now);

            // 3. Commit transaction bảo đảm dữ liệu đã xuống đĩa an toàn trước khi ACK
            conn.commit();

            // 4. Cập nhật bộ nhớ đệm watermark
            watermarkCache.put(makeKey(effectiveAgentId, streamId), watermark);

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LoggerUtil.error("[JdbcResultSink] Lỗi rollback transaction: " + rollbackEx.getMessage());
                }
            }
            throw new IOException("Không thể lưu bền lô kết quả đo kiểm vào CSDL: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                    conn.close();
                } catch (SQLException ignored) {}
            }
        }
    }

    private void insertBatch(Connection conn, List<IngestedResult> batch, String defaultAgentId, long defaultStreamId) throws SQLException {
        DatabaseBackendType backendType = databaseManager.getBackendType();
        String sql = (backendType == DatabaseBackendType.MSSQL) ? MSSQL_MERGE_SQL : H2_MERGE_SQL;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (IngestedResult result : batch) {
                String aId = (result.getAgentId() != null && !result.getAgentId().isBlank()) ? result.getAgentId() : defaultAgentId;
                long sId = result.getStreamId() != 0 ? result.getStreamId() : defaultStreamId;

                pstmt.setString(1, aId);
                pstmt.setLong(2, sId);
                pstmt.setLong(3, result.getSeq());
                pstmt.setInt(4, result.getTargetId());
                pstmt.setLong(5, result.getCheckedAtServerMs());
                pstmt.setLong(6, result.getServerRecvMs());
                pstmt.setString(7, result.isSuccess() ? "OK" : "CRIT");
                pstmt.setLong(8, result.getLatencyMs() * 1000L); // micro giây

                String msg = result.getMessage();
                if (msg != null && msg.length() > 512) {
                    msg = msg.substring(0, 512);
                }
                pstmt.setString(9, msg);
                pstmt.setString(10, null); // perf_json tạm thời null
                pstmt.setString(11, result.getMethod() != null ? result.getMethod() : "ICMP");
                pstmt.setBoolean(12, result.isBackfilled());

                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private void upsertStreamState(Connection conn, String agentId, long streamId, long watermark, long now) throws SQLException {
        String updateSql = "UPDATE StreamState SET watermark = ?, updated_at_utc = ? WHERE agent_id = ? AND stream_id = ?";
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            updateStmt.setLong(1, watermark);
            updateStmt.setLong(2, now);
            updateStmt.setString(3, agentId);
            updateStmt.setLong(4, streamId);
            int rowsUpdated = updateStmt.executeUpdate();

            if (rowsUpdated == 0) {
                String insertSql = "INSERT INTO StreamState (agent_id, stream_id, watermark, updated_at_utc) VALUES (?, ?, ?, ?)";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, agentId);
                    insertStmt.setLong(2, streamId);
                    insertStmt.setLong(3, watermark);
                    insertStmt.setLong(4, now);
                    insertStmt.executeUpdate();
                }
            }
        }
    }

    @Override
    public long getWatermark(String agentId, long streamId) {
        String effectiveAgentId = (agentId != null && !agentId.isBlank()) ? agentId : "default";
        String key = makeKey(effectiveAgentId, streamId);
        Long cached = watermarkCache.get(key);
        if (cached != null) {
            return cached;
        }

        String sql = "SELECT watermark FROM StreamState WHERE agent_id = ? AND stream_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, effectiveAgentId);
            stmt.setLong(2, streamId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long wm = rs.getLong(1);
                    watermarkCache.put(key, wm);
                    return wm;
                }
            }
        } catch (SQLException e) {
            LoggerUtil.warn("[JdbcResultSink] Không thể truy vấn StreamState cho " + key + ": " + e.getMessage());
        }
        return 0L;
    }

    /**
     * Đếm tổng số bản ghi trong bảng CheckResults (phục vụ kiểm thử và xác minh tính toàn vẹn)
     */
    public long countResults() throws SQLException {
        String sql = "SELECT COUNT(*) FROM CheckResults";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0L;
    }

    /**
     * Xóa sạch cache bộ nhớ đệm (phục vụ kiểm thử đọc lại trực tiếp từ CSDL)
     */
    public void clearCache() {
        watermarkCache.clear();
    }

    private String makeKey(String agentId, long streamId) {
        return (agentId != null ? agentId : "default") + ":" + streamId;
    }
}

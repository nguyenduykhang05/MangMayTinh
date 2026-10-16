package com.networkalert.server.ingest;

import com.networkalert.repository.DatabaseManager;
import com.networkalert.repository.backend.H2InMemoryBackend;
import com.networkalert.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử hợp đồng và hiệu năng của JdbcResultSink theo ERRATA §E8.3:
 * <ul>
 *   <li>Batch insert và StreamState cập nhật trong một transaction.</li>
 *   <li>Ràng buộc UNIQUE(agent_id, stream_id, seq) ngăn chặn trùng lặp.</li>
 *   <li>Watermark lưu bền và phục hồi chính xác sau khi khởi động lại.</li>
 *   <li>Hiệu năng batch insert đạt mục tiêu ≥ 2.000 kết quả/giây trên H2.</li>
 * </ul>
 */
public class JdbcResultSinkTest {

    private DatabaseManager databaseManager;
    private TestClock testClock;
    private JdbcResultSink sink;

    @BeforeEach
    void setUp() throws Exception {
        testClock = TestClock.fixed(Instant.parse("2026-10-24T12:00:00Z"));
        databaseManager = DatabaseManager.getInstance();

        // Sử dụng In-Memory backend độc lập cho mỗi test case
        H2InMemoryBackend inMemoryBackend = new H2InMemoryBackend();
        databaseManager.setBackendForTesting(inMemoryBackend);

        sink = new JdbcResultSink(databaseManager, testClock);
    }

    @AfterEach
    void tearDown() {
        databaseManager.resetForTesting();
    }

    @Test
    @DisplayName("Ghi lô kết quả và cam kết watermark trong cùng một transaction")
    void testBatchInsertAndWatermarkCommit() throws Exception {
        List<IngestedResult> batch = new ArrayList<>();
        for (long seq = 1; seq <= 5; seq++) {
            batch.add(new IngestedResult(
                    "agent-01", 100L, seq, 1,
                    testClock.millis(), testClock.millis(),
                    true, 25, 200, "OK", "ICMP", false, null
            ));
        }

        sink.persist(batch, "agent-01", 100L, 5L);

        assertEquals(5, sink.countResults(), "Bảng CheckResults phải chứa đúng 5 bản ghi");
        assertEquals(5L, sink.getWatermark("agent-01", 100L), "Watermark của stream 100 phải là 5");
    }

    @Test
    @DisplayName("Khử trùng lặp theo UNIQUE(agent_id, stream_id, seq) không gây lỗi transaction")
    void testDuplicateHandlingOnSameSeq() throws Exception {
        // Lô 1: seq 1..5
        List<IngestedResult> batch1 = new ArrayList<>();
        for (long seq = 1; seq <= 5; seq++) {
            batch1.add(new IngestedResult(
                    "agent-01", 100L, seq, 1,
                    testClock.millis(), testClock.millis(),
                    true, 20, 200, "OK", "ICMP", false, null
            ));
        }
        sink.persist(batch1, "agent-01", 100L, 5L);
        assertEquals(5, sink.countResults());

        // Lô 2: seq 3..7 (trong đó 3, 4, 5 bị trùng)
        List<IngestedResult> batch2 = new ArrayList<>();
        for (long seq = 3; seq <= 7; seq++) {
            batch2.add(new IngestedResult(
                    "agent-01", 100L, seq, 1,
                    testClock.millis(), testClock.millis(),
                    true, 22, 200, "OK", "ICMP", false, null
            ));
        }
        sink.persist(batch2, "agent-01", 100L, 7L);

        assertEquals(7, sink.countResults(), "Tổng số bản ghi phải là 7 (không chèn trùng seq 3, 4, 5)");
        assertEquals(7L, sink.getWatermark("agent-01", 100L), "Watermark phải được cập nhật lên 7");
    }

    @Test
    @DisplayName("Watermark tồn tại bền vững và phục hồi chính xác khi khởi tạo instance mới")
    void testWatermarkPersistenceAcrossRestart() throws Exception {
        List<IngestedResult> batch = new ArrayList<>();
        batch.add(new IngestedResult(
                "agent-persisted", 999L, 42L, 2,
                testClock.millis(), testClock.millis(),
                true, 15, 200, "OK", "TCP", false, null
        ));

        sink.persist(batch, "agent-persisted", 999L, 42L);
        assertEquals(42L, sink.getWatermark("agent-persisted", 999L));

        // Khởi tạo instance mới (mô phỏng Server khởi động lại, bộ nhớ đệm rỗng)
        JdbcResultSink newServerSink = new JdbcResultSink(databaseManager, testClock);
        assertEquals(42L, newServerSink.getWatermark("agent-persisted", 999L),
                "Instance mới phải đọc được watermark 42 đã lưu trong bảng StreamState");
    }

    @Test
    @DisplayName("Đạt mục tiêu hiệu năng batch insert ≥ 2.000 kết quả/giây trên H2 (ERRATA §E8.3)")
    void testThroughputBenchmark() throws Exception {
        int totalResults = 2_500;
        List<IngestedResult> largeBatch = new ArrayList<>(totalResults);

        for (int i = 1; i <= totalResults; i++) {
            largeBatch.add(new IngestedResult(
                    "agent-bench", 888L, i, (i % 10) + 1,
                    testClock.millis(), testClock.millis(),
                    (i % 20 != 0), 10 + (i % 50), 200, "Benchmark " + i, "ICMP", false, null
            ));
        }

        long startNs = System.nanoTime();
        sink.persist(largeBatch, "agent-bench", 888L, totalResults);
        long durationNs = System.nanoTime() - startNs;

        double durationSec = durationNs / 1_000_000_000.0;
        double throughputPerSec = totalResults / durationSec;

        System.out.printf("[Benchmark] Đã ghi %d kết quả vào H2 trong %.4f giây -> Tốc độ: %.1f kết quả/giây%n",
                totalResults, durationSec, throughputPerSec);

        assertEquals(totalResults, sink.countResults());
        assertEquals(totalResults, sink.getWatermark("agent-bench", 888L));
        assertTrue(throughputPerSec >= 2000.0,
                String.format("Tốc độ ghi (%.1f r/s) phải đạt chuẩn tối thiểu 2.000 r/s", throughputPerSec));
    }
}

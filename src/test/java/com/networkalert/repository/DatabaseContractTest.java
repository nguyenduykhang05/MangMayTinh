package com.networkalert.repository;

import com.networkalert.repository.backend.DatabaseBackend;
import com.networkalert.repository.backend.DatabaseBackendType;
import com.networkalert.repository.backend.H2FileBackend;
import com.networkalert.repository.backend.H2InMemoryBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử hợp đồng CSDL (DatabaseContractTest) cho Task T3.1 (§E8.1)
 * Kiểm thử tính bền vững của CSDL H2 qua các lần tắt/mở ứng dụng,
 * đảm bảo hàng đợi ghi có trần (10.000) và chống phân rã (split-brain).
 */
public class DatabaseContractTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.getInstance();
    }

    @AfterEach
    void tearDown() {
        dbManager.resetForTesting();
    }

    @Test
    @DisplayName("H2 File: Lưu trữ bền vững qua các lần tắt mở ứng dụng (Restart Persistence)")
    void testH2FilePersistenceAcrossRestarts() throws SQLException {
        File dbFile = tempDir.resolve("test-persistence-db").toFile();
        String dbPath = dbFile.getAbsolutePath();

        // Lần chạy 1: Khởi tạo CSDL H2 tệp và ghi dữ liệu
        try (H2FileBackend backend1 = new H2FileBackend(dbPath)) {
            backend1.initializeSchema();
            assertTrue(backend1.isPersistent(), "H2FileBackend phải là dạng lưu trữ bền vững (persistent)");
            assertEquals(DatabaseBackendType.H2_FILE, backend1.getType());

            try (Connection conn = backend1.getConnection()) {
                // Thêm target mới
                String insertTarget = "INSERT INTO ServerTargets (name, host, port, protocol, interval_seconds, timeout_ms, warning_threshold_ms, scope, group_name, tags, enabled) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertTarget)) {
                    ps.setString(1, "Core Switch Branch A");
                    ps.setString(2, "10.20.30.1");
                    ps.setInt(3, 0);
                    ps.setString(4, "ICMP");
                    ps.setInt(5, 5);
                    ps.setInt(6, 1500);
                    ps.setLong(7, 200);
                    ps.setString(8, "AGENT");
                    ps.setString(9, "Core");
                    ps.setString(10, "switch,core");
                    ps.setBoolean(11, true);
                    assertEquals(1, ps.executeUpdate());
                }

                // Thêm sự cố IncidentLog
                String insertIncident = "INSERT INTO IncidentLogs (target_id, target_name, host, port, protocol, event_type, message, acknowledged) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertIncident)) {
                    ps.setInt(1, 100);
                    ps.setString(2, "Core Switch Branch A");
                    ps.setString(3, "10.20.30.1");
                    ps.setInt(4, 0);
                    ps.setString(5, "ICMP");
                    ps.setString(6, "OFFLINE");
                    ps.setString(7, "Loss of optical link");
                    ps.setBoolean(8, false);
                    assertEquals(1, ps.executeUpdate());
                }

                // Thêm vấn đề Problem
                String insertProblem = "INSERT INTO problems (target_id, target_name, source, severity, status, title, description) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertProblem)) {
                    ps.setInt(1, 100);
                    ps.setString(2, "Core Switch Branch A");
                    ps.setString(3, "Server");
                    ps.setString(4, "HIGH");
                    ps.setString(5, "ACTIVE");
                    ps.setString(6, "Core Switch Branch A unreachable");
                    ps.setString(7, "Both primary and secondary uplinks down");
                    assertEquals(1, ps.executeUpdate());
                }

                // Cập nhật cấu hình
                String insertSetting = "MERGE INTO AlertSettings (setting_key, setting_value) KEY (setting_key) VALUES (?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSetting)) {
                    ps.setString(1, "test_persistence_key");
                    ps.setString(2, "test_persistence_value_123");
                    ps.executeUpdate();
                }
            }
        } // backend1 đóng kết nối và SHUTDOWN CSDL tương tự như tắt app

        // Lần chạy 2: Khởi tạo lại kết nối tới CÙNG file CSDL (mô phỏng mở lại ứng dụng)
        try (H2FileBackend backend2 = new H2FileBackend(dbPath)) {
            backend2.initializeSchema(); // Phải idempotent

            try (Connection conn = backend2.getConnection()) {
                // Kiểm tra Target vẫn còn nguyên
                try (PreparedStatement ps = conn.prepareStatement("SELECT name, host, scope FROM ServerTargets WHERE host = ?")) {
                    ps.setString(1, "10.20.30.1");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Dữ liệu ServerTarget phải tồn tại sau khi ứng dụng khởi động lại!");
                        assertEquals("Core Switch Branch A", rs.getString("name"));
                        assertEquals("AGENT", rs.getString("scope"));
                    }
                }

                // Kiểm tra IncidentLog vẫn còn nguyên
                try (PreparedStatement ps = conn.prepareStatement("SELECT message, event_type FROM IncidentLogs WHERE target_name = ?")) {
                    ps.setString(1, "Core Switch Branch A");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Dữ liệu IncidentLogs phải tồn tại sau khi ứng dụng khởi động lại!");
                        assertEquals("Loss of optical link", rs.getString("message"));
                        assertEquals("OFFLINE", rs.getString("event_type"));
                    }
                }

                // Kiểm tra Problem vẫn còn nguyên
                try (PreparedStatement ps = conn.prepareStatement("SELECT title, severity, status FROM problems WHERE target_name = ?")) {
                    ps.setString(1, "Core Switch Branch A");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Dữ liệu Problem phải tồn tại sau khi ứng dụng khởi động lại!");
                        assertEquals("Core Switch Branch A unreachable", rs.getString("title"));
                        assertEquals("HIGH", rs.getString("severity"));
                        assertEquals("ACTIVE", rs.getString("status"));
                    }
                }

                // Kiểm tra AlertSettings vẫn còn nguyên
                try (PreparedStatement ps = conn.prepareStatement("SELECT setting_value FROM AlertSettings WHERE setting_key = ?")) {
                    ps.setString(1, "test_persistence_key");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Dữ liệu AlertSettings phải tồn tại sau khi ứng dụng khởi động lại!");
                        assertEquals("test_persistence_value_123", rs.getString("setting_value"));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Chống Split-Brain: Mất kết nối trong runtime TUYỆT ĐỐI KHÔNG tự động chuyển Backend")
    void testNoBackendSwitchingOnRuntimeDisconnect() {
        File dbFile = tempDir.resolve("split-brain-guard-db").toFile();
        H2FileBackend h2Backend = new H2FileBackend(dbFile.getAbsolutePath());
        dbManager.setBackendForTesting(h2Backend);

        assertTrue(dbManager.isConnected());
        assertEquals(DatabaseBackendType.H2_FILE, dbManager.getBackendType());

        // Mô phỏng mất kết nối giữa chừng
        dbManager.markConnectionLost("Simulated network link drop");

        assertFalse(dbManager.isConnected(), "Trạng thái kết nối phải chuyển thành disconnected");
        // Backend type vẫn phải giữ nguyên là H2_FILE, KHÔNG được chuyển thành IN_MEMORY (chống split-brain)
        assertEquals(DatabaseBackendType.H2_FILE, dbManager.getBackendType(),
                "Backend type phải giữ nguyên H2_FILE để chống chẻ đôi dữ liệu (split-brain)!");
        assertTrue(dbManager.getBackendStatusText().contains("Mất kết nối"));
    }

    @Test
    @DisplayName("Hàng đợi ghi có trần (10.000) và kích hoạt Backpressure khi đầy (§E8.1, §E5.6)")
    void testBoundedWriteQueueAndBackpressure() {
        File dbFile = tempDir.resolve("queue-test-db").toFile();
        H2FileBackend h2Backend = new H2FileBackend(dbFile.getAbsolutePath());
        dbManager.setBackendForTesting(h2Backend);

        // Mô phỏng mất kết nối
        dbManager.markConnectionLost("Simulated DB offline for queue test");
        assertFalse(dbManager.isConnected());

        AtomicInteger counter = new AtomicInteger(0);

        // Nạp 100 tác vụ ghi
        for (int i = 0; i < 100; i++) {
            boolean enqueued = dbManager.enqueueWrite(counter::incrementAndGet);
            assertTrue(enqueued, "Tác vụ ghi phải được chấp nhận khi hàng đợi chưa đầy");
        }

        assertEquals(100, dbManager.getPendingWriteCount());
        assertFalse(dbManager.isWriteQueueFull());

        // Nạp thêm để chạm trần MAX_WRITE_QUEUE_CAPACITY (10.000)
        int remaining = DatabaseManager.MAX_WRITE_QUEUE_CAPACITY - 100;
        for (int i = 0; i < remaining; i++) {
            boolean enqueued = dbManager.enqueueWrite(counter::incrementAndGet);
            assertTrue(enqueued);
        }

        assertEquals(DatabaseManager.MAX_WRITE_QUEUE_CAPACITY, dbManager.getPendingWriteCount());
        assertTrue(dbManager.isWriteQueueFull(), "Hàng đợi phải báo đầy khi chạm mốc 10.000");

        // Thử nạp bản ghi thứ 10.001 -> phải bị từ chối (kích hoạt Backpressure, ngừng phát ACK)
        boolean rejected = dbManager.enqueueWrite(counter::incrementAndGet);
        assertFalse(rejected, "Tác vụ ghi thứ 10.001 phải bị từ chối để bảo vệ Server không cạn kiệt bộ nhớ!");
        assertTrue(dbManager.isWriteQueueFull());

        // Mô phỏng kết nối CSDL được khôi phục -> testConnection() thành công xả hàng đợi
        boolean recovered = dbManager.testConnection();
        assertTrue(recovered);
        assertTrue(dbManager.isConnected());

        // Kiểm tra toàn bộ 10.000 tác vụ đã được xả và thực thi
        assertEquals(DatabaseManager.MAX_WRITE_QUEUE_CAPACITY, counter.get());
        assertEquals(0, dbManager.getPendingWriteCount(), "Hàng đợi phải trống sau khi xả");
        assertFalse(dbManager.isWriteQueueFull(), "Trạng thái đầy phải được giải tỏa");
    }

    @Test
    @DisplayName("H2 In-Memory Backend: Dành riêng cho Unit Test (Non-persistent)")
    void testH2InMemoryBackend() throws SQLException {
        try (H2InMemoryBackend memBackend = new H2InMemoryBackend()) {
            memBackend.initializeSchema();
            assertFalse(memBackend.isPersistent(), "H2InMemoryBackend không được phép là persistent");
            assertEquals(DatabaseBackendType.IN_MEMORY, memBackend.getType());

            try (Connection conn = memBackend.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ServerTargets")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 0);
            }
        }
    }
}

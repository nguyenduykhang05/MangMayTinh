package com.networkalert.repository;

import com.networkalert.repository.backend.DatabaseBackendType;
import com.networkalert.repository.backend.H2FileBackend;
import com.networkalert.repository.backend.H2InMemoryBackend;
import com.networkalert.repository.migration.MigrationManager;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử Hệ thống Schema & Migration phiên bản hóa cho Task T3.2 (§E8.2)
 */
public class MigrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Migration: Áp dụng thành công các phiên bản V1 và V2, lưu vết schema_version")
    void testMigrationExecutionAndTracking() throws SQLException {
        try (H2InMemoryBackend backend = new H2InMemoryBackend()) {
            MigrationManager migrationManager = new MigrationManager();

            try (Connection conn = backend.getConnection()) {
                int applied = migrationManager.migrate(conn, backend.getType());
                assertEquals(2, applied, "Phải áp dụng chính xác 2 bản migration (V1, V2)");

                Set<Integer> versions = migrationManager.getAppliedVersions(conn);
                assertTrue(versions.contains(1));
                assertTrue(versions.contains(2));
                assertEquals(2, migrationManager.getCurrentVersion(conn));

                // Chạy lại lần 2 -> Idempotent, không chạy lại
                int secondRun = migrationManager.migrate(conn, backend.getType());
                assertEquals(0, secondRun, "Chạy lại migration phải idempotent (0 bản ghi mới)");
            }
        }
    }

    @Test
    @DisplayName("Ràng buộc CheckResults: UNIQUE(agent_id, stream_id, seq) chặn trùng lặp hoàn toàn (§E8.2)")
    void testCheckResultsUniqueConstraint() throws SQLException {
        try (H2InMemoryBackend backend = new H2InMemoryBackend()) {
            backend.initializeSchema();

            try (Connection conn = backend.getConnection()) {
                String insertSql = "INSERT INTO CheckResults (agent_id, stream_id, seq, target_id, checked_at_utc, recv_at_utc, status, latency_us, message, method, backfilled) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

                // Lần 1: Chèn bản ghi hợp lệ
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, "agent-hn-01");
                    ps.setLong(2, 88888L);
                    ps.setLong(3, 1L);
                    ps.setInt(4, 10);
                    ps.setLong(5, 1700000000000L);
                    ps.setLong(6, 1700000000010L);
                    ps.setString(7, "OK");
                    ps.setLong(8, 25000L);
                    ps.setString(9, "Round trip 25ms");
                    ps.setString(10, "ICMP");
                    ps.setBoolean(11, false);
                    assertEquals(1, ps.executeUpdate());
                }

                // Lần 2: Chèn bản ghi trùng (cùng agent_id, stream_id, seq) -> Phải ném SQLException
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, "agent-hn-01");
                    ps.setLong(2, 88888L);
                    ps.setLong(3, 1L); // Trùng seq 1
                    ps.setInt(4, 10);
                    ps.setLong(5, 1700000000100L);
                    ps.setLong(6, 1700000000110L);
                    ps.setString(7, "OK");
                    ps.setLong(8, 30000L);
                    ps.setString(9, "Duplicate seq report");
                    ps.setString(10, "ICMP");
                    ps.setBoolean(11, false);

                    assertThrows(SQLException.class, ps::executeUpdate,
                            "UNIQUE(agent_id, stream_id, seq) phải chặn đứng việc ghi trùng kết quả!");
                }
            }
        }
    }

    @Test
    @DisplayName("Nâng cấp Schema từ DB mẫu V1 lên V2 không làm mất dữ liệu hiện có (§E8.2)")
    void testUpgradeFromV1ToV2WithoutDataLoss() throws SQLException {
        File dbFile = tempDir.resolve("upgrade-test-db").toFile();
        String dbPath = dbFile.getAbsolutePath();

        MigrationManager migrationManager = new MigrationManager();

        // Bước 1: Khởi tạo database và CHỈ áp dụng V1 (Legacy DB)
        try (H2FileBackend backend = new H2FileBackend(dbPath)) {
            try (Connection conn = backend.getConnection()) {
                // Tạo schema_version và chạy V1
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE schema_version (version INT PRIMARY KEY, description VARCHAR(255) NOT NULL, script_name VARCHAR(255) NOT NULL, checksum INT NOT NULL, installed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, execution_time_ms BIGINT NOT NULL, success BOOLEAN NOT NULL)");
                }
                // Nạp script V1
                String v1Script = "CREATE TABLE ServerTargets (id INT IDENTITY(1,1) PRIMARY KEY, name VARCHAR(100) NOT NULL, host VARCHAR(255) NOT NULL, port INT NOT NULL DEFAULT 80, protocol VARCHAR(20) NOT NULL DEFAULT 'TCP', interval_seconds INT NOT NULL DEFAULT 5, timeout_ms INT NOT NULL DEFAULT 1500, warning_threshold_ms BIGINT NOT NULL DEFAULT 0, scope VARCHAR(20) NOT NULL DEFAULT 'MULTI', assigned_stations VARCHAR(255) NULL, group_name VARCHAR(50) NOT NULL DEFAULT 'Default', tags VARCHAR(255) NULL, parent_id INT NULL, parent_agent VARCHAR(50) NULL, max_check_attempts INT NOT NULL DEFAULT 3, retry_interval INT NOT NULL DEFAULT 2, expected_keyword VARCHAR(255) NULL, push_token VARCHAR(64) NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);\n" +
                        "CREATE TABLE problems (id INT IDENTITY(1,1) PRIMARY KEY, target_id INT NOT NULL, target_name VARCHAR(100) NOT NULL, source VARCHAR(100) NOT NULL DEFAULT 'Server', severity VARCHAR(20) NOT NULL DEFAULT 'HIGH', status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', title VARCHAR(255) NOT NULL, description NVARCHAR(MAX) NULL, start_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, resolved_time TIMESTAMP NULL, ack_time TIMESTAMP NULL, ack_user VARCHAR(50) NULL, ack_comment NVARCHAR(MAX) NULL);";
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : migrationManager.parseStatements(v1Script)) {
                        stmt.execute(sql);
                    }
                    stmt.execute("INSERT INTO schema_version VALUES (1, 'init schema', 'V1__init_schema.sql', 12345, CURRENT_TIMESTAMP, 50, true)");
                }

                // Ghi dữ liệu legacy vào V1
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ServerTargets (name, host) VALUES (?, ?)")) {
                    ps.setString(1, "Legacy Server Target");
                    ps.setString(2, "192.168.1.100");
                    assertEquals(1, ps.executeUpdate());
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO problems (target_id, target_name, title) VALUES (?, ?, ?)")) {
                    ps.setInt(1, 1);
                    ps.setString(2, "Legacy Server Target");
                    ps.setString(3, "Legacy problem title");
                    assertEquals(1, ps.executeUpdate());
                }
            }
        } // Đóng kết nối V1

        // Bước 2: Mở lại ứng dụng và thực hiện migration tự động lên V2
        try (H2FileBackend backend = new H2FileBackend(dbPath)) {
            try (Connection conn = backend.getConnection()) {
                int upgraded = migrationManager.migrate(conn, backend.getType());
                assertEquals(1, upgraded, "Phải nâng cấp duy nhất bản V2");
                assertEquals(2, migrationManager.getCurrentVersion(conn));

                // Kiểm tra dữ liệu cũ trong ServerTargets và problems vẫn còn nguyên 100%
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT name, host FROM ServerTargets WHERE host = '192.168.1.100'")) {
                    assertTrue(rs.next(), "Dữ liệu cũ trong ServerTargets không được phép bị mất!");
                    assertEquals("Legacy Server Target", rs.getString("name"));
                }

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT title, detected_late, suppressed_by FROM problems WHERE target_name = 'Legacy Server Target'")) {
                    assertTrue(rs.next(), "Dữ liệu cũ trong problems không được phép bị mất!");
                    assertEquals("Legacy problem title", rs.getString("title"));
                    assertFalse(rs.getBoolean("detected_late"), "Cột mới detected_late phải mang giá trị mặc định false");
                    assertNull(rs.getString("suppressed_by"), "Cột mới suppressed_by phải là null");
                }

                // Kiểm tra bảng mới CheckResults và StreamState đã sẵn sàng hoạt động
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM CheckResults")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
            }
        }
    }
}

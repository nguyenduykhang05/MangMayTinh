package com.networkalert.repository.backend;

import com.networkalert.util.LoggerUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Backend CSDL tệp H2 lưu trữ bền vững trên đĩa cứng (ERRATA §E8.1, Task T3.1)
 * Mặc định ghi vào đường dẫn ./data/ncas-db.mv.db.
 */
public class H2FileBackend implements DatabaseBackend {
    public static final String DEFAULT_DB_PATH = "./data/ncas-db";

    private final String dbPath;
    private final String jdbcUrl;
    private volatile boolean closed = false;

    public H2FileBackend() {
        this(System.getProperty("ncas.db.path", DEFAULT_DB_PATH));
    }

    public H2FileBackend(String dbPath) {
        this.dbPath = dbPath;
        // Đảm bảo thư mục cha tồn tại
        File dbFile = new File(dbPath);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        // Chuỗi kết nối H2 chế độ MSSQL tương thích kèm AUTO_SERVER=TRUE cho phép chia sẻ an toàn
        this.jdbcUrl = "jdbc:h2:" + dbPath + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=MSSQLServer;NON_KEYWORDS=KEY,VALUE";
        tryLoadDriver();
    }

    private void tryLoadDriver() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            LoggerUtil.error("[H2FileBackend] Không tìm thấy H2 Driver: " + e.getMessage());
        }
    }

    @Override
    public DatabaseBackendType getType() {
        return DatabaseBackendType.H2_FILE;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("H2FileBackend đã bị đóng.");
        }
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    @Override
    public boolean testConnection() {
        if (closed) return false;
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void initializeSchema() throws SQLException {
        try (Connection conn = getConnection()) {
            new com.networkalert.repository.migration.MigrationManager().migrate(conn, getType());
            seedDataIfEmpty(conn);
            LoggerUtil.info("[H2FileBackend] Đã khởi tạo schema CSDL H2 bền vững thành công (" + dbPath + ").");
        }
    }

    private void seedDataIfEmpty(Connection conn) throws SQLException {
        try (Statement checkStmt = conn.createStatement();
             ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM ServerTargets")) {
            if (rs.next() && rs.getInt(1) == 0) {
                String insertSql = "INSERT INTO ServerTargets (name, host, port, protocol, interval_seconds, timeout_ms, warning_threshold_ms, scope, group_name, tags, enabled) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    // 1. Default Gateway (Scope AGENT theo §E11.1 / §E13 #8)
                    ps.setString(1, "Wi-Fi Router / Default Gateway");
                    ps.setString(2, "192.168.100.1");
                    ps.setInt(3, 0);
                    ps.setString(4, "ICMP");
                    ps.setInt(5, 3);
                    ps.setInt(6, 1000);
                    ps.setLong(7, 150);
                    ps.setString(8, "AGENT");
                    ps.setString(9, "Mạng nội bộ");
                    ps.setString(10, "gateway,router");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 2. Google DNS
                    ps.setString(1, "Google Public DNS");
                    ps.setString(2, "8.8.8.8");
                    ps.setInt(3, 0);
                    ps.setString(4, "ICMP");
                    ps.setInt(5, 15);
                    ps.setInt(6, 2000);
                    ps.setLong(7, 300);
                    ps.setString(8, "MULTI");
                    ps.setString(9, "Dịch vụ DNS");
                    ps.setString(10, "dns,google");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 3. Cloudflare DNS
                    ps.setString(1, "Cloudflare DNS");
                    ps.setString(2, "1.1.1.1");
                    ps.setInt(3, 53);
                    ps.setString(4, "DNS");
                    ps.setInt(5, 15);
                    ps.setInt(6, 2000);
                    ps.setLong(7, 250);
                    ps.setString(8, "MULTI");
                    ps.setString(9, "Dịch vụ DNS");
                    ps.setString(10, "dns,cloudflare");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 4. Google Web
                    ps.setString(1, "Google Web HTTPS");
                    ps.setString(2, "google.com");
                    ps.setInt(3, 443);
                    ps.setString(4, "HTTPS");
                    ps.setInt(5, 20);
                    ps.setInt(6, 3500);
                    ps.setLong(7, 1000);
                    ps.setString(8, "MULTI");
                    ps.setString(9, "Web công cộng");
                    ps.setString(10, "web,search");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 5. Cloudflare Global Web
                    ps.setString(1, "Cloudflare Global Web");
                    ps.setString(2, "cloudflare.com");
                    ps.setInt(3, 443);
                    ps.setString(4, "HTTPS");
                    ps.setInt(5, 20);
                    ps.setInt(6, 3500);
                    ps.setLong(7, 1000);
                    ps.setString(8, "MULTI");
                    ps.setString(9, "Web công cộng");
                    ps.setString(10, "web,cdn");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 6. VnExpress
                    ps.setString(1, "VnExpress Báo Mạng VN");
                    ps.setString(2, "vnexpress.net");
                    ps.setInt(3, 443);
                    ps.setString(4, "HTTPS");
                    ps.setInt(5, 20);
                    ps.setInt(6, 3500);
                    ps.setLong(7, 800);
                    ps.setString(8, "MULTI");
                    ps.setString(9, "Báo chí");
                    ps.setString(10, "news,vietnam");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 7. GitHub API
                    ps.setString(1, "GitHub Global API");
                    ps.setString(2, "api.github.com");
                    ps.setInt(3, 443);
                    ps.setString(4, "HTTPS");
                    ps.setInt(5, 30);
                    ps.setInt(6, 4000);
                    ps.setLong(7, 1200);
                    ps.setString(8, "MULTI");
                    ps.setString(9, "Dịch vụ Cloud");
                    ps.setString(10, "git,api");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 8. Mock TCP
                    ps.setString(1, "[Giả lập] Mock TCP Server");
                    ps.setString(2, "127.0.0.1");
                    ps.setInt(3, 9000);
                    ps.setString(4, "TCP");
                    ps.setInt(5, 3);
                    ps.setInt(6, 1000);
                    ps.setLong(7, 100);
                    ps.setString(8, "SERVER");
                    ps.setString(9, "Giả lập");
                    ps.setString(10, "mock,tcp");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 9. Mock HTTP
                    ps.setString(1, "[Giả lập] Mock HTTP Server");
                    ps.setString(2, "127.0.0.1");
                    ps.setInt(3, 9002);
                    ps.setString(4, "HTTP");
                    ps.setInt(5, 3);
                    ps.setInt(6, 1000);
                    ps.setLong(7, 100);
                    ps.setString(8, "SERVER");
                    ps.setString(9, "Giả lập");
                    ps.setString(10, "mock,http");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    // 10. Mock UDP
                    ps.setString(1, "[Giả lập] Mock UDP Echo");
                    ps.setString(2, "127.0.0.1");
                    ps.setInt(3, 9001);
                    ps.setString(4, "UDP");
                    ps.setInt(5, 4);
                    ps.setInt(6, 1000);
                    ps.setLong(7, 100);
                    ps.setString(8, "SERVER");
                    ps.setString(9, "Giả lập");
                    ps.setString(10, "mock,udp");
                    ps.setBoolean(11, true);
                    ps.addBatch();

                    ps.executeBatch();
                }
            }
        }

        // Seed AlertSettings if empty
        try (Statement checkStmt = conn.createStatement();
             ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM AlertSettings")) {
            if (rs.next() && rs.getInt(1) == 0) {
                String insertSettings = "INSERT INTO AlertSettings (setting_key, setting_value) VALUES " +
                        "('popup_enabled', 'true'), " +
                        "('sound_enabled', 'true'), " +
                        "('email_enabled', 'false'), " +
                        "('latency_threshold_ms', '300')";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(insertSettings);
                }
            }
        }
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public String getStatusDescription() {
        return "H2 File (" + dbPath + ")";
    }

    public String getDbPath() {
        return dbPath;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
            } catch (SQLException ignored) {
            }
        }
    }
}

package com.networkalert.repository.backend;

import com.networkalert.util.LoggerUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Backend CSDL H2 In-Memory (CHỈ dùng cho kiểm thử tự động, ERRATA §E8.1)
 */
public class H2InMemoryBackend implements DatabaseBackend {
    private final String dbName;
    private final String jdbcUrl;
    private volatile boolean closed = false;

    public H2InMemoryBackend() {
        this("ncas_mem_" + UUID.randomUUID().toString().replace("-", ""));
    }

    public H2InMemoryBackend(String dbName) {
        this.dbName = dbName;
        this.jdbcUrl = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MSSQLServer;NON_KEYWORDS=KEY,VALUE";
        tryLoadDriver();
    }

    private void tryLoadDriver() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            LoggerUtil.error("[H2InMemoryBackend] Không tìm thấy H2 Driver: " + e.getMessage());
        }
    }

    @Override
    public DatabaseBackendType getType() {
        return DatabaseBackendType.IN_MEMORY;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("H2InMemoryBackend đã bị đóng.");
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
            LoggerUtil.info("[H2InMemoryBackend] Đã khởi tạo in-memory schema (" + dbName + ").");
        }
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public String getStatusDescription() {
        return "In-Memory (" + dbName + ")";
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

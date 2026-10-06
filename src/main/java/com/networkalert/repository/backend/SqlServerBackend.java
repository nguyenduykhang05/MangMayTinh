package com.networkalert.repository.backend;

import com.networkalert.model.DatabaseConfig;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.util.LoggerUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Backend CSDL Microsoft SQL Server (ERRATA §E8.1, Task T3.1)
 */
public class SqlServerBackend implements DatabaseBackend {
    private final DatabaseConfig config;
    private volatile boolean closed = false;

    public SqlServerBackend(DatabaseConfig config) {
        this.config = config != null ? config : new DatabaseConfig();
        tryLoadDriver();
    }

    private void tryLoadDriver() {
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        } catch (ClassNotFoundException e) {
            LoggerUtil.error("[SqlServerBackend] Không tìm thấy SQLServerDriver: " + e.getMessage());
        }
        DatabaseManager.tryLoadAuthDll();
    }

    @Override
    public DatabaseBackendType getType() {
        return DatabaseBackendType.MSSQL;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("SqlServerBackend đã bị đóng.");
        }
        DatabaseManager.tryLoadAuthDll();
        String url = config.buildJdbcUrl();
        return DriverManager.getConnection(url);
    }

    @Override
    public boolean testConnection() {
        if (closed) return false;
        DatabaseManager.tryLoadAuthDll();
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("Cannot open database")) {
                if (tryCreateDatabase()) {
                    try (Connection retryConn = getConnection()) {
                        return retryConn != null && !retryConn.isClosed();
                    } catch (SQLException ignored) {}
                }
            }
            return false;
        }
    }

    private boolean tryCreateDatabase() {
        try {
            String masterUrl = config.buildJdbcUrl();
            if (masterUrl.contains("databaseName=" + config.getDatabaseName())) {
                masterUrl = masterUrl.replace("databaseName=" + config.getDatabaseName(), "databaseName=master");
            }
            try (Connection conn = DriverManager.getConnection(masterUrl);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("IF NOT EXISTS (SELECT * FROM sys.databases WHERE name = '" + config.getDatabaseName() + "') CREATE DATABASE " + config.getDatabaseName());
                LoggerUtil.info("[SqlServerBackend] Đã tự động tạo cơ sở dữ liệu '" + config.getDatabaseName() + "' thành công!");
                return true;
            }
        } catch (Exception ex) {
            LoggerUtil.error("[SqlServerBackend] Không thể tự động tạo database: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public void initializeSchema() throws SQLException {
        try (Connection conn = getConnection()) {
            new com.networkalert.repository.migration.MigrationManager().migrate(conn, getType());
            LoggerUtil.info("[SqlServerBackend] Khởi tạo các bảng SQL Server thành công.");
        }
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public String getStatusDescription() {
        return "SQL Server (" + config.getServer() + ":" + config.getPort() + "/" + config.getDatabaseName() + ")";
    }

    @Override
    public void close() {
        this.closed = true;
    }
}

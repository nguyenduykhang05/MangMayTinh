package com.networkalert.repository.backend;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Giao diện trừu tượng hóa Backend Cơ sở dữ liệu (ERRATA §E8.1, Task T3.1)
 */
public interface DatabaseBackend extends AutoCloseable {

    DatabaseBackendType getType();

    Connection getConnection() throws SQLException;

    boolean testConnection();

    void initializeSchema() throws SQLException;

    boolean isPersistent();

    String getStatusDescription();

    @Override
    void close();
}

package com.networkalert.repository.backend;

/**
 * Phân loại loại hình Cơ sở dữ liệu của NCAS (ADR-06, Task T3.1)
 */
public enum DatabaseBackendType {
    MSSQL("SQL Server", true),
    H2_FILE("H2 File (bền vững)", true),
    IN_MEMORY("In-Memory (tạm thời)", false);

    private final String displayName;
    private final boolean persistent;

    DatabaseBackendType(String displayName, boolean persistent) {
        this.displayName = displayName;
        this.persistent = persistent;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isPersistent() {
        return persistent;
    }
}

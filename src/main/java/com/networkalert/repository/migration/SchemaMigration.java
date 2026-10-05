package com.networkalert.repository.migration;

/**
 * Đại diện cho một bản ghi phiên bản di chuyển Schema (Task T3.2)
 */
public class SchemaMigration {
    private final int version;
    private final String description;
    private final String scriptName;
    private final int checksum;

    public SchemaMigration(int version, String description, String scriptName, int checksum) {
        this.version = version;
        this.description = description;
        this.scriptName = scriptName;
        this.checksum = checksum;
    }

    public int getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getScriptName() {
        return scriptName;
    }

    public int getChecksum() {
        return checksum;
    }
}

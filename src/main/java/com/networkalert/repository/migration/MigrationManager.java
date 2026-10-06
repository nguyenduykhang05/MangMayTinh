package com.networkalert.repository.migration;

import com.networkalert.repository.backend.DatabaseBackendType;
import com.networkalert.util.LoggerUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Công cụ quản lý di chuyển và nâng cấp cấu trúc Schema phiên bản hóa (ERRATA §E8.2, Task T3.2)
 * Tự phát triển gọn nhẹ (Lightweight Migration Manager), hỗ trợ song song H2 và SQL Server.
 */
public class MigrationManager {

    private static final List<String> KNOWN_SCRIPTS = Arrays.asList(
            "V1__init_schema.sql",
            "V2__ncas_v2_schema.sql"
    );

    private final Clock clock;

    public MigrationManager() {
        this(Clock.systemUTC());
    }

    public MigrationManager(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Chạy toàn bộ các bản di chuyển chưa được áp dụng trên kết nối CSDL
     * @return Số lượng phiên bản đã được nâng cấp thành công
     */
    public synchronized int migrate(Connection conn, DatabaseBackendType backendType) throws SQLException {
        if (conn == null) throw new IllegalArgumentException("Connection không được null.");

        ensureSchemaVersionTable(conn, backendType);
        Set<Integer> appliedVersions = getAppliedVersions(conn);

        String dialect = (backendType == DatabaseBackendType.MSSQL) ? "mssql" : "h2";
        int migratedCount = 0;

        for (String scriptName : KNOWN_SCRIPTS) {
            int version = extractVersion(scriptName);
            if (appliedVersions.contains(version)) {
                continue; // Đã áp dụng
            }

            String description = extractDescription(scriptName);
            String resourcePath = "db/migration/" + dialect + "/" + scriptName;
            String scriptContent = loadResource(resourcePath);
            if (scriptContent == null) {
                LoggerUtil.warn("[MigrationManager] Không tìm thấy file script di chuyển: " + resourcePath);
                continue;
            }

            int checksum = calculateChecksum(scriptContent);
            long startMs = clock.millis();

            LoggerUtil.info(String.format("🔄 [MigrationManager] Đang áp dụng %s (%s) cho %s...",
                    scriptName, description, backendType.getDisplayName()));

            executeScript(conn, scriptContent);

            long elapsedMs = clock.millis() - startMs;
            recordMigration(conn, backendType, version, description, scriptName, checksum, elapsedMs, true);
            migratedCount++;

            LoggerUtil.info(String.format("✅ [MigrationManager] Đã áp dụng thành công %s trong %d ms.",
                    scriptName, elapsedMs));
        }

        return migratedCount;
    }

    public Set<Integer> getAppliedVersions(Connection conn) {
        Set<Integer> versions = new HashSet<>();
        String sql = "SELECT version FROM schema_version WHERE success = 1";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                versions.add(rs.getInt("version"));
            }
        } catch (SQLException ignored) {}
        return versions;
    }

    public int getCurrentVersion(Connection conn) {
        String sql = "SELECT MAX(version) FROM schema_version WHERE success = 1";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private void ensureSchemaVersionTable(Connection conn, DatabaseBackendType backendType) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (backendType == DatabaseBackendType.MSSQL) {
                stmt.execute("IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'schema_version') " +
                        "CREATE TABLE schema_version (" +
                        "    version INT PRIMARY KEY," +
                        "    description NVARCHAR(255) NOT NULL," +
                        "    script_name NVARCHAR(255) NOT NULL," +
                        "    checksum INT NOT NULL," +
                        "    installed_at DATETIME DEFAULT GETDATE()," +
                        "    execution_time_ms BIGINT NOT NULL," +
                        "    success INT NOT NULL" +
                        ");");
            } else {
                stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (" +
                        "    version INT PRIMARY KEY," +
                        "    description VARCHAR(255) NOT NULL," +
                        "    script_name VARCHAR(255) NOT NULL," +
                        "    checksum INT NOT NULL," +
                        "    installed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "    execution_time_ms BIGINT NOT NULL," +
                        "    success INT NOT NULL" +
                        ");");
            }
        }
    }

    private void recordMigration(Connection conn, DatabaseBackendType backendType, int version,
                                 String description, String scriptName, int checksum,
                                 long elapsedMs, boolean success) throws SQLException {
        String sql = "INSERT INTO schema_version (version, description, script_name, checksum, execution_time_ms, success) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, version);
            ps.setString(2, description);
            ps.setString(3, scriptName);
            ps.setInt(4, checksum);
            ps.setLong(5, elapsedMs);
            ps.setInt(6, success ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void executeScript(Connection conn, String scriptContent) throws SQLException {
        List<String> statements = parseStatements(scriptContent);
        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    if (!sql.trim().isEmpty()) {
                        stmt.execute(sql);
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    public List<String> parseStatements(String script) {
        if (script == null || script.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = script.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("GO")) {
                if (current.length() > 0) {
                    statements.add(current.toString().trim());
                    current.setLength(0);
                }
                continue;
            }
            current.append(line).append("\n");
            if (trimmed.endsWith(";")) {
                statements.add(current.toString().trim());
                current.setLength(0);
            }
        }

        if (current.length() > 0 && !current.toString().trim().isEmpty()) {
            statements.add(current.toString().trim());
        }

        return statements;
    }

    private int extractVersion(String scriptName) {
        try {
            int idx = scriptName.indexOf("__");
            if (idx > 1 && scriptName.startsWith("V")) {
                return Integer.parseInt(scriptName.substring(1, idx));
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private String extractDescription(String scriptName) {
        int idx = scriptName.indexOf("__");
        if (idx > 0 && scriptName.endsWith(".sql")) {
            String sub = scriptName.substring(idx + 2, scriptName.length() - 4);
            return sub.replace("_", " ");
        }
        return scriptName;
    }

    private int calculateChecksum(String content) {
        CRC32 crc = new CRC32();
        crc.update(content.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    private String loadResource(String path) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(path);
        if (is == null) {
            // Thử nạp từ file hệ thống trực tiếp nếu chạy kiểm thử
            java.io.File f = new java.io.File("src/main/resources/" + path);
            if (f.exists()) {
                try {
                    return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

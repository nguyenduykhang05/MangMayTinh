package com.networkalert.model;

/**
 * Cấu hình kết nối Microsoft SQL Server
 */
public class DatabaseConfig {
    private String server = "localhost";
    private String instanceName = "SQLEXPRESS";
    private int port = 1433;
    private String databaseName = "NetworkAlertDB";
    private String username = "sa";
    private String password = "";
    private boolean useIntegratedSecurity = false; // Mặc định dùng SQL Authentication
    private boolean trustServerCertificate = true;

    public DatabaseConfig() {}

    public String buildJdbcUrl() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:sqlserver://").append(server);
        if (instanceName != null && !instanceName.trim().isEmpty()) {
            sb.append("\\").append(instanceName.trim());
        } else if (port > 0) {
            sb.append(":").append(port);
        }
        sb.append(";databaseName=").append(databaseName);
        if (useIntegratedSecurity) {
            sb.append(";integratedSecurity=true");
        } else {
            sb.append(";user=").append(username);
            sb.append(";password=").append(password);
        }
        if (trustServerCertificate) {
            sb.append(";trustServerCertificate=true");
        }
        sb.append(";loginTimeout=5;");
        return sb.toString();
    }

    // Getters and Setters
    public String getServer() { return server; }
    public void setServer(String server) { this.server = server; }

    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isUseIntegratedSecurity() { return useIntegratedSecurity; }
    public void setUseIntegratedSecurity(boolean useIntegratedSecurity) { this.useIntegratedSecurity = useIntegratedSecurity; }

    public boolean isTrustServerCertificate() { return trustServerCertificate; }
    public void setTrustServerCertificate(boolean trustServerCertificate) { this.trustServerCertificate = trustServerCertificate; }
}

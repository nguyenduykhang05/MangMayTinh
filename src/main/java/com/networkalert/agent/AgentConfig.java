package com.networkalert.agent;

import java.net.InetAddress;

/**
 * Cấu hình khởi chạy của trạm đo NCAS Agent
 */
public class AgentConfig {
    private String serverHost = "127.0.0.1";
    private int serverPort = 9876;
    private String agentId;
    private String authToken = "NCAS-SECRET-TOKEN";
    private int heartbeatIntervalSeconds = 10;
    private long reconnectInitialDelayMs = 1000L;
    private long reconnectMaxDelayMs = 30000L;
    private int maxQueueCapacity = 500;
    private boolean allowLegacyAuth = true; // Mặc định BẬT trong constructor để tương thích bộ test v1 (ERRATA §E2.4)
    private boolean tlsEnabled = false; // Mặc định TẮT trong constructor để tương thích test (BẬT trong parse CLI)
    private final java.util.List<String> serverFingerprints = new java.util.ArrayList<>();
    private String policyFilePath = "data/agent-policy.json";
    private String spoolDirPath = "data/spool";
    private long maxSpoolBytes = 50 * 1024 * 1024L;
    private int maxSpoolHours = 24;
    private long spoolFsyncIntervalMs = 1000L;
    private String assignedCachePath = "data/assigned.json";

    public AgentConfig() {
        String defaultHost = "node-01";
        try {
            defaultHost = InetAddress.getLocalHost().getHostName().toLowerCase();
        } catch (Exception ignored) {}
        this.agentId = "agent-" + defaultHost;
    }

    public static AgentConfig parse(String[] args) {
        AgentConfig config = new AgentConfig();
        config.setAllowLegacyAuth(false); // Mặc định TẮT ở CLI/production (ERRATA §E2.4)
        config.setTlsEnabled(true); // Mặc định BẬT TLS ở CLI/production (ERRATA §E2.1)
        if (args == null) return config;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--server".equalsIgnoreCase(arg) || "-s".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setServerHost(args[++i]);
                }
            } else if ("--port".equalsIgnoreCase(arg) || "-p".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setServerPort(Integer.parseInt(args[++i]));
                }
            } else if ("--id".equalsIgnoreCase(arg) || "--agent-id".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setAgentId(args[++i]);
                }
            } else if ("--token".equalsIgnoreCase(arg) || "-t".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setAuthToken(args[++i]);
                }
            } else if ("--heartbeat".equalsIgnoreCase(arg) || "-hb".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setHeartbeatIntervalSeconds(Integer.parseInt(args[++i]));
                }
            } else if ("--allow-legacy-auth".equalsIgnoreCase(arg)) {
                config.setAllowLegacyAuth(true);
            } else if ("--no-tls".equalsIgnoreCase(arg)) {
                config.setTlsEnabled(false);
            } else if ("--server-fp".equalsIgnoreCase(arg) || "--fingerprint".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    String[] fps = args[++i].split(",");
                    for (String fp : fps) {
                        if (!fp.trim().isEmpty()) {
                            config.addServerFingerprint(fp.trim());
                        }
                    }
                }
            } else if ("--policy".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setPolicyFilePath(args[++i]);
                }
            } else if ("--spool-dir".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setSpoolDirPath(args[++i]);
                }
            } else if ("--max-spool-mb".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setMaxSpoolBytes(Long.parseLong(args[++i]) * 1024L * 1024L);
                }
            } else if ("--assigned-cache".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setAssignedCachePath(args[++i]);
                }
            }
        }
        return config;
    }

    // --- Getters & Setters ---
    public String getSpoolDirPath() { return spoolDirPath; }
    public void setSpoolDirPath(String spoolDirPath) { this.spoolDirPath = spoolDirPath; }

    public long getMaxSpoolBytes() { return maxSpoolBytes; }
    public void setMaxSpoolBytes(long maxSpoolBytes) { this.maxSpoolBytes = maxSpoolBytes; }

    public int getMaxSpoolHours() { return maxSpoolHours; }
    public void setMaxSpoolHours(int maxSpoolHours) { this.maxSpoolHours = maxSpoolHours; }

    public long getSpoolFsyncIntervalMs() { return spoolFsyncIntervalMs; }
    public void setSpoolFsyncIntervalMs(long spoolFsyncIntervalMs) { this.spoolFsyncIntervalMs = spoolFsyncIntervalMs; }

    public String getAssignedCachePath() { return assignedCachePath; }
    public void setAssignedCachePath(String assignedCachePath) { this.assignedCachePath = assignedCachePath; }
    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }

    public java.util.List<String> getServerFingerprints() { return java.util.Collections.unmodifiableList(serverFingerprints); }
    public void addServerFingerprint(String fingerprint) {
        if (fingerprint != null && !fingerprint.trim().isEmpty()) {
            this.serverFingerprints.add(fingerprint.trim());
        }
    }
    public void setServerFingerprints(java.util.List<String> fingerprints) {
        this.serverFingerprints.clear();
        if (fingerprints != null) {
            for (String fp : fingerprints) {
                addServerFingerprint(fp);
            }
        }
    }
    public boolean isAllowLegacyAuth() { return allowLegacyAuth; }
    public void setAllowLegacyAuth(boolean allowLegacyAuth) { this.allowLegacyAuth = allowLegacyAuth; }

    public String getPolicyFilePath() { return policyFilePath; }
    public void setPolicyFilePath(String policyFilePath) { this.policyFilePath = policyFilePath; }
    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }

    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = Math.max(2, heartbeatIntervalSeconds);
    }

    public long getReconnectInitialDelayMs() { return reconnectInitialDelayMs; }
    public void setReconnectInitialDelayMs(long reconnectInitialDelayMs) {
        this.reconnectInitialDelayMs = reconnectInitialDelayMs;
    }

    public long getReconnectMaxDelayMs() { return reconnectMaxDelayMs; }
    public void setReconnectMaxDelayMs(long reconnectMaxDelayMs) {
        this.reconnectMaxDelayMs = reconnectMaxDelayMs;
    }

    public int getMaxQueueCapacity() { return maxQueueCapacity; }
    public void setMaxQueueCapacity(int maxQueueCapacity) {
        this.maxQueueCapacity = maxQueueCapacity;
    }

    @Override
    public String toString() {
        return String.format("AgentConfig[id=%s, server=%s:%d, hb=%ds, queueMax=%d]",
                agentId, serverHost, serverPort, heartbeatIntervalSeconds, maxQueueCapacity);
    }
}

package com.networkalert.console;

import java.util.ArrayList;
import java.util.List;

/**
 * Cấu hình khởi chạy của Bàn điều khiển Quản trị NCAS Console
 */
public class ConsoleConfig {
    private String serverHost = "127.0.0.1";
    private int serverPort = 9876;
    private String authToken = "NCAS-SECRET-TOKEN";
    private boolean streamMode = false;
    private boolean tlsEnabled = false; // Mặc định TẮT trong constructor để tương thích test (BẬT trong parse CLI)
    private final List<String> serverFingerprints = new ArrayList<>();
    private String oneShotCommand = null;
    private final List<String> commandArgs = new ArrayList<>();

    public ConsoleConfig() {}

    public static ConsoleConfig parse(String[] args) {
        ConsoleConfig config = new ConsoleConfig();
        config.setTlsEnabled(true); // Mặc định BẬT TLS ở CLI/production (ERRATA §E2.1)
        if (args == null) return config;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            if ("--server".equalsIgnoreCase(arg) || "-s".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setServerHost(args[++i]);
                }
            } else if ("--port".equalsIgnoreCase(arg) || "-p".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setServerPort(Integer.parseInt(args[++i]));
                }
            } else if ("--token".equalsIgnoreCase(arg) || "-t".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    config.setAuthToken(args[++i]);
                }
            } else if ("--stream".equalsIgnoreCase(arg) || "-w".equalsIgnoreCase(arg) || "--watch".equalsIgnoreCase(arg)) {
                config.setStreamMode(true);
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
            } else if (!arg.startsWith("-")) {
                // Lệnh one-shot kèm tham số (ví dụ: list-agents, check 1, server-info)
                if (config.getOneShotCommand() == null) {
                    config.setOneShotCommand(arg);
                } else {
                    config.getCommandArgs().add(arg);
                }
            }
        }
        return config;
    }

    // --- Getters & Setters ---
    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }

    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public boolean isStreamMode() { return streamMode; }
    public void setStreamMode(boolean streamMode) { this.streamMode = streamMode; }

    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }

    public List<String> getServerFingerprints() { return java.util.Collections.unmodifiableList(serverFingerprints); }
    public void addServerFingerprint(String fingerprint) {
        if (fingerprint != null && !fingerprint.trim().isEmpty()) {
            this.serverFingerprints.add(fingerprint.trim());
        }
    }
    public void setServerFingerprints(List<String> fingerprints) {
        this.serverFingerprints.clear();
        if (fingerprints != null) {
            for (String fp : fingerprints) {
                addServerFingerprint(fp);
            }
        }
    }

    public String getOneShotCommand() { return oneShotCommand; }
    public void setOneShotCommand(String oneShotCommand) { this.oneShotCommand = oneShotCommand; }

    public List<String> getCommandArgs() { return commandArgs; }

    @Override
    public String toString() {
        return String.format("ConsoleConfig[server=%s:%d, stream=%b, oneShot=%s]",
                serverHost, serverPort, streamMode, oneShotCommand != null ? oneShotCommand : "interactive");
    }
}

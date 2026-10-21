package com.networkalert.scanner;

/**
 * Kết quả kiểm tra của một cổng mạng (Port Scan Result)
 */
public class PortScanResult {
    private final int port;
    private final boolean open;
    private final long latencyMs;
    private final String serviceName;

    public PortScanResult(int port, boolean open, long latencyMs, String serviceName) {
        this.port = port;
        this.open = open;
        this.latencyMs = latencyMs;
        this.serviceName = serviceName;
    }

    public int getPort() {
        return port;
    }

    public boolean isOpen() {
        return open;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getServiceName() {
        return serviceName;
    }

    @Override
    public String toString() {
        return String.format("Cổng %d (%s): %s [%d ms]", port, serviceName, open ? "MỞ" : "ĐÓNG", latencyMs);
    }
}

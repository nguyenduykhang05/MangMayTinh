package com.networkalert.model;

/**
 * Đại diện cho 1 thiết bị thực tế được tìm thấy trong mạng LAN
 */
public class LanDevice {
    private String ip;
    private String mac = "N/A";
    private String hostname = "Chưa rõ";
    private long latencyMs = -1;
    private String deviceType = "Thiết bị mạng";
    private boolean isGateway = false;
    private boolean isCurrentDevice = false;
    private String openPorts = "";

    public LanDevice() {}

    public LanDevice(String ip, String mac, String hostname, long latencyMs, String deviceType, boolean isGateway, boolean isCurrentDevice) {
        this.ip = ip;
        this.mac = mac;
        this.hostname = hostname;
        this.latencyMs = latencyMs;
        this.deviceType = deviceType;
        this.isGateway = isGateway;
        this.isCurrentDevice = isCurrentDevice;
    }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getMac() { return mac; }
    public void setMac(String mac) { this.mac = mac; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public boolean isGateway() { return isGateway; }
    public void setGateway(boolean gateway) { isGateway = gateway; }

    public boolean isCurrentDevice() { return isCurrentDevice; }
    public void setCurrentDevice(boolean currentDevice) { isCurrentDevice = currentDevice; }

    public String getOpenPorts() { return openPorts; }
    public void setOpenPorts(String openPorts) { this.openPorts = openPorts; }
}

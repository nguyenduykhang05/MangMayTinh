package com.networkalert.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Thông tin môi trường mạng thật của máy tính đang chạy hệ thống NCAS
 */
public class RealNetworkInfo {
    private String interfaceName = "Đang phát hiện...";
    private String interfaceType = "Wi-Fi / Ethernet";
    private String macAddress = "N/A";
    private String localIp = "127.0.0.1";
    private String subnetMask = "255.255.255.0";
    private int networkPrefix = 24;
    private String gatewayIp = "192.168.1.1";
    private long gatewayLatencyMs = -1;
    private String gatewayStatus = "UNKNOWN"; // ONLINE, OFFLINE, WARNING
    private String publicIp = "Đang nạp...";
    private List<String> dnsServers = new ArrayList<>();
    private long dnsLatencyMs = -1;
    private String internetStatus = "CONNECTED"; // CONNECTED, DEGRADED, DISCONNECTED
    private long lastUpdated = System.currentTimeMillis();

    public RealNetworkInfo() {}

    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String interfaceName) { this.interfaceName = interfaceName; }

    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getLocalIp() { return localIp; }
    public void setLocalIp(String localIp) { this.localIp = localIp; }

    public String getSubnetMask() { return subnetMask; }
    public void setSubnetMask(String subnetMask) { this.subnetMask = subnetMask; }

    public int getNetworkPrefix() { return networkPrefix; }
    public void setNetworkPrefix(int networkPrefix) { this.networkPrefix = networkPrefix; }

    public String getGatewayIp() { return gatewayIp; }
    public void setGatewayIp(String gatewayIp) { this.gatewayIp = gatewayIp; }

    public long getGatewayLatencyMs() { return gatewayLatencyMs; }
    public void setGatewayLatencyMs(long gatewayLatencyMs) { this.gatewayLatencyMs = gatewayLatencyMs; }

    public String getGatewayStatus() { return gatewayStatus; }
    public void setGatewayStatus(String gatewayStatus) { this.gatewayStatus = gatewayStatus; }

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }

    public List<String> getDnsServers() { return dnsServers; }
    public void setDnsServers(List<String> dnsServers) { this.dnsServers = dnsServers; }

    public long getDnsLatencyMs() { return dnsLatencyMs; }
    public void setDnsLatencyMs(long dnsLatencyMs) { this.dnsLatencyMs = dnsLatencyMs; }

    public String getInternetStatus() { return internetStatus; }
    public void setInternetStatus(String internetStatus) { this.internetStatus = internetStatus; }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
}

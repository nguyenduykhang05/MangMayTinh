package com.networkalert.protocol.messages;

import java.util.List;

/**
 * Thông điệp đăng ký danh tính từ Agent lên Server (MSG_REGISTER_REQ - 0x0001)
 */
public class RegisterRequest {
    private String agentId;
    private String hostname;
    private String os;
    private String localIp;
    private String macAddress;
    private String authToken;
    private List<String> supportedProtocols;
    private String version;

    // Các trường mở rộng NCAS-P v1.1 (ERRATA §E3.6)
    private String agentName;
    private String agentVersion;
    private String protoMax;
    private List<String> capabilities;
    private String location;
    private Long clockMs;
    private Long streamId;

    public RegisterRequest() {}

    public RegisterRequest(String agentId, String hostname, String os, String localIp,
                           String macAddress, String authToken, List<String> supportedProtocols, String version) {
        this.agentId = agentId;
        this.hostname = hostname;
        this.os = os;
        this.localIp = localIp;
        this.macAddress = macAddress;
        this.authToken = authToken;
        this.supportedProtocols = supportedProtocols;
        this.version = version;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }

    public String getLocalIp() { return localIp; }
    public void setLocalIp(String localIp) { this.localIp = localIp; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public List<String> getSupportedProtocols() { return supportedProtocols; }
    public void setSupportedProtocols(List<String> supportedProtocols) { this.supportedProtocols = supportedProtocols; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public String getProtoMax() { return protoMax; }
    public void setProtoMax(String protoMax) { this.protoMax = protoMax; }

    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public Long getClockMs() { return clockMs; }
    public void setClockMs(Long clockMs) { this.clockMs = clockMs; }

    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }

    @Override
    public String toString() {
        return String.format("RegisterRequest[id=%s, host=%s, ip=%s, os=%s]", agentId, hostname, localIp, os);
    }
}

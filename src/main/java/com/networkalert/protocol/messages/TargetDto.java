package com.networkalert.protocol.messages;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;

/**
 * Đối tượng truyền dữ liệu (DTO) của một mục tiêu giám sát mạng giữa Server và Agent
 */
public class TargetDto {
    private int id;
    private String name;
    private String host;
    private int port;
    private String protocol;
    private int intervalSeconds;
    private int timeoutMs;
    private long warningThresholdMs;
    private boolean enabled;
    private String scope;
    private String group;
    private String tags;

    public TargetDto() {}

    public TargetDto(int id, String name, String host, int port, String protocol,
                     int intervalSeconds, int timeoutMs, long warningThresholdMs, boolean enabled) {
        this(id, name, host, port, protocol, intervalSeconds, timeoutMs, warningThresholdMs, enabled, "MULTI", "Default", "");
    }

    public TargetDto(int id, String name, String host, int port, String protocol,
                     int intervalSeconds, int timeoutMs, long warningThresholdMs, boolean enabled,
                     String scope, String group, String tags) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.protocol = protocol;
        this.intervalSeconds = intervalSeconds;
        this.timeoutMs = timeoutMs;
        this.warningThresholdMs = warningThresholdMs;
        this.enabled = enabled;
        this.scope = scope != null ? scope : "MULTI";
        this.group = group != null ? group : "Default";
        this.tags = tags != null ? tags : "";
    }

    public static TargetDto fromServerTarget(ServerTarget t) {
        return new TargetDto(
                t.getId(),
                t.getName(),
                t.getHost(),
                t.getPort(),
                t.getProtocol() != null ? t.getProtocol().name() : "TCP",
                t.getIntervalSeconds(),
                t.getTimeoutMs(),
                t.getWarningThresholdMs(),
                t.isEnabled(),
                t.getScope() != null ? t.getScope().name() : "MULTI",
                t.getGroup(),
                t.getTags()
        );
    }

    public ServerTarget toServerTarget() {
        ProtocolType proto = ProtocolType.fromString(protocol);
        ServerTarget target = new ServerTarget(
                id,
                name,
                host,
                port,
                proto,
                intervalSeconds,
                timeoutMs,
                warningThresholdMs,
                enabled
        );
        target.setScope(com.networkalert.model.TargetScope.fromString(scope));
        if (group != null) target.setGroup(group);
        if (tags != null) target.setTags(tags);
        return target;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public long getWarningThresholdMs() { return warningThresholdMs; }
    public void setWarningThresholdMs(long warningThresholdMs) { this.warningThresholdMs = warningThresholdMs; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    @Override
    public String toString() {
        return String.format("TargetDto[#%d '%s' %s:%d proto=%s]", id, name, host, port, protocol);
    }
}

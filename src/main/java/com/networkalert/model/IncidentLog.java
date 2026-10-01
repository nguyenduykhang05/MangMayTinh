package com.networkalert.model;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Bản ghi sự cố mất kết nối hoặc khôi phục
 */
public class IncidentLog {
    private int id;
    private int targetId;
    private String targetName;
    private String host;
    private int port;
    private ProtocolType protocol;
    private String eventType; // 'OFFLINE', 'RECOVERED', 'HIGH_LATENCY'
    private LocalDateTime eventTime;
    private Integer durationSeconds;
    private String message;
    private boolean acknowledged;

    public IncidentLog() {
        this(Clock.systemDefaultZone());
    }

    public IncidentLog(Clock clock) {
        this.eventTime = LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());
    }

    public IncidentLog(int targetId, String targetName, String host, int port, ProtocolType protocol,
                       String eventType, String message) {
        this(targetId, targetName, host, port, protocol, eventType, message, Clock.systemDefaultZone());
    }

    public IncidentLog(int targetId, String targetName, String host, int port, ProtocolType protocol,
                       String eventType, String message, Clock clock) {
        this(clock);
        this.targetId = targetId;
        this.targetName = targetName;
        this.host = host;
        this.port = port;
        this.protocol = protocol;
        this.eventType = eventType;
        this.message = message;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getTargetId() { return targetId; }
    public void setTargetId(int targetId) { this.targetId = targetId; }

    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public ProtocolType getProtocol() { return protocol; }
    public void setProtocol(ProtocolType protocol) { this.protocol = protocol; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }
}

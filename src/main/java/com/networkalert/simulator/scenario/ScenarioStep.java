package com.networkalert.simulator.scenario;

/**
 * Đại diện cho một bước diễn tiến trong kịch bản mô phỏng mạng tự động theo ERRATA §E14.1.
 */
public class ScenarioStep {
    private long timeOffsetSeconds;
    private String name;
    private String description;
    private Boolean tcpRunning;
    private Integer tcpDelayMs;
    private Boolean httpRunning;
    private Integer httpStatusCode;
    private Integer httpDelayMs;
    private Boolean udpRunning;

    public ScenarioStep() {}

    public ScenarioStep(long timeOffsetSeconds, String name, String description,
                        Boolean tcpRunning, Integer tcpDelayMs,
                        Boolean httpRunning, Integer httpStatusCode, Integer httpDelayMs,
                        Boolean udpRunning) {
        this.timeOffsetSeconds = timeOffsetSeconds;
        this.name = name;
        this.description = description;
        this.tcpRunning = tcpRunning;
        this.tcpDelayMs = tcpDelayMs;
        this.httpRunning = httpRunning;
        this.httpStatusCode = httpStatusCode;
        this.httpDelayMs = httpDelayMs;
        this.udpRunning = udpRunning;
    }

    public long getTimeOffsetSeconds() { return timeOffsetSeconds; }
    public void setTimeOffsetSeconds(long timeOffsetSeconds) { this.timeOffsetSeconds = timeOffsetSeconds; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getTcpRunning() { return tcpRunning; }
    public void setTcpRunning(Boolean tcpRunning) { this.tcpRunning = tcpRunning; }

    public Integer getTcpDelayMs() { return tcpDelayMs; }
    public void setTcpDelayMs(Integer tcpDelayMs) { this.tcpDelayMs = tcpDelayMs; }

    public Boolean getHttpRunning() { return httpRunning; }
    public void setHttpRunning(Boolean httpRunning) { this.httpRunning = httpRunning; }

    public Integer getHttpStatusCode() { return httpStatusCode; }
    public void setHttpStatusCode(Integer httpStatusCode) { this.httpStatusCode = httpStatusCode; }

    public Integer getHttpDelayMs() { return httpDelayMs; }
    public void setHttpDelayMs(Integer httpDelayMs) { this.httpDelayMs = httpDelayMs; }

    public Boolean getUdpRunning() { return udpRunning; }
    public void setUdpRunning(Boolean udpRunning) { this.udpRunning = udpRunning; }

    @Override
    public String toString() {
        return String.format("+%ds: %s (%s)", timeOffsetSeconds, name, description);
    }
}

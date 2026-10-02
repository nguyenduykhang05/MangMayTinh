package com.networkalert.protocol.messages;

/**
 * Thông điệp nhịp tim định kỳ từ Agent gửi Server (MSG_HEARTBEAT_PING - 0x0003)
 */
public class HeartbeatPing {
    private String agentId;
    private long timestamp;
    private int activeTasks;
    private double cpuUsagePercent;
    private long freeMemoryMb;

    public HeartbeatPing() {}

    public HeartbeatPing(String agentId, long timestamp, int activeTasks, double cpuUsagePercent, long freeMemoryMb) {
        this.agentId = agentId;
        this.timestamp = timestamp;
        this.activeTasks = activeTasks;
        this.cpuUsagePercent = cpuUsagePercent;
        this.freeMemoryMb = freeMemoryMb;
    }

    public static HeartbeatPing now(String agentId, int activeTasks) {
        long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        return new HeartbeatPing(agentId, System.currentTimeMillis(), activeTasks, 0.0, freeMem);
    }

    // Các trường mở rộng NCAS-P v1.1 (ERRATA §E3.6)
    private Long hbSeq;
    private Long clockMs;
    private Long clockOffsetMs;
    private Long clockDelayMs;
    private Integer spoolDepth;
    private Long spoolBytes;
    private Integer activeProbes;
    private Long droppedResults;
    private Long skippedProbes;
    private java.util.List<StreamProgress> streams;
    private java.util.List<DataGap> gaps;

    public Long getHbSeq() { return hbSeq; }
    public void setHbSeq(Long hbSeq) { this.hbSeq = hbSeq; }

    public Long getClockMs() { return clockMs; }
    public void setClockMs(Long clockMs) { this.clockMs = clockMs; }

    public Long getClockOffsetMs() { return clockOffsetMs; }
    public void setClockOffsetMs(Long clockOffsetMs) { this.clockOffsetMs = clockOffsetMs; }

    public Long getClockDelayMs() { return clockDelayMs; }
    public void setClockDelayMs(Long clockDelayMs) { this.clockDelayMs = clockDelayMs; }

    public Integer getSpoolDepth() { return spoolDepth; }
    public void setSpoolDepth(Integer spoolDepth) { this.spoolDepth = spoolDepth; }

    public Long getSpoolBytes() { return spoolBytes; }
    public void setSpoolBytes(Long spoolBytes) { this.spoolBytes = spoolBytes; }

    public Integer getActiveProbes() { return activeProbes; }
    public void setActiveProbes(Integer activeProbes) { this.activeProbes = activeProbes; }

    public Long getDroppedResults() { return droppedResults; }
    public void setDroppedResults(Long droppedResults) { this.droppedResults = droppedResults; }

    public Long getSkippedProbes() { return skippedProbes; }
    public void setSkippedProbes(Long skippedProbes) { this.skippedProbes = skippedProbes; }

    public java.util.List<StreamProgress> getStreams() { return streams; }
    public void setStreams(java.util.List<StreamProgress> streams) { this.streams = streams; }

    public java.util.List<DataGap> getGaps() { return gaps; }
    public void setGaps(java.util.List<DataGap> gaps) { this.gaps = gaps; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getActiveTasks() { return activeTasks; }
    public void setActiveTasks(int activeTasks) { this.activeTasks = activeTasks; }

    public double getCpuUsagePercent() { return cpuUsagePercent; }
    public void setCpuUsagePercent(double cpuUsagePercent) { this.cpuUsagePercent = cpuUsagePercent; }

    public long getFreeMemoryMb() { return freeMemoryMb; }
    public void setFreeMemoryMb(long freeMemoryMb) { this.freeMemoryMb = freeMemoryMb; }

    @Override
    public String toString() {
        return String.format("HeartbeatPing[agent=%s, time=%d, tasks=%d, freeMem=%dMB]", agentId, timestamp, activeTasks, freeMemoryMb);
    }
}

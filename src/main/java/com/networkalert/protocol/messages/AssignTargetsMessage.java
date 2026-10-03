package com.networkalert.protocol.messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Thông điệp phân công danh sách mục tiêu đo kiểm từ Server xuống Agent (MSG_ASSIGN_TARGETS - 0x0005)
 */
public class AssignTargetsMessage {
    private List<TargetDto> targets = new ArrayList<>();
    private long timestamp;

    public AssignTargetsMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public AssignTargetsMessage(List<TargetDto> targets) {
        this.targets = targets != null ? targets : new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
    }

    public List<TargetDto> getTargets() { return targets; }
    public void setTargets(List<TargetDto> targets) { this.targets = targets; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return String.format("AssignTargetsMessage[count=%d, time=%d]", targets != null ? targets.size() : 0, timestamp);
    }
}

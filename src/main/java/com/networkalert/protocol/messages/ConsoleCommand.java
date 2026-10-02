package com.networkalert.protocol.messages;

import java.util.HashMap;
import java.util.Map;

/**
 * Lệnh quản trị từ Console gửi lên Server (MSG_CONSOLE_COMMAND - 0x000B)
 */
public class ConsoleCommand {
    private String command; // list-agents, list-targets, check-now, add-target, remove-target, pause, resume...
    private Map<String, String> params = new HashMap<>();
    private long timestamp;

    public ConsoleCommand() {
        this.timestamp = System.currentTimeMillis();
    }

    public ConsoleCommand(String command) {
        this.command = command;
        this.timestamp = System.currentTimeMillis();
    }

    public ConsoleCommand(String command, Map<String, String> params) {
        this.command = command;
        this.params = (params != null) ? params : new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) { this.params = params; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return String.format("ConsoleCommand[cmd='%s', params=%s]", command, params);
    }
}

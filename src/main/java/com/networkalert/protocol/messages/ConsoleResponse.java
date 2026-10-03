package com.networkalert.protocol.messages;

/**
 * Phản hồi thực thi lệnh quản trị từ Server gửi về cho Console (MSG_CONSOLE_COMMAND_RESP - 0x000C)
 */
public class ConsoleResponse {
    private boolean success;
    private String message;
    private String dataJson; // Dữ liệu trả về (danh sách agents, targets, metric...) dạng JSON
    private long executionTimeMs;

    public ConsoleResponse() {}

    public ConsoleResponse(boolean success, String message, String dataJson, long executionTimeMs) {
        this.success = success;
        this.message = message;
        this.dataJson = dataJson;
        this.executionTimeMs = executionTimeMs;
    }

    public static ConsoleResponse ok(String message) {
        return new ConsoleResponse(true, message, null, 0);
    }

    public static ConsoleResponse ok(String message, String dataJson) {
        return new ConsoleResponse(true, message, dataJson, 0);
    }

    public static ConsoleResponse error(String message) {
        return new ConsoleResponse(false, message, null, 0);
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    @Override
    public String toString() {
        return String.format("ConsoleResponse[success=%b, msg='%s']", success, message);
    }
}

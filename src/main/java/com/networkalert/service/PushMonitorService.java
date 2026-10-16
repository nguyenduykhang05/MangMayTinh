package com.networkalert.service;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerTarget;
import com.networkalert.util.LoggerUtil;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dịch vụ quản lý các mục tiêu Push Monitor (Heartbeat / Dead Man's Snitch)
 * Cho phép các tiến trình, backup cronjob đẩy nhịp tim về máy chủ NCAS qua HTTP POST /api/push/{token}
 * Tích hợp Rate Limiting chống spam/tấn công từ chối dịch vụ.
 */
public class PushMonitorService {
    private static final PushMonitorService INSTANCE = new PushMonitorService();

    public static PushMonitorService getInstance() {
        return INSTANCE;
    }

    private final Map<String, Long> lastPushRequestTimes = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_WINDOW_MS = 1000L; // Tối đa 1 request mỗi giây cho mỗi token
    private volatile Clock clock = Clock.systemUTC();

    public PushMonitorService() {}

    public PushMonitorService(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public Clock getClock() {
        return clock;
    }

    /**
     * Sinh mã token ngẫu nhiên cho một Push Monitor mới
     */
    public String generatePushToken() {
        return "push_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Xử lý nhận tín hiệu nhịp tim từ thiết bị bên ngoài
     *
     * @param token    Mã token riêng biệt của Push Monitor
     * @param status   Trạng thái ("up" / "ok" / "down")
     * @param msg      Thông điệp kèm theo (nếu có)
     * @param pingMs   Độ trễ đo lường của thiết bị
     * @param target   Mục tiêu ServerTarget tương ứng
     * @return Kết quả xử lý chuỗi JSON
     */
    public PushResult handlePush(String token, String status, String msg, long pingMs, ServerTarget target) {
        if (token == null || token.trim().isEmpty()) {
            return new PushResult(400, "{\"ok\":false,\"error\":\"Thiếu push token\"}");
        }

        // 1. Kiểm tra Rate Limiting
        long now = clock.millis();
        Long lastReq = lastPushRequestTimes.get(token);
        if (lastReq != null && (now - lastReq) < RATE_LIMIT_WINDOW_MS) {
            return new PushResult(429, "{\"ok\":false,\"error\":\"Rate limit exceeded. Tần suất gửi quá nhanh, vui lòng chờ 1 giây!\"}");
        }
        lastPushRequestTimes.put(token, now);

        if (target == null) {
            return new PushResult(404, "{\"ok\":false,\"error\":\"Không tìm thấy mục tiêu nào gắn với push token này!\"}");
        }

        // 2. Cập nhật nhịp tim cho mục tiêu
        target.setLastPushTimestamp(now);
        boolean isUp = status == null || status.trim().isEmpty()
                || "up".equalsIgnoreCase(status)
                || "ok".equalsIgnoreCase(status)
                || "online".equalsIgnoreCase(status);

        String detailMsg = (msg != null && !msg.trim().isEmpty()) ? msg.trim() : "Push heartbeat nhận thành công";
        long effectivePing = Math.max(0, pingMs);

        CheckResult result = isUp
                ? CheckResult.success(effectivePing, detailMsg)
                : CheckResult.failure("Push báo sự cố: " + detailMsg);

        target.recordCheckResult(result, 300);

        LoggerUtil.info(String.format("📡 [Push Monitor] Đã nhận tín hiệu từ '%s' (Token: %s, Trạng thái: %s, RTT: %d ms, Msg: %s)",
                target.getName(), token, (isUp ? "UP" : "DOWN"), effectivePing, detailMsg));

        return new PushResult(200, String.format("{\"ok\":true,\"message\":\"%s\",\"target\":\"%s\",\"status\":\"%s\"}",
                "Đã nhận nhịp tim Push", target.getName(), (isUp ? "UP" : "DOWN")));
    }

    public static class PushResult {
        private final int statusCode;
        private final String jsonResponse;

        public PushResult(int statusCode, String jsonResponse) {
            this.statusCode = statusCode;
            this.jsonResponse = jsonResponse;
        }

        public int getStatusCode() { return statusCode; }
        public String getJsonResponse() { return jsonResponse; }
    }
}

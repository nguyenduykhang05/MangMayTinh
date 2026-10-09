package com.networkalert.service.checker;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerTarget;

import java.time.format.DateTimeFormatter;

/**
 * Bộ kiểm tra thụ động cho Push Monitor (Dead Man's Snitch / Heartbeat Push)
 * Thiết bị bên ngoài tự động đẩy HTTP POST định kỳ tới máy chủ NCAS.
 * Nếu quá chu kỳ (Interval + Grace Period) mà không nhận được dữ liệu, mục tiêu sẽ bị đánh dấu OFFLINE.
 */
public class PushChecker implements IProtocolChecker {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public CheckResult check(ServerTarget target) {
        if (target == null) {
            return CheckResult.failure("Mục tiêu không tồn tại");
        }

        long lastPush = target.getLastPushTimestamp();
        if (lastPush <= 0) {
            return CheckResult.failure("Đang chờ nhịp tim Push đầu tiên (Token: " +
                    (target.getPushToken() != null ? target.getPushToken() : "Chưa cấp") + ")");
        }

        long elapsedSec = (System.currentTimeMillis() - lastPush) / 1000;
        long maxGraceSec = target.getIntervalSeconds() + target.getPushGraceSeconds();

        if (elapsedSec > maxGraceSec) {
            return CheckResult.failure(String.format(
                    "Quá hạn nhịp tim Push: %d giây không có tín hiệu (Hạn chờ tối đa %d giây)",
                    elapsedSec, maxGraceSec
            ));
        }

        long lat = target.getCurrentLatency() >= 0 ? target.getCurrentLatency() : 0;
        return CheckResult.success(lat, String.format("Nhịp tim Push OK (Tín hiệu gần nhất cách %d giây)", elapsedSec));
    }
}

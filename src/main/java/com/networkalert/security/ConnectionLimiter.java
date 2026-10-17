package com.networkalert.security;

import com.networkalert.util.LoggerUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kiểm soát và giới hạn số lượng kết nối TCP chưa xác thực danh tính (Pre-Authentication Limiter).
 * <p>
 * Thực thi các yêu cầu chống tấn công lạm dụng và cạn kiệt tài nguyên theo ERRATA §E2.5:
 * <ul>
 *   <li>Tối đa 8 kết nối chưa xác thực trên mỗi địa chỉ IP nguồn</li>
 *   <li>Tối đa 64 kết nối chưa xác thực trên toàn hệ thống Server</li>
 *   <li>Tuyệt đối KHÔNG khóa vĩnh viễn IP: Tránh gây DoS cho các Agent đứng sau cùng cổng NAT</li>
 * </ul>
 * </p>
 */
public class ConnectionLimiter {

    public static final int DEFAULT_MAX_PRE_AUTH_PER_IP = 8;
    public static final int DEFAULT_MAX_PRE_AUTH_GLOBAL = 64;

    private final int maxPerIp;
    private final int maxGlobal;

    private final AtomicInteger globalPreAuthCount = new AtomicInteger(0);
    private final Map<String, AtomicInteger> ipPreAuthCounts = new ConcurrentHashMap<>();

    public ConnectionLimiter() {
        this(DEFAULT_MAX_PRE_AUTH_PER_IP, DEFAULT_MAX_PRE_AUTH_GLOBAL);
    }

    public ConnectionLimiter(int maxPerIp, int maxGlobal) {
        this.maxPerIp = maxPerIp > 0 ? maxPerIp : DEFAULT_MAX_PRE_AUTH_PER_IP;
        this.maxGlobal = maxGlobal > 0 ? maxGlobal : DEFAULT_MAX_PRE_AUTH_GLOBAL;
    }

    /**
     * Thử đăng ký giữ chỗ một kết nối mới chưa xác thực.
     *
     * @param remoteIp Địa chỉ IP nguồn của Client
     * @return true nếu được phép tiếp nhận; false nếu vượt trần giới hạn
     */
    public boolean tryAcquirePreAuth(String remoteIp) {
        String ipKey = normalizeIp(remoteIp);

        // 1. Kiểm tra trần toàn cục
        int currentGlobal = globalPreAuthCount.get();
        if (currentGlobal >= maxGlobal) {
            LoggerUtil.warn(String.format("🛡️ [ConnectionLimiter] Từ chối kết nối từ %s: Đạt trần kết nối chưa xác thực toàn cục (%d/%d)!",
                    ipKey, currentGlobal, maxGlobal));
            return false;
        }

        // 2. Tăng số đếm theo IP nguyên tử
        AtomicInteger ipCounter = ipPreAuthCounts.computeIfAbsent(ipKey, k -> new AtomicInteger(0));
        int currentIpCount = ipCounter.incrementAndGet();

        if (currentIpCount > maxPerIp) {
            // Vượt quá trần trên IP -> Giảm lại và từ chối
            ipCounter.decrementAndGet();
            LoggerUtil.warn(String.format("🛡️ [ConnectionLimiter] Từ chối kết nối từ %s: Đạt trần kết nối chưa xác thực trên IP (%d/%d)!",
                    ipKey, currentIpCount - 1, maxPerIp));
            return false;
        }

        // 3. Tăng trần toàn cục
        globalPreAuthCount.incrementAndGet();
        return true;
    }

    /**
     * Giải phóng một kết nối chưa xác thực (sau khi kết nối đã xác thực thành công hoặc bị ngắt/đóng socket).
     */
    public void releasePreAuth(String remoteIp) {
        String ipKey = normalizeIp(remoteIp);
        AtomicInteger ipCounter = ipPreAuthCounts.get(ipKey);
        if (ipCounter != null) {
            int remaining = ipCounter.decrementAndGet();
            if (remaining <= 0) {
                ipPreAuthCounts.remove(ipKey, ipCounter);
            }
        }
        globalPreAuthCount.updateAndGet(c -> Math.max(0, c - 1));
    }

    public int getGlobalPreAuthCount() {
        return globalPreAuthCount.get();
    }

    public int getPreAuthCountForIp(String remoteIp) {
        AtomicInteger counter = ipPreAuthCounts.get(normalizeIp(remoteIp));
        return counter != null ? counter.get() : 0;
    }

    public void reset() {
        globalPreAuthCount.set(0);
        ipPreAuthCounts.clear();
    }

    private static String normalizeIp(String remoteIp) {
        if (remoteIp == null || remoteIp.trim().isEmpty()) {
            return "unknown";
        }
        String clean = remoteIp.trim();
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        int colonIdx = clean.indexOf(':');
        if (colonIdx > 0 && clean.indexOf(']') == -1) {
            // IPv4 với port "1.2.3.4:1234"
            clean = clean.substring(0, colonIdx);
        }
        return clean;
    }
}

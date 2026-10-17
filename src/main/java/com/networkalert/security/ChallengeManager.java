package com.networkalert.security;

import com.networkalert.protocol.messages.AuthChallengeMessage;
import com.networkalert.util.LoggerUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý cơ chế thách thức - phản hồi (Challenge-Response) trong chế độ {@code --no-tls} (ERRATA §E2.3).
 * <p>
 * Đảm bảo:
 * <ul>
 *   <li>Nonce 32 bytes ngẫu nhiên từ {@link SecureRandom}</li>
 *   <li>Single-use: Mỗi Nonce chỉ được dùng đúng 1 lần duy nhất, bị hủy ngay khi kiểm tra</li>
 *   <li>TTL ngắn: Hết hạn sau 30 giây (30_000 ms)</li>
 *   <li>Chữ ký {@code HMAC-SHA256(H, nonce || agentId)}</li>
 *   <li>Chống triệt để tấn công Replay Attack và Timing Attack bằng {@link MessageDigest#isEqual(byte[], byte[])}</li>
 * </ul>
 * </p>
 */
public class ChallengeManager {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    public static final long DEFAULT_TTL_MS = 30_000L; // 30 giây

    public static class ActiveChallenge {
        private final String agentId;
        private final String nonce;
        private final String saltHex;
        private final long createdAtMs;
        private final long expiresAtMs;

        public ActiveChallenge(String agentId, String nonce, String saltHex, long createdAtMs, long expiresAtMs) {
            this.agentId = agentId;
            this.nonce = nonce;
            this.saltHex = saltHex;
            this.createdAtMs = createdAtMs;
            this.expiresAtMs = expiresAtMs;
        }

        public String getAgentId() { return agentId; }
        public String getNonce() { return nonce; }
        public String getSaltHex() { return saltHex; }
        public long getCreatedAtMs() { return createdAtMs; }
        public long getExpiresAtMs() { return expiresAtMs; }
    }

    private final Clock clock;
    private final Map<String, ActiveChallenge> activeChallenges = new ConcurrentHashMap<>();

    public ChallengeManager() {
        this(Clock.systemUTC());
    }

    public ChallengeManager(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Sinh một thách thức ngẫu nhiên cho Agent chuẩn bị xác thực.
     */
    public AuthChallengeMessage createChallenge(String agentId, String saltHex) {
        return createChallenge(agentId, saltHex, DEFAULT_TTL_MS);
    }

    public AuthChallengeMessage createChallenge(String agentId, String saltHex, long ttlMs) {
        if (agentId == null || agentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent ID không được để trống khi sinh challenge!");
        }
        cleanExpiredChallenges();

        byte[] nonceBytes = new byte[32];
        SECURE_RANDOM.nextBytes(nonceBytes);
        String nonce = AgentTokenManager.bytesToHex(nonceBytes);

        long now = clock.millis();
        long expiresAt = now + ttlMs;

        ActiveChallenge challenge = new ActiveChallenge(agentId.trim(), nonce, saltHex, now, expiresAt);
        activeChallenges.put(nonce, challenge);

        return new AuthChallengeMessage(agentId.trim(), nonce, saltHex, now, expiresAt);
    }

    /**
     * Xác thực phản hồi Challenge từ Agent.
     * <p>
     * RÀNG BUỘC SINGLE-USE: Nonce bị thu hồi ngay lập tức khỏi bộ nhớ dù hợp lệ hay không hợp lệ.
     * </p>
     *
     * @param agentId Định danh Agent
     * @param nonce Chuỗi Nonce dùng 1 lần
     * @param responseHmacHex Chữ ký HMAC nhận được từ Agent
     * @param storedHashHex Giá trị băm H = SHA-256(salt || token) đã lưu trên Server
     * @return true nếu chữ ký hợp lệ và nonce chưa từng được dùng / chưa hết hạn
     */
    public boolean verifyResponse(String agentId, String nonce, String responseHmacHex, String storedHashHex) {
        if (agentId == null || nonce == null || responseHmacHex == null || storedHashHex == null) {
            return false;
        }

        // 1. Rút challenge ra khỏi danh sách và xóa ngay lập tức (Bất biến Single-Use chống Replay)
        ActiveChallenge challenge = activeChallenges.remove(nonce);
        if (challenge == null) {
            LoggerUtil.warn(String.format("🛡️ [ChallengeManager] Nonce '%s' không tồn tại hoặc đã bị sử dụng lại (Replay Attack)!", nonce));
            return false;
        }

        // 2. Kiểm tra thời hạn hiệu lực (TTL 30s)
        long now = clock.millis();
        if (now > challenge.getExpiresAtMs()) {
            LoggerUtil.warn(String.format("🛡️ [ChallengeManager] Nonce '%s' của Agent '%s' đã hết hạn (%d ms > %d ms)!",
                    nonce, agentId, now, challenge.getExpiresAtMs()));
            return false;
        }

        // 3. Kiểm tra Agent ID sở hữu Nonce
        if (!challenge.getAgentId().equalsIgnoreCase(agentId.trim())) {
            LoggerUtil.warn(String.format("🛡️ [ChallengeManager] Nonce '%s' không thuộc về Agent '%s' (thuộc về '%s')!",
                    nonce, agentId, challenge.getAgentId()));
            return false;
        }

        // 4. Tính toán HMAC mong đợi và so khớp an toàn
        try {
            byte[] hKey = AgentTokenManager.hexToBytes(storedHashHex);
            byte[] expectedHmac = computeHmacBytes(hKey, nonce, agentId.trim());
            byte[] actualHmac = AgentTokenManager.hexToBytes(responseHmacHex);

            return MessageDigest.isEqual(expectedHmac, actualHmac);
        } catch (Exception e) {
            LoggerUtil.warn("[ChallengeManager] Lỗi kiểm tra HMAC chữ ký: " + e.getMessage());
            return false;
        }
    }

    /**
     * Tính toán chữ ký HMAC-SHA256(H, nonce || agentId).
     */
    public static byte[] computeHmacBytes(byte[] hKey, String nonce, String agentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(hKey, "HmacSHA256");
            mac.init(keySpec);

            String message = nonce + agentId;
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Lỗi khởi tạo HMAC-SHA256: " + e.getMessage(), e);
        }
    }

    /**
     * Tính toán chữ ký HMAC trả về chuỗi Hex phục vụ Agent tạo thông điệp AUTH_RESPONSE.
     */
    public static String computeResponseHmac(byte[] hKey, String nonce, String agentId) {
        byte[] hmac = computeHmacBytes(hKey, nonce, agentId);
        return AgentTokenManager.bytesToHex(hmac);
    }

    /**
     * Dọn dẹp các challenge đã hết hạn
     */
    public void cleanExpiredChallenges() {
        long now = clock.millis();
        activeChallenges.entrySet().removeIf(entry -> now > entry.getValue().getExpiresAtMs());
    }

    public int getActiveChallengeCount() {
        return activeChallenges.size();
    }
}

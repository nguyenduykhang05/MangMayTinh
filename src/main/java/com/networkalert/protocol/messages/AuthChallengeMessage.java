package com.networkalert.protocol.messages;

/**
 * Thông điệp thách thức xác thực từ Server gửi tới Agent (MSG_AUTH_CHALLENGE - 0x000D)
 * <p>
 * Áp dụng trong chế độ {@code --no-tls} (Challenge-Response) theo ERRATA §E2.3 và §E3.1.
 * Nonce là chuỗi ngẫu nhiên 32 bytes (64 ký tự hex) dùng một lần (single-use) với TTL 30 giây.
 * </p>
 */
public class AuthChallengeMessage {
    private String agentId;
    private String nonce;
    private String salt;
    private long timestamp;
    private long expiresAt;

    public AuthChallengeMessage() {}

    public AuthChallengeMessage(String agentId, String nonce, String salt, long timestamp, long expiresAt) {
        this.agentId = agentId;
        this.nonce = nonce;
        this.salt = salt;
        this.timestamp = timestamp;
        this.expiresAt = expiresAt;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    @Override
    public String toString() {
        return String.format("AuthChallengeMessage[agent=%s, nonce=%.8s..., salt=%.8s..., expiresAt=%d]",
                agentId, nonce != null ? nonce : "", salt != null ? salt : "", expiresAt);
    }
}

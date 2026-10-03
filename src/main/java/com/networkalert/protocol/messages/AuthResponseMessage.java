package com.networkalert.protocol.messages;

/**
 * Thông điệp phản hồi chữ ký xác thực từ Agent gửi về Server (MSG_AUTH_RESPONSE - 0x000E)
 * <p>
 * Áp dụng trong chế độ {@code --no-tls} theo ERRATA §E2.3 và §E3.1.
 * Agent tính {@code H = SHA-256(salt || token)}, sau đó tính chữ ký
 * {@code hmac = HMAC-SHA256(H, nonce || agentId)}.
 * </p>
 */
public class AuthResponseMessage {
    private String agentId;
    private String nonce;
    private String hmac;

    public AuthResponseMessage() {}

    public AuthResponseMessage(String agentId, String nonce, String hmac) {
        this.agentId = agentId;
        this.nonce = nonce;
        this.hmac = hmac;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public String getHmac() { return hmac; }
    public void setHmac(String hmac) { this.hmac = hmac; }

    @Override
    public String toString() {
        return String.format("AuthResponseMessage[agent=%s, nonce=%.8s..., hmac=%.8s...]",
                agentId, nonce != null ? nonce : "", hmac != null ? hmac : "");
    }
}

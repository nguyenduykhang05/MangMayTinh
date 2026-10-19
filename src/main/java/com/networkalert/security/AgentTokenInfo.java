package com.networkalert.security;

/**
 * Thông tin siêu dữ liệu công khai của Agent Token phục vụ liệt kê CLI (E2.2: token list).
 * <p>
 * Tuyệt đối không chứa token bản rõ hay giá trị băm mật khẩu.
 * </p>
 */
public class AgentTokenInfo {
    private final String agentId;
    private final String role;
    private final long createdAt;
    private final boolean revoked;
    private final long revokedAt;

    public AgentTokenInfo(String agentId, String role, long createdAt, boolean revoked, long revokedAt) {
        this.agentId = agentId;
        this.role = role;
        this.createdAt = createdAt;
        this.revoked = revoked;
        this.revokedAt = revokedAt;
    }

    public String getAgentId() { return agentId; }
    public String getRole() { return role; }
    public long getCreatedAt() { return createdAt; }
    public boolean isRevoked() { return revoked; }
    public long getRevokedAt() { return revokedAt; }

    @Override
    public String toString() {
        return String.format("AgentTokenInfo[agentId=%s, role=%s, active=%s, createdAt=%d]",
                agentId, role, !revoked, createdAt);
    }
}

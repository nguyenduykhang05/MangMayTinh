package com.networkalert.security;

/**
 * Bản ghi lưu trữ token xác thực của Agent trên NCAS Server.
 * <p>
 * Tuyệt đối KHÔNG lưu token bản rõ; chỉ lưu Salt ngẫu nhiên 16 bytes và giá trị băm
 * SHA-256(salt || token) theo ERRATA §E2.2.
 * </p>
 */
public class AgentTokenEntry {
    private String agentId;
    private String saltHex;
    private String hashHex;
    private String role = "agent";
    private long createdAt;
    private boolean revoked = false;
    private long revokedAt = 0L;

    public AgentTokenEntry() {}

    public AgentTokenEntry(String agentId, String saltHex, String hashHex, String role, long createdAt) {
        this.agentId = agentId;
        this.saltHex = saltHex;
        this.hashHex = hashHex;
        this.role = role != null ? role : "agent";
        this.createdAt = createdAt;
        this.revoked = false;
        this.revokedAt = 0L;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getSaltHex() { return saltHex; }
    public void setSaltHex(String saltHex) { this.saltHex = saltHex; }

    public String getHashHex() { return hashHex; }
    public void setHashHex(String hashHex) { this.hashHex = hashHex; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public long getRevokedAt() { return revokedAt; }
    public void setRevokedAt(long revokedAt) { this.revokedAt = revokedAt; }

    public AgentTokenInfo toInfo() {
        return new AgentTokenInfo(agentId, role, createdAt, revoked, revokedAt);
    }
}

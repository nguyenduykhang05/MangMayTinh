package com.networkalert.repository;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;
import com.networkalert.util.LoggerUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tầng truy xuất dữ liệu máy chủ giám sát (ServerTarget Repository)
 */
public class TargetRepository {
    private final DatabaseManager dbManager;
    private final List<ServerTarget> memoryBackup = new ArrayList<>();

    public TargetRepository() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public List<ServerTarget> getAllTargets() {
        List<ServerTarget> targets = new ArrayList<>();
        if (dbManager.isConnected()) {
            String sql = "SELECT id, name, host, port, protocol, interval_seconds, timeout_ms, warning_threshold_ms, scope, assigned_stations, group_name, tags, parent_id, parent_agent, max_check_attempts, retry_interval, enabled FROM ServerTargets ORDER BY id ASC";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    long warningThreshold = 0;
                    try {
                        warningThreshold = rs.getLong("warning_threshold_ms");
                    } catch (Exception ignored) {}

                    String scopeStr = "MULTI";
                    try {
                        scopeStr = rs.getString("scope");
                    } catch (Exception ignored) {}

                    String groupName = "Default";
                    try {
                        groupName = rs.getString("group_name");
                    } catch (Exception ignored) {}

                    String tags = "";
                    try {
                        tags = rs.getString("tags");
                    } catch (Exception ignored) {}

                    ServerTarget t = new ServerTarget(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("host"),
                            rs.getInt("port"),
                            ProtocolType.fromString(rs.getString("protocol")),
                            rs.getInt("interval_seconds"),
                            rs.getInt("timeout_ms"),
                            warningThreshold,
                            com.networkalert.model.TargetScope.fromString(scopeStr),
                            groupName,
                            rs.getBoolean("enabled")
                    );
                    t.setTags(tags != null ? tags : "");
                    try {
                        String st = rs.getString("assigned_stations");
                        if (st != null && !st.trim().isEmpty()) {
                            t.setAssignedStations(java.util.Arrays.asList(st.split(",")));
                        }
                    } catch (Exception ignored) {}

                    try {
                        int pId = rs.getInt("parent_id");
                        if (!rs.wasNull()) t.setParentId(pId);
                    } catch (Exception ignored) {}

                    try {
                        String pAgent = rs.getString("parent_agent");
                        if (pAgent != null && !pAgent.trim().isEmpty()) t.setParentAgent(pAgent);
                    } catch (Exception ignored) {}

                    try {
                        int maxAttempts = rs.getInt("max_check_attempts");
                        if (!rs.wasNull() && maxAttempts > 0) t.setMaxCheckAttempts(maxAttempts);
                    } catch (Exception ignored) {}

                    try {
                        int retrySec = rs.getInt("retry_interval");
                        if (!rs.wasNull() && retrySec > 0) t.setRetryIntervalSeconds(retrySec);
                    } catch (Exception ignored) {}

                    try {
                        String kw = rs.getString("expected_keyword");
                        if (kw != null && !kw.trim().isEmpty()) t.setExpectedKeyword(kw);
                    } catch (Exception ignored) {}

                    try {
                        String pt = rs.getString("push_token");
                        if (pt != null && !pt.trim().isEmpty()) t.setPushToken(pt);
                    } catch (Exception ignored) {}

                    targets.add(t);
                }
            } catch (SQLException e) {
                // Fallback nếu bảng cũ chưa có các cột mới
                String fallbackSql = "SELECT id, name, host, port, protocol, interval_seconds, timeout_ms, warning_threshold_ms, enabled FROM ServerTargets ORDER BY id ASC";
                try (Connection conn = dbManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(fallbackSql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        targets.add(new ServerTarget(
                                rs.getInt("id"),
                                rs.getString("name"),
                                rs.getString("host"),
                                rs.getInt("port"),
                                ProtocolType.fromString(rs.getString("protocol")),
                                rs.getInt("interval_seconds"),
                                rs.getInt("timeout_ms"),
                                rs.getLong("warning_threshold_ms"),
                                rs.getBoolean("enabled")
                        ));
                    }
                } catch (SQLException ex) {
                    System.err.println("[TargetRepository] Lỗi đọc targets từ SQL Server: " + ex.getMessage());
                }
            }
        }

        String gatewayIp = com.networkalert.service.RealNetworkService.getInstance().getNetworkInfo().getGatewayIp();
        if (gatewayIp == null || gatewayIp.isEmpty()) gatewayIp = "192.168.1.1";

        if (targets.isEmpty()) {
            if (memoryBackup.isEmpty()) {
                // Tạo dữ liệu mặc định: Chu kỳ và ngưỡng độ trễ được tinh chỉnh chuẩn theo đặc thù mạng
                int id = 1;
                memoryBackup.add(new ServerTarget(id++, "Wi-Fi Router / Default Gateway", gatewayIp, 0, ProtocolType.ICMP, 3, 1000, 150, com.networkalert.model.TargetScope.AGENT, "Mạng nội bộ", true));
                memoryBackup.add(new ServerTarget(id++, "Google Public DNS", "8.8.8.8", 0, ProtocolType.ICMP, 15, 2000, 300, com.networkalert.model.TargetScope.MULTI, "Dịch vụ DNS", true));
                memoryBackup.add(new ServerTarget(id++, "Cloudflare DNS", "1.1.1.1", 53, ProtocolType.DNS, 15, 2000, 250, com.networkalert.model.TargetScope.MULTI, "Dịch vụ DNS", true));
                memoryBackup.add(new ServerTarget(id++, "Google Web HTTPS", "google.com", 443, ProtocolType.HTTPS, 20, 3500, 1000, com.networkalert.model.TargetScope.MULTI, "Web công cộng", true));
                memoryBackup.add(new ServerTarget(id++, "Cloudflare Global Web", "cloudflare.com", 443, ProtocolType.HTTPS, 20, 3500, 1000, com.networkalert.model.TargetScope.MULTI, "Web công cộng", true));
                memoryBackup.add(new ServerTarget(id++, "VnExpress Báo Mạng VN", "vnexpress.net", 443, ProtocolType.HTTPS, 20, 3500, 800, com.networkalert.model.TargetScope.MULTI, "Báo chí", true));
                memoryBackup.add(new ServerTarget(id++, "GitHub Global API", "api.github.com", 443, ProtocolType.HTTPS, 30, 4000, 1200, com.networkalert.model.TargetScope.MULTI, "Dịch vụ Cloud", true));

                // Các Mock Server chạy trên chính máy chủ Server -> gán Scope SERVER (Server đo)
                ServerTarget mockTcp = new ServerTarget(id++, "[Giả lập] Mock TCP Server", "127.0.0.1", 9000, ProtocolType.TCP, 3, 1000, 100, com.networkalert.model.TargetScope.SERVER, "Giả lập", true);
                mockTcp.setTags("mock,tcp");
                memoryBackup.add(mockTcp);

                ServerTarget mockHttp = new ServerTarget(id++, "[Giả lập] Mock HTTP Server", "127.0.0.1", 9002, ProtocolType.HTTP, 3, 1000, 100, com.networkalert.model.TargetScope.SERVER, "Giả lập", true);
                mockHttp.setTags("mock,http");
                memoryBackup.add(mockHttp);

                ServerTarget mockUdp = new ServerTarget(id++, "[Giả lập] Mock UDP Echo", "127.0.0.1", 9001, ProtocolType.UDP, 4, 1000, 100, com.networkalert.model.TargetScope.SERVER, "Giả lập", true);
                mockUdp.setTags("mock,udp");
                memoryBackup.add(mockUdp);
            }
            targets.addAll(memoryBackup);
        } else {
            // Nếu đọc từ DB mà chưa có Default Gateway, bổ sung vào đầu danh sách
            final String gw = gatewayIp;
            boolean hasGateway = targets.stream().anyMatch(t -> t.getHost().equalsIgnoreCase(gw) || t.getName().contains("Gateway") || t.getName().contains("Router"));
            if (!hasGateway) {
                targets.add(0, new ServerTarget(999, "Wi-Fi Router / Default Gateway", gw, 0, ProtocolType.ICMP, 3, 1000, 150, com.networkalert.model.TargetScope.AGENT, "Mạng nội bộ", true));
            }
            memoryBackup.clear();
            memoryBackup.addAll(targets);
        }

        // Tự động kiểm tra và sửa đổi (Auto-repair) các mục tiêu cũ vi phạm Scope MULTI vs IP riêng (§E11.1)
        for (ServerTarget t : targets) {
            com.networkalert.validation.TargetValidator.autoRepairScope(t);
        }

        return targets;
    }

    public boolean addTarget(ServerTarget target) {
        if (dbManager.isConnected()) {
            String sql = "INSERT INTO ServerTargets (name, host, port, protocol, interval_seconds, timeout_ms, warning_threshold_ms, scope, assigned_stations, group_name, tags, parent_id, parent_agent, max_check_attempts, retry_interval, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, target.getName());
                ps.setString(2, target.getHost());
                ps.setInt(3, target.getPort());
                ps.setString(4, target.getProtocol().name());
                ps.setInt(5, target.getIntervalSeconds());
                ps.setInt(6, target.getTimeoutMs());
                ps.setLong(7, target.getWarningThresholdMs());
                ps.setString(8, target.getScope() != null ? target.getScope().name() : "MULTI");
                ps.setString(9, target.getAssignedStations() != null ? String.join(",", target.getAssignedStations()) : null);
                ps.setString(10, target.getGroup() != null ? target.getGroup() : "Default");
                ps.setString(11, target.getTags() != null ? target.getTags() : "");
                if (target.getParentId() != null) ps.setInt(12, target.getParentId()); else ps.setNull(12, Types.INTEGER);
                ps.setString(13, target.getParentAgent());
                ps.setInt(14, target.getMaxCheckAttempts());
                ps.setInt(15, target.getRetryIntervalSeconds());
                ps.setBoolean(16, target.isEnabled());
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            target.setId(rs.getInt(1));
                        }
                    }
                    memoryBackup.add(target);
                    return true;
                }
            } catch (SQLException e) {
                // Thử fallback câu INSERT cũ nếu schema chưa update
                String fallbackSql = "INSERT INTO ServerTargets (name, host, port, protocol, interval_seconds, timeout_ms, warning_threshold_ms, scope, assigned_stations, group_name, tags, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (Connection conn = dbManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(fallbackSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, target.getName());
                    ps.setString(2, target.getHost());
                    ps.setInt(3, target.getPort());
                    ps.setString(4, target.getProtocol().name());
                    ps.setInt(5, target.getIntervalSeconds());
                    ps.setInt(6, target.getTimeoutMs());
                    ps.setLong(7, target.getWarningThresholdMs());
                    ps.setString(8, target.getScope() != null ? target.getScope().name() : "MULTI");
                    ps.setString(9, target.getAssignedStations() != null ? String.join(",", target.getAssignedStations()) : null);
                    ps.setString(10, target.getGroup() != null ? target.getGroup() : "Default");
                    ps.setString(11, target.getTags() != null ? target.getTags() : "");
                    ps.setBoolean(12, target.isEnabled());
                    if (ps.executeUpdate() > 0) {
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) target.setId(rs.getInt(1));
                        }
                        memoryBackup.add(target);
                        return true;
                    }
                } catch (SQLException ex) {
                    LoggerUtil.error("[TargetRepository] Lỗi thêm target vào SQL Server: " + ex.getMessage());
                }
            }
        }

        // In-memory fallback
        int nextId = memoryBackup.stream().mapToInt(ServerTarget::getId).max().orElse(0) + 1;
        target.setId(nextId);
        memoryBackup.add(target);
        return true;
    }

    public boolean updateTarget(ServerTarget target) {
        if (dbManager.isConnected()) {
            String sql = "UPDATE ServerTargets SET name=?, host=?, port=?, protocol=?, interval_seconds=?, timeout_ms=?, warning_threshold_ms=?, scope=?, assigned_stations=?, group_name=?, tags=?, parent_id=?, parent_agent=?, max_check_attempts=?, retry_interval=?, enabled=? WHERE id=?";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, target.getName());
                ps.setString(2, target.getHost());
                ps.setInt(3, target.getPort());
                ps.setString(4, target.getProtocol().name());
                ps.setInt(5, target.getIntervalSeconds());
                ps.setInt(6, target.getTimeoutMs());
                ps.setLong(7, target.getWarningThresholdMs());
                ps.setString(8, target.getScope() != null ? target.getScope().name() : "MULTI");
                ps.setString(9, target.getAssignedStations() != null ? String.join(",", target.getAssignedStations()) : null);
                ps.setString(10, target.getGroup() != null ? target.getGroup() : "Default");
                ps.setString(11, target.getTags() != null ? target.getTags() : "");
                if (target.getParentId() != null) ps.setInt(12, target.getParentId()); else ps.setNull(12, Types.INTEGER);
                ps.setString(13, target.getParentAgent());
                ps.setInt(14, target.getMaxCheckAttempts());
                ps.setInt(15, target.getRetryIntervalSeconds());
                ps.setBoolean(16, target.isEnabled());
                ps.setInt(17, target.getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                // Fallback nếu schema cũ
                String fallbackSql = "UPDATE ServerTargets SET name=?, host=?, port=?, protocol=?, interval_seconds=?, timeout_ms=?, warning_threshold_ms=?, enabled=? WHERE id=?";
                try (Connection conn = dbManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(fallbackSql)) {
                    ps.setString(1, target.getName());
                    ps.setString(2, target.getHost());
                    ps.setInt(3, target.getPort());
                    ps.setString(4, target.getProtocol().name());
                    ps.setInt(5, target.getIntervalSeconds());
                    ps.setInt(6, target.getTimeoutMs());
                    ps.setLong(7, target.getWarningThresholdMs());
                    ps.setBoolean(8, target.isEnabled());
                    ps.setInt(9, target.getId());
                    ps.executeUpdate();
                } catch (SQLException ex) {
                    LoggerUtil.error("[TargetRepository] Lỗi cập nhật target trong SQL Server: " + ex.getMessage());
                }
            }
        }

        for (int i = 0; i < memoryBackup.size(); i++) {
            if (memoryBackup.get(i).getId() == target.getId()) {
                memoryBackup.set(i, target);
                break;
            }
        }
        return true;
    }

    public boolean deleteTarget(int targetId) {
        if (dbManager.isConnected()) {
            String sql = "DELETE FROM ServerTargets WHERE id=?";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, targetId);
                ps.executeUpdate();
            } catch (SQLException e) {
                LoggerUtil.error("[TargetRepository] Lỗi xóa target trong SQL Server: " + e.getMessage());
            }
        }

        memoryBackup.removeIf(t -> t.getId() == targetId);
        return true;
    }
}

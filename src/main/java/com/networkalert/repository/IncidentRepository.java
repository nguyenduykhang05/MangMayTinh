package com.networkalert.repository;

import com.networkalert.model.IncidentLog;
import com.networkalert.model.ProtocolType;
import com.networkalert.util.LoggerUtil;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Tầng truy xuất nhật ký sự cố kết nối (IncidentLogs Repository)
 */
public class IncidentRepository {
    private final DatabaseManager dbManager;
    private final List<IncidentLog> memoryLogs = new ArrayList<>();

    public IncidentRepository() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public synchronized void saveIncident(IncidentLog incident) {
        if (dbManager.isConnected()) {
            String sql = "INSERT INTO IncidentLogs (target_id, target_name, host, port, protocol, event_type, event_time, duration_seconds, message, acknowledged) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, incident.getTargetId());
                ps.setString(2, incident.getTargetName());
                ps.setString(3, incident.getHost());
                ps.setInt(4, incident.getPort());
                ps.setString(5, incident.getProtocol() != null ? incident.getProtocol().name() : "TCP");
                ps.setString(6, incident.getEventType());
                ps.setTimestamp(7, Timestamp.valueOf(incident.getEventTime()));
                if (incident.getDurationSeconds() != null) {
                    ps.setInt(8, incident.getDurationSeconds());
                } else {
                    ps.setNull(8, Types.INTEGER);
                }
                ps.setString(9, incident.getMessage());
                ps.setBoolean(10, incident.isAcknowledged());

                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        incident.setId(rs.getInt(1));
                    }
                }
            } catch (SQLException e) {
                System.err.println("[IncidentRepository] Lỗi lưu incident vào SQL Server: " + e.getMessage());
            }
        }

        memoryLogs.add(0, incident); // Thêm vào đầu danh sách
        if (memoryLogs.size() > 500) {
            memoryLogs.remove(memoryLogs.size() - 1);
        }
    }

    public synchronized void resolveActiveIncident(int targetId) {
        LocalDateTime now = LocalDateTime.now();
        // Cập nhật sự cố đang diễn ra gần nhất trong bộ nhớ
        for (IncidentLog log : memoryLogs) {
            if (log.getTargetId() == targetId && log.getDurationSeconds() == null && !"RECOVERED".equalsIgnoreCase(log.getEventType())) {
                if (log.getEventTime() != null) {
                    long sec = java.time.Duration.between(log.getEventTime(), now).getSeconds();
                    log.setDurationSeconds((int) Math.max(1, sec));
                } else {
                    log.setDurationSeconds(1);
                }
                break;
            }
        }

        // Cập nhật CSDL SQL Server nếu đang kết nối
        if (dbManager.isConnected()) {
            String sql = "UPDATE IncidentLogs SET duration_seconds = DATEDIFF(SECOND, event_time, GETDATE()) " +
                         "WHERE id = (SELECT TOP 1 id FROM IncidentLogs WHERE target_id = ? AND duration_seconds IS NULL AND event_type != 'RECOVERED' ORDER BY event_time DESC)";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, targetId);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.err.println("[IncidentRepository] Lỗi cập nhật thời lượng sự cố: " + e.getMessage());
            }
        }
    }

    public List<IncidentLog> getRecentIncidents(int limit) {
        List<IncidentLog> list = new ArrayList<>();
        if (dbManager.isConnected()) {
            String sql = "SELECT TOP (?) id, target_id, target_name, host, port, protocol, event_type, event_time, duration_seconds, message, acknowledged " +
                         "FROM IncidentLogs ORDER BY event_time DESC";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        IncidentLog item = new IncidentLog();
                        item.setId(rs.getInt("id"));
                        item.setTargetId(rs.getInt("target_id"));
                        item.setTargetName(rs.getString("target_name"));
                        item.setHost(rs.getString("host"));
                        item.setPort(rs.getInt("port"));
                        item.setProtocol(ProtocolType.fromString(rs.getString("protocol")));
                        item.setEventType(rs.getString("event_type"));
                        Timestamp ts = rs.getTimestamp("event_time");
                        if (ts != null) {
                            item.setEventTime(ts.toLocalDateTime());
                        }
                        int dur = rs.getInt("duration_seconds");
                        if (!rs.wasNull()) {
                            item.setDurationSeconds(dur);
                        }
                        item.setMessage(rs.getString("message"));
                        item.setAcknowledged(rs.getBoolean("acknowledged"));
                        list.add(item);
                    }
                }
                return list;
            } catch (SQLException e) {
                System.err.println("[IncidentRepository] Lỗi lấy incident từ SQL Server: " + e.getMessage());
            }
        }

        int count = Math.min(limit, memoryLogs.size());
        return new ArrayList<>(memoryLogs.subList(0, count));
    }

    public synchronized void clearAllIncidents() {
        memoryLogs.clear();
        String sql = "DELETE FROM IncidentLogs";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            LoggerUtil.error("[IncidentRepository] Lỗi xóa incident: " + e.getMessage());
        }
    }
}

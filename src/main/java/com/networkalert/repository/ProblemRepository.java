package com.networkalert.repository;

import com.networkalert.model.Problem;
import com.networkalert.model.ProblemSeverity;
import com.networkalert.model.ProblemStatus;
import com.networkalert.util.LoggerUtil;

import java.sql.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Tầng truy xuất dữ liệu Sự cố hệ thống (Problem Repository)
 * Tương thích Microsoft SQL Server và tự động Fallback in-memory khi CSDL ngoại tuyến.
 */
public class ProblemRepository {
    private static final ProblemRepository INSTANCE = new ProblemRepository();

    public static ProblemRepository getInstance() {
        return INSTANCE;
    }

    private final DatabaseManager dbManager;
    private final Clock clock;
    private final List<Problem> memoryProblems = new CopyOnWriteArrayList<>();

    public ProblemRepository() {
        this(Clock.systemDefaultZone());
    }

    public ProblemRepository(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.dbManager = DatabaseManager.getInstance();
    }

    public List<Problem> getAllProblems() {
        if (!dbManager.isConnected()) {
            return new ArrayList<>(memoryProblems);
        }

        List<Problem> list = new ArrayList<>();
        String sql = "SELECT id, target_id, target_name, source, severity, status, title, description, start_time, resolved_time, ack_time, ack_user, ack_comment, diagnosis_json FROM problems ORDER BY id DESC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Problem p = new Problem();
                p.setId(rs.getInt("id"));
                p.setTargetId(rs.getInt("target_id"));
                p.setTargetName(rs.getString("target_name"));
                p.setSource(rs.getString("source"));
                p.setSeverity(ProblemSeverity.fromString(rs.getString("severity")));
                p.setStatus(ProblemStatus.fromString(rs.getString("status")));
                p.setTitle(rs.getString("title"));
                p.setDescription(rs.getString("description"));

                Timestamp st = rs.getTimestamp("start_time");
                if (st != null) p.setStartTime(st.toLocalDateTime());

                Timestamp rt = rs.getTimestamp("resolved_time");
                if (rt != null) p.setResolvedTime(rt.toLocalDateTime());

                Timestamp at = rs.getTimestamp("ack_time");
                if (at != null) p.setAckTime(at.toLocalDateTime());

                p.setAckUser(rs.getString("ack_user"));
                p.setAckComment(rs.getString("ack_comment"));
                try {
                    p.setDiagnosis(rs.getString("diagnosis_json"));
                } catch (Exception ignored) {}
                list.add(p);
            }
            java.util.Map<Integer, Problem> existingMap = new java.util.HashMap<>();
            for (Problem ep : memoryProblems) {
                existingMap.put(ep.getId(), ep);
            }
            List<Problem> finalList = new ArrayList<>();
            for (Problem loaded : list) {
                Problem existing = existingMap.get(loaded.getId());
                if (existing != null) {
                    existing.setTargetId(loaded.getTargetId());
                    existing.setTargetName(loaded.getTargetName());
                    existing.setSource(loaded.getSource());
                    existing.setSeverity(loaded.getSeverity());
                    existing.setStatus(loaded.getStatus());
                    existing.setTitle(loaded.getTitle());
                    existing.setDescription(loaded.getDescription());
                    existing.setStartTime(loaded.getStartTime());
                    existing.setResolvedTime(loaded.getResolvedTime());
                    existing.setAckTime(loaded.getAckTime());
                    existing.setAckUser(loaded.getAckUser());
                    existing.setAckComment(loaded.getAckComment());
                    finalList.add(existing);
                } else {
                    finalList.add(loaded);
                }
            }
            memoryProblems.clear();
            memoryProblems.addAll(finalList);
            return list;
        } catch (SQLException e) {
            LoggerUtil.error("[ProblemRepository] Lỗi đọc danh sách sự cố: " + e.getMessage());
            return new ArrayList<>(memoryProblems);
        }
    }

    public List<Problem> getActiveProblems() {
        return getAllProblems().stream()
                .filter(p -> p.getStatus() == ProblemStatus.ACTIVE || p.getStatus() == ProblemStatus.ACKNOWLEDGED)
                .collect(Collectors.toList());
    }

    public synchronized Problem createOrGetActiveProblem(int targetId, String targetName, String source,
                                                         ProblemSeverity severity, String title, String description) {
        return createOrGetActiveProblem(targetId, targetName, source, severity, title, description, null);
    }

    public synchronized Problem createOrGetActiveProblem(int targetId, String targetName, String source,
                                                         ProblemSeverity severity, String title, String description, String diagnosis) {
        // Kiểm tra xem đã có sự cố đang ACTIVE hoặc ACKNOWLEDGED cho mục tiêu này chưa
        for (Problem p : memoryProblems) {
            if (p.getTargetId() == targetId && p.getStatus() != ProblemStatus.RESOLVED) {
                if (diagnosis != null && p.getDiagnosis() == null) {
                    p.setDiagnosis(diagnosis);
                }
                return p; // Đã có problem đang mở, không tạo trùng
            }
        }

        Problem p = new Problem(0, targetId, targetName, source, severity, ProblemStatus.ACTIVE, title, description, LocalDateTime.now(clock));
        p.setDiagnosis(diagnosis);
        saveNewProblem(p);
        return p;
    }

    public synchronized boolean saveNewProblem(Problem p) {
        if (dbManager.isConnected()) {
            boolean hasDiag = p.getDiagnosis() != null;
            String sql = hasDiag
                    ? "INSERT INTO problems (target_id, target_name, source, severity, status, title, description, start_time, diagnosis_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    : "INSERT INTO problems (target_id, target_name, source, severity, status, title, description, start_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, p.getTargetId());
                ps.setString(2, p.getTargetName());
                ps.setString(3, p.getSource() != null ? p.getSource() : "Server");
                ps.setString(4, p.getSeverity().name());
                ps.setString(5, p.getStatus().name());
                ps.setString(6, p.getTitle());
                ps.setString(7, p.getDescription());
                ps.setTimestamp(8, Timestamp.valueOf(p.getStartTime()));
                if (hasDiag) {
                    ps.setString(9, p.getDiagnosis());
                }

                int rows = ps.executeUpdate();
                if (rows > 0) {
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            p.setId(rs.getInt(1));
                        }
                    }
                    memoryProblems.add(0, p);
                    return true;
                }
            } catch (SQLException e) {
                LoggerUtil.error("[ProblemRepository] Lỗi thêm problem vào CSDL: " + e.getMessage());
            }
        }

        int nextId = memoryProblems.stream().mapToInt(Problem::getId).max().orElse(0) + 1;
        p.setId(nextId);
        memoryProblems.add(0, p);
        return true;
    }

    public synchronized boolean acknowledgeProblem(int problemId, String ackUser, String comment) {
        for (Problem p : memoryProblems) {
            if (p.getId() == problemId) {
                p.acknowledge(ackUser, comment);
                break;
            }
        }

        if (dbManager.isConnected()) {
            String sql = "UPDATE problems SET status=?, ack_time=?, ack_user=?, ack_comment=? WHERE id=?";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ProblemStatus.ACKNOWLEDGED.name());
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(clock)));
                ps.setString(3, ackUser);
                ps.setString(4, comment);
                ps.setInt(5, problemId);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                LoggerUtil.error("[ProblemRepository] Lỗi cập nhật Ack problem: " + e.getMessage());
            }
        }
        return true;
    }

    public synchronized void resolveActiveProblemsForTarget(int targetId, String resolutionMessage) {
        for (Problem p : memoryProblems) {
            if (p.getTargetId() == targetId && p.getStatus() != ProblemStatus.RESOLVED) {
                p.resolve(resolutionMessage);

                if (dbManager.isConnected()) {
                    String sql = "UPDATE problems SET status=?, resolved_time=?, description=? WHERE id=?";
                    try (Connection conn = dbManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, ProblemStatus.RESOLVED.name());
                        ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(clock)));
                        ps.setString(3, p.getDescription());
                        ps.setInt(4, p.getId());
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        LoggerUtil.error("[ProblemRepository] Lỗi đóng problem trong CSDL: " + e.getMessage());
                    }
                }
            }
        }
    }

    public synchronized void resolveProblemsForSource(String source, String resolutionMessage) {
        if (source == null) return;
        for (Problem p : memoryProblems) {
            if (source.equalsIgnoreCase(p.getSource()) && p.getStatus() != ProblemStatus.RESOLVED) {
                p.resolve(resolutionMessage);

                if (dbManager.isConnected()) {
                    String sql = "UPDATE problems SET status=?, resolved_time=?, description=? WHERE id=?";
                    try (Connection conn = dbManager.getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, ProblemStatus.RESOLVED.name());
                        ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(clock)));
                        ps.setString(3, p.getDescription());
                        ps.setInt(4, p.getId());
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        LoggerUtil.error("[ProblemRepository] Lỗi đóng problem trong CSDL: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Xóa tất cả các bản ghi sự cố đã đóng (RESOLVED) khỏi bộ nhớ và CSDL.
     */
    public synchronized int clearResolvedProblems() {
        int removed = 0;
        if (dbManager.isConnected()) {
            String sql = "DELETE FROM problems WHERE status = ?";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ProblemStatus.RESOLVED.name());
                removed = ps.executeUpdate();
            } catch (SQLException e) {
                LoggerUtil.error("[ProblemRepository] Lỗi xóa lịch sử sự cố đã đóng: " + e.getMessage());
            }
        }
        int beforeSize = memoryProblems.size();
        memoryProblems.removeIf(p -> p.getStatus() == ProblemStatus.RESOLVED);
        if (removed == 0) {
            removed = Math.max(0, beforeSize - memoryProblems.size());
        }
        return removed;
    }

    /**
     * Dọn dẹp dữ liệu rác từ kiểm thử đơn vị và tự động đóng các sự cố mồ côi khi khởi chạy ứng dụng.
     */
    public synchronized void cleanupStaleTestAndRecoveredProblems(List<com.networkalert.model.ServerTarget> currentTargets) {
        java.util.Set<Integer> validTargetIds = new java.util.HashSet<>();
        java.util.Set<Integer> onlineTargetIds = new java.util.HashSet<>();
        if (currentTargets != null) {
            for (com.networkalert.model.ServerTarget t : currentTargets) {
                validTargetIds.add(t.getId());
                if (t.getStatus() == com.networkalert.model.ServerStatus.ONLINE) {
                    onlineTargetIds.add(t.getId());
                }
            }
        }

        if (dbManager.isConnected()) {
            try (Connection conn = dbManager.getConnection()) {
                // 1. Xóa các bản ghi giả lập sinh ra từ unit test trước đây
                String deleteTestSql = "DELETE FROM problems WHERE target_name IN ('Critical Gateway', 'Database Production', 'Test Web', 'Mock Target') " +
                        "OR source IN ('agent-console-test', 'agent-offline-01', 'agent-test') " +
                        "OR target_id >= 500";
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate(deleteTestSql);
                }

                // 2. Đóng các sự cố mồ côi có target_id không còn tồn tại trong danh sách máy chủ
                List<Problem> all = getAllProblems();
                for (Problem p : all) {
                    if (p.getStatus() != ProblemStatus.RESOLVED) {
                        boolean isOrphanTarget = p.getTargetId() > 0 && !validTargetIds.isEmpty() && !validTargetIds.contains(p.getTargetId());
                        boolean isTargetOnline = onlineTargetIds.contains(p.getTargetId());
                        boolean isStaleAgentProblem = p.getTargetId() <= 0 && p.getSource() != null && p.getSource().startsWith("agent-");
                        if (isOrphanTarget || isTargetOnline || isStaleAgentProblem) {
                            p.resolve("Tự động đóng sự cố cũ khi khởi động hệ thống");
                            try (PreparedStatement ps = conn.prepareStatement("UPDATE problems SET status=?, resolved_time=?, description=? WHERE id=?")) {
                                ps.setString(1, ProblemStatus.RESOLVED.name());
                                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(clock)));
                                ps.setString(3, p.getDescription());
                                ps.setInt(4, p.getId());
                                ps.executeUpdate();
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                LoggerUtil.error("[ProblemRepository] Lỗi dọn dẹp sự cố cũ: " + e.getMessage());
            }
        }
        getAllProblems();
    }
}

package com.networkalert.server;

import com.networkalert.model.ServerTarget;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.AssignTargetsMessage;
import com.networkalert.protocol.messages.TargetDto;
import com.networkalert.repository.TargetRepository;
import com.networkalert.util.LoggerUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Bộ điều phối và phân bổ mục tiêu giám sát mạng cho các Agent phân tán (Target Distributor).
 * <p>
 * Thuật toán: Least-Assigned kết hợp Round-Robin và đối soát giao thức hỗ trợ (Protocol Affinity).
 * Khi Agent kết nối/ngắt kết nối hoặc danh sách mục tiêu thay đổi, bộ điều phối sẽ tự động
 * tái cân bằng (rebalance) và đẩy lệnh MSG_ASSIGN_TARGETS xuống từng Agent.
 * </p>
 */
public class TargetDistributor {
    private final TargetRepository targetRepository;
    private AgentManager agentManager;

    // Danh sách toàn bộ mục tiêu theo ID
    private final Map<Integer, ServerTarget> targetsMap = new ConcurrentHashMap<>();

    // Bản đồ phân công: targetId -> agentId
    private final Map<Integer, String> targetToAgentMap = new ConcurrentHashMap<>();

    public TargetDistributor(TargetRepository targetRepository) {
        this.targetRepository = targetRepository;
    }

    public void setAgentManager(AgentManager agentManager) {
        this.agentManager = agentManager;
    }

    /**
     * Nạp danh sách mục tiêu từ Repository (SQL Server / Fallback In-memory)
     */
    public synchronized void loadTargets() {
        if (targetRepository != null) {
            List<ServerTarget> list = targetRepository.getAllTargets();
            targetsMap.clear();
            for (ServerTarget t : list) {
                com.networkalert.validation.TargetValidator.autoRepairScope(t);
                targetsMap.put(t.getId(), t);
            }
            LoggerUtil.info("[TargetDistributor] Đã nạp " + targetsMap.size() + " mục tiêu giám sát vào bộ nhớ trung tâm.");
        }
    }

    /**
     * Tái cân bằng tải phân bổ mục tiêu cho toàn bộ các Agent đang trực tuyến
     */
    public synchronized void rebalance() {
        if (agentManager == null) return;

        List<AgentSession> onlineAgents = new ArrayList<>(agentManager.getActiveSessions());
        // Sắp xếp thứ tự Agent theo agentId để đảm bảo tính nhất quán (deterministic)
        onlineAgents.sort(Comparator.comparing(AgentSession::getAgentId));

        List<ServerTarget> enabledTargets = targetsMap.values().stream()
                .filter(ServerTarget::isEnabled)
                .collect(Collectors.toList());

        if (onlineAgents.isEmpty()) {
            targetToAgentMap.clear();
            for (ServerTarget t : targetsMap.values()) {
                t.setAssignedAgent(null);
            }
            LoggerUtil.warn("[TargetDistributor] Hiện tại không có Agent nào trực tuyến! Đang tạm ngưng phân công mục tiêu.");
            return;
        }

        // Bản đồ phân công tạm thời: agentId -> Set<targetId>
        Map<String, Set<Integer>> newAssignments = new HashMap<>();
        for (AgentSession agent : onlineAgents) {
            newAssignments.put(agent.getAgentId(), new HashSet<>());
        }

        // Phân bổ từng mục tiêu theo Scope (SERVER: giữ lại Server; AGENT: gán 1 agent; MULTI: gán nhiều agent)
        for (ServerTarget target : enabledTargets) {
            com.networkalert.model.TargetScope scope = target.getScope();
            if (scope == com.networkalert.model.TargetScope.SERVER) {
                // Scope SERVER: Giữ lại Server trung tâm đo cục bộ, KHÔNG phân công cho Agent
                target.setAssignedAgent("LOCAL");
                continue;
            }

            String protoStr = target.getProtocol() != null ? target.getProtocol().name() : "TCP";
            List<AgentSession> candidates = onlineAgents.stream()
                    .filter(a -> a.supportsProtocol(protoStr))
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                candidates = onlineAgents;
            }

            if (scope == com.networkalert.model.TargetScope.MULTI) {
                // Scope MULTI: Phân công cho tất cả các Agent ứng viên (hoặc danh sách trạm được chỉ định)
                List<String> assignedList = target.getAssignedStations();
                for (AgentSession agent : candidates) {
                    if (assignedList == null || assignedList.isEmpty() || assignedList.contains(agent.getAgentId())) {
                        newAssignments.get(agent.getAgentId()).add(target.getId());
                    }
                }
                target.setAssignedAgent("MULTI");
            } else {
                // Scope AGENT: Phân bổ cho 1 Agent duy nhất (Least-Assigned)
                AgentSession selected = candidates.stream()
                        .min(Comparator.comparingInt(a -> newAssignments.get(a.getAgentId()).size()))
                        .orElse(onlineAgents.get(0));

                newAssignments.get(selected.getAgentId()).add(target.getId());
                target.setAssignedAgent(selected.getAgentId());
            }
        }

        // Cập nhật và gửi thông điệp MSG_ASSIGN_TARGETS cho từng Agent
        Map<Integer, String> newTargetToAgentMap = new HashMap<>();

        for (AgentSession agent : onlineAgents) {
            String aId = agent.getAgentId();
            Set<Integer> newTargetIds = newAssignments.get(aId);
            Set<Integer> currentTargetIds = agent.getAssignedTargetIds();

            for (Integer tid : newTargetIds) {
                newTargetToAgentMap.put(tid, aId);
                ServerTarget t = targetsMap.get(tid);
                if (t != null) {
                    t.setAssignedAgent(aId);
                }
            }

            // Chỉ gửi thông điệp nếu danh sách mục tiêu thực sự có sự thay đổi hoặc chưa từng gửi
            boolean changed = !newTargetIds.equals(currentTargetIds);
            if (changed || currentTargetIds.isEmpty()) {
                agent.setAssignedTargetIds(newTargetIds);

                List<TargetDto> dtoList = newTargetIds.stream()
                        .map(targetsMap::get)
                        .filter(java.util.Objects::nonNull)
                        .map(TargetDto::fromServerTarget)
                        .collect(Collectors.toList());

                AssignTargetsMessage msg = new AssignTargetsMessage(dtoList);
                try {
                    agent.sendFrame(MessageType.ASSIGN_TARGETS, msg);
                    LoggerUtil.info(String.format("[TargetDistributor] Đã phân công %d mục tiêu cho Agent '%s' (%s)",
                            dtoList.size(), aId, agent.getLocalIp()));
                } catch (Exception e) {
                    LoggerUtil.error(String.format("[TargetDistributor] Lỗi gửi phân công mục tiêu tới Agent '%s': %s",
                            aId, e.getMessage()));
                }
            }
        }

        targetToAgentMap.clear();
        targetToAgentMap.putAll(newTargetToAgentMap);
    }

    /**
     * Lấy ServerTarget theo ID
     */
    public ServerTarget getTarget(int targetId) {
        return targetsMap.get(targetId);
    }

    /**
     * Lấy toàn bộ danh sách ServerTarget
     */
    public Collection<ServerTarget> getAllTargets() {
        return Collections.unmodifiableCollection(targetsMap.values());
    }

    /**
     * Lấy Agent ID đang chịu trách nhiệm đo mục tiêu
     */
    public String getAssignedAgent(int targetId) {
        String agentId = targetToAgentMap.get(targetId);
        if (agentId != null) return agentId;
        ServerTarget t = targetsMap.get(targetId);
        if (t != null && agentManager != null) {
            for (AgentSession s : agentManager.getActiveSessions()) {
                if (s.getAssignedTargetIds() != null && s.getAssignedTargetIds().contains(targetId)) {
                    return s.getAgentId();
                }
            }
        }
        return null;
    }

    /**
     * Lấy danh sách mục tiêu do một Agent cụ thể phụ trách
     */
    public List<ServerTarget> getTargetsForAgent(String agentId) {
        if (agentManager == null) return Collections.emptyList();
        AgentSession session = agentManager.getSession(agentId);
        if (session == null) return Collections.emptyList();

        return session.getAssignedTargetIds().stream()
                .map(targetsMap::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Xử lý khi một Agent bị mất kết nối (LOST):
     * Các mục tiêu đang do Agent này đo kiểm sẽ chuyển sang UNREACHABLE (không phải OFFLINE, không bắn cảnh báo giả)
     */
    public synchronized void handleAgentDisconnected(String agentId) {
        if (agentId == null) return;
        for (ServerTarget t : targetsMap.values()) {
            if (agentId.equals(t.getAssignedAgent()) || agentId.equals(t.getParentAgent())) {
                t.getStateMachine().markUnreachable(String.format("Trạm đo Agent '%s' đã ngắt kết nối (LOST)", agentId));
                t.setStatus(com.networkalert.model.ServerStatus.UNREACHABLE);
            }
        }
        rebalance();
    }

    /**
     * Thêm hoặc cập nhật một mục tiêu mới và tự động tái phân bổ
     */
    public synchronized void addOrUpdateTarget(ServerTarget target) {
        if (target != null) {
            com.networkalert.validation.TargetValidator.autoRepairScope(target);
            targetsMap.put(target.getId(), target);
            rebalance();
        }
    }

    /**
     * Xóa mục tiêu và tái phân bổ
     */
    public synchronized void removeTarget(int targetId) {
        targetsMap.remove(targetId);
        targetToAgentMap.remove(targetId);
        rebalance();
    }
}

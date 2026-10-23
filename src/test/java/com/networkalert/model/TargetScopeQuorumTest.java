package com.networkalert.model;

import com.networkalert.protocol.messages.TargetDto;
import com.networkalert.repository.TargetRepository;
import com.networkalert.server.AgentManager;
import com.networkalert.server.AgentSession;
import com.networkalert.server.TargetDistributor;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TargetScopeQuorumTest {

    @Test
    public void testTargetScopeEnumAndDefaults() {
        ServerTarget target = new ServerTarget(1, "Google DNS", "8.8.8.8", 53, ProtocolType.DNS, 5, 1000, true);
        assertEquals(TargetScope.MULTI, target.getScope(), "Mục tiêu mặc định có Scope là MULTI");
        assertEquals("Default", target.getGroup());

        target.setScope(TargetScope.SERVER);
        assertEquals(TargetScope.SERVER, target.getScope());
        assertEquals("trên Server", target.getQuorumSummary());

        target.setScope(TargetScope.AGENT);
        target.setAssignedAgent("agent-hanoi-01");
        assertEquals("agent-hanoi-01", target.getQuorumSummary());
    }

    @Test
    public void testMultiStationQuorumAggregation() {
        ServerTarget target = new ServerTarget(2, "Public Web", "example.com", 80, ProtocolType.HTTP, 5, 1000, true);
        target.setScope(TargetScope.MULTI);

        // Ban đầu chưa có trạm nào đo
        assertEquals("Đang chờ", target.getQuorumSummary());

        // Trạm 1: LOCAL báo thành công 45ms
        target.recordStationResult("LOCAL", new CheckResult(true, 45, "OK"), 300);
        assertEquals("1/1 OK", target.getQuorumSummary());
        assertEquals(ServerStatus.ONLINE, target.getStatus());

        // Trạm 2: agent-hanoi-01 báo thành công 55ms
        target.recordStationResult("agent-hanoi-01", new CheckResult(true, 55, "OK"), 300);
        assertEquals("2/2 OK", target.getQuorumSummary());
        assertEquals(ServerStatus.ONLINE, target.getStatus());

        // Trạm 3: agent-hcm-02 báo thất bại Timeout
        target.recordStationResult("agent-hcm-02", new CheckResult(false, -1, "Connection timed out"), 300);
        assertEquals("2/3 OK", target.getQuorumSummary());
        // 2/3 OK -> Quorum quá bán vẫn là ONLINE!
        assertEquals(ServerStatus.ONLINE, target.getStatus());

        // Trạm 1 bị sự cố (chỉ còn 1/3 OK)
        target.recordStationResult("LOCAL", new CheckResult(false, -1, "Connection refused"), 300);
        target.recordStationResult("LOCAL", new CheckResult(false, -1, "Connection refused"), 300);
        target.recordStationResult("LOCAL", new CheckResult(false, -1, "Connection refused"), 300);
        assertEquals("1/3 OK", target.getQuorumSummary());
    }

    @Test
    public void testMockServersScopeIsServer() {
        TargetRepository repo = new TargetRepository();
        List<ServerTarget> targets = repo.getAllTargets();

        for (ServerTarget t : targets) {
            if (t.getName().contains("Mock") || t.getHost().equals("127.0.0.1")) {
                assertEquals(TargetScope.SERVER, t.getScope(),
                        "Mock target '" + t.getName() + "' phải có scope là SERVER để không gán cho Agent");
            }
        }
    }

    @Test
    public void testTargetDistributorExcludesServerScope() {
        TargetRepository repo = new TargetRepository();
        TargetDistributor distributor = new TargetDistributor(repo);
        distributor.loadTargets();

        // Kiểm tra target Mock TCP Server có scope SERVER
        boolean foundMock = false;
        for (ServerTarget t : repo.getAllTargets()) {
            if (t.getHost().equals("127.0.0.1") && t.getPort() == 9000) {
                foundMock = true;
                assertEquals(TargetScope.SERVER, t.getScope());
            }
        }
        assertTrue(foundMock, "Phải tìm thấy Mock TCP Server trong repo");
    }

    @Test
    public void testTargetDtoSerializationWithScope() {
        ServerTarget target = new ServerTarget(10, "Test", "1.2.3.4", 80, ProtocolType.TCP, 5, 1000, 200, true);
        target.setScope(TargetScope.SERVER);
        target.setGroup("Network Core");
        target.setTags("core,router");

        TargetDto dto = TargetDto.fromServerTarget(target);
        assertEquals("SERVER", dto.getScope());
        assertEquals("Network Core", dto.getGroup());
        assertEquals("core,router", dto.getTags());

        ServerTarget restored = dto.toServerTarget();
        assertEquals(TargetScope.SERVER, restored.getScope());
        assertEquals("Network Core", restored.getGroup());
        assertEquals("core,router", restored.getTags());
    }
}

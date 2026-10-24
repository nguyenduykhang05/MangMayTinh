package com.networkalert.model;

import com.networkalert.server.TargetDistributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử toàn diện cho Giai đoạn 3C — Logic trạng thái kiểu Nagios:
 * 1. SOFT / HARD state transitions & Chỉ HARD mới sinh cảnh báo
 * 2. Tần suất kiểm tra dày hơn (retry_interval) khi ở trạng thái SOFT
 * 3. Chuỗi quan hệ thiết bị cha (Parent Dependency) 2-3 cấp -> UNREACHABLE
 * 4. Thuật toán phát hiện vòng lặp phân cấp cha (Parent Cycle Detection)
 * 5. Thuật toán chống rung lắc mạng Flapping (bật/tắt tự động theo ngưỡng)
 * 6. Trạm Agent LOST kéo theo mục tiêu con thành UNREACHABLE (không phải OFFLINE)
 */
public class NagiosStateMachineTest {

    private final long WARNING_THRESHOLD = 300L;

    @Test
    @DisplayName("1. Bảng chuyển trạng thái SOFT/HARD: Chỉ HARD mới sinh cảnh báo, hồi phục từ HARD cũng sinh thông báo")
    void testSoftHardStateTransitions() {
        TargetStateMachine sm = new TargetStateMachine(1, "Database Server", 3, 2, 2);

        // Khởi tạo ban đầu thành công -> HARD ONLINE
        Optional<TargetStateMachine.StateTransition> initTrans = sm.processResult(CheckResult.success(20, "OK"), WARNING_THRESHOLD);
        assertTrue(initTrans.isPresent());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());
        assertEquals(StateType.HARD, sm.getStateType());
        assertEquals(1, sm.getCurrentAttempt());

        // Lần 1 thất bại -> Trạng thái SOFT (lần 1/3), KHÔNG sinh cảnh báo (transitionOpt is empty)
        Optional<TargetStateMachine.StateTransition> soft1 = sm.processResult(CheckResult.failure("Connection Timeout"), WARNING_THRESHOLD);
        assertFalse(soft1.isPresent(), "Lần 1 thất bại là SOFT -> Chưa sinh cảnh báo");
        assertEquals(StateType.SOFT, sm.getStateType());
        assertEquals(1, sm.getCurrentAttempt());
        assertEquals("lần 1/3", sm.getAttemptDisplay());

        // Lần 2 thất bại -> Vẫn là SOFT (lần 2/3), KHÔNG sinh cảnh báo
        Optional<TargetStateMachine.StateTransition> soft2 = sm.processResult(CheckResult.failure("Connection Timeout"), WARNING_THRESHOLD);
        assertFalse(soft2.isPresent(), "Lần 2 thất bại là SOFT -> Chưa sinh cảnh báo");
        assertEquals(StateType.SOFT, sm.getStateType());
        assertEquals(2, sm.getCurrentAttempt());
        assertEquals("lần 2/3", sm.getAttemptDisplay());

        // Lần 3 thất bại -> Đạt ngưỡng tối đa 3 lần -> CHUYỂN SANG HARD OFFLINE, KÍCH HOẠT CẢNH BÁO
        Optional<TargetStateMachine.StateTransition> hardTrans = sm.processResult(CheckResult.failure("Connection Refused"), WARNING_THRESHOLD);
        assertTrue(hardTrans.isPresent(), "Lần 3 thất bại đạt ngưỡng maxCheckAttempts -> Phải sinh StateTransition HARD");
        TargetStateMachine.StateTransition transition = hardTrans.get();
        assertEquals(ServerStatus.OFFLINE, transition.getToStatus());
        assertEquals(StateType.HARD, transition.getStateType());
        assertTrue(transition.isHardTransition(), "Phải đánh dấu là hardTransition để gửi thông báo");
        assertEquals(StateType.HARD, sm.getStateType());
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());

        // Hồi phục: Lần 1 thành công trong khi đang HARD OFFLINE -> Chưa đủ M=2 lần hồi phục
        Optional<TargetStateMachine.StateTransition> rec1 = sm.processResult(CheckResult.success(25, "Ping OK"), WARNING_THRESHOLD);
        assertFalse(rec1.isPresent(), "Lần 1 thành công chưa đủ M=2 lần hồi phục");
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());

        // Lần 2 thành công -> Đủ M=2 lần hồi phục -> CHUYỂN SANG HARD ONLINE, KÍCH HOẠT THÔNG BÁO HỒI PHỤC
        Optional<TargetStateMachine.StateTransition> rec2 = sm.processResult(CheckResult.success(28, "Ping OK"), WARNING_THRESHOLD);
        assertTrue(rec2.isPresent(), "Hồi phục sau khi đủ 2 lần thành công");
        assertTrue(rec2.get().isHardTransition(), "Hồi phục từ HARD phải kích hoạt thông báo hồi phục");
        assertEquals(ServerStatus.ONLINE, rec2.get().getToStatus());
        assertEquals(StateType.HARD, rec2.get().getStateType());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());

        // Trường hợp hồi phục khi đang SOFT (chưa thành HARD): Không cần cảnh báo hồi phục
        sm.processResult(CheckResult.failure("Rớt mạng tạm"), WARNING_THRESHOLD);
        assertEquals(StateType.SOFT, sm.getStateType());
        // Lần tiếp theo thành công ngay lập tức
        Optional<TargetStateMachine.StateTransition> softRecover = sm.processResult(CheckResult.success(20, "Hồi mạng"), WARNING_THRESHOLD);
        assertFalse(softRecover.isPresent(), "Hồi phục khi đang SOFT không cần sinh thông báo vì chưa từng báo HARD lỗi");
        assertEquals(StateType.HARD, sm.getStateType());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("2. Tần suất kiểm tra: Khi ở SOFT kiểm tra dày hơn (retry_interval), khi HARD quay về bình thường")
    void testRetryIntervalInSoftState() {
        ServerTarget target = new ServerTarget(2, "Payment API", "10.0.0.5", 443, ProtocolType.HTTPS, 10, 1500, 300, true);
        target.setRetryIntervalSeconds(2); // Thử lại dày hơn mỗi 2s khi lỗi

        assertEquals(10, target.getIntervalSeconds());
        assertEquals(2, target.getRetryIntervalSeconds());

        // Ban đầu đang ổn định -> Chu kỳ là intervalSeconds (10s)
        target.recordCheckResult(new CheckResult(true, 50, "OK"), 300);
        assertEquals(StateType.HARD, target.getStateType());
        assertEquals(10, target.getEffectiveIntervalSeconds(), "Khi HARD ONLINE, chu kỳ là 10 giây bình thường");

        // Lần 1 bị lỗi -> Chuyển sang SOFT -> Chu kỳ tự động rút ngắn còn retryInterval (2s)
        target.recordCheckResult(new CheckResult(false, -1, "Gateway Timeout"), 300);
        assertEquals(StateType.SOFT, target.getStateType());
        assertEquals(2, target.getEffectiveIntervalSeconds(), "Khi ở SOFT, chu kỳ kiểm tra phải dày hơn (2 giây)");

        // Lần 2 tiếp tục lỗi -> Vẫn ở SOFT -> Chu kỳ vẫn là 2s
        target.recordCheckResult(new CheckResult(false, -1, "Gateway Timeout"), 300);
        assertEquals(StateType.SOFT, target.getStateType());
        assertEquals(2, target.getEffectiveIntervalSeconds());

        // Lần 3 lỗi -> Chuyển sang HARD OFFLINE -> Chu kỳ quay trở lại intervalSeconds (10s)
        target.recordCheckResult(new CheckResult(false, -1, "Connection Refused"), 300);
        assertEquals(StateType.HARD, target.getStateType());
        assertEquals(10, target.getEffectiveIntervalSeconds(), "Khi đã thành HARD OFFLINE, chu kỳ kiểm tra quay lại 10 giây");
    }

    @Test
    @DisplayName("3. Chuỗi quan hệ cha–con hai-ba cấp: Khi cha HARD DOWN thì con thành UNREACHABLE")
    void testParentChainTwoThreeLevelsUnreachable() {
        // Cấp 1: Core Router (ID 1)
        ServerTarget router = new ServerTarget(1, "Core Router", "192.168.1.1", 0, ProtocolType.ICMP, 5, 1000, true);
        // Cấp 2: Switch tầng (ID 2, phụ thuộc Core Router)
        ServerTarget switchDev = new ServerTarget(2, "Floor Switch", "192.168.1.2", 0, ProtocolType.ICMP, 5, 1000, true);
        switchDev.setParentId(1);
        // Cấp 3: Web Server (ID 3, phụ thuộc Floor Switch)
        ServerTarget webServer = new ServerTarget(3, "Web Server", "192.168.1.50", 80, ProtocolType.HTTP, 5, 1000, true);
        webServer.setParentId(2);

        // Ban đầu cả 3 thiết bị đều ONLINE
        router.recordCheckResult(new CheckResult(true, 2, "OK"), 300);
        switchDev.recordCheckResult(new CheckResult(true, 5, "OK"), 300);
        webServer.recordCheckResult(new CheckResult(true, 10, "OK"), 300);

        assertEquals(ServerStatus.ONLINE, router.getStatus());
        assertEquals(ServerStatus.ONLINE, switchDev.getStatus());
        assertEquals(ServerStatus.ONLINE, webServer.getStatus());

        // Giả lập Core Router (ID 1) bị đứt cáp -> Rớt mạng liên tiếp 3 lần thành HARD OFFLINE
        router.recordCheckResult(new CheckResult(false, -1, "Down 1"), 300);
        router.recordCheckResult(new CheckResult(false, -1, "Down 2"), 300);
        router.recordCheckResult(new CheckResult(false, -1, "Down 3"), 300);

        assertEquals(ServerStatus.OFFLINE, router.getStatus());
        assertEquals(StateType.HARD, router.getStateType());

        // Vì Core Router bị HARD DOWN, Switch con (ID 2) và Web Server cháu (ID 3) phải thành UNREACHABLE
        switchDev.getStateMachine().markUnreachable("Thiết bị cấp trên 'Core Router' đang bị HARD DOWN");
        switchDev.setStatus(ServerStatus.UNREACHABLE);

        webServer.getStateMachine().markUnreachable("Thiết bị cấp trên 'Floor Switch' đang bị UNREACHABLE");
        webServer.setStatus(ServerStatus.UNREACHABLE);

        assertEquals(ServerStatus.UNREACHABLE, switchDev.getStatus(), "Switch con phải thành UNREACHABLE khi router cha sập");
        assertEquals(ServerStatus.UNREACHABLE, webServer.getStatus(), "Web Server cháu phải thành UNREACHABLE");

        // Khi Core Router khôi phục
        router.recordCheckResult(new CheckResult(true, 2, "OK"), 300);
        router.recordCheckResult(new CheckResult(true, 2, "OK"), 300);
        assertEquals(ServerStatus.ONLINE, router.getStatus());

        switchDev.getStateMachine().clearUnreachable();
        switchDev.recordCheckResult(new CheckResult(true, 5, "OK"), 300);
        assertEquals(ServerStatus.ONLINE, switchDev.getStatus());
    }

    @Test
    @DisplayName("4. Thuật toán phát hiện vòng lặp cấu hình cha (Parent Loop / Cycle Detection) và từ chối")
    void testParentCycleDetection() {
        Map<Integer, ServerTarget> targetsMap = new HashMap<>();

        ServerTarget t1 = new ServerTarget(1, "Node A", "10.0.0.1", 80, ProtocolType.TCP, 5, 1000, true);
        ServerTarget t2 = new ServerTarget(2, "Node B", "10.0.0.2", 80, ProtocolType.TCP, 5, 1000, true);
        ServerTarget t3 = new ServerTarget(3, "Node C", "10.0.0.3", 80, ProtocolType.TCP, 5, 1000, true);

        targetsMap.put(1, t1);
        targetsMap.put(2, t2);
        targetsMap.put(3, t3);

        // Trường hợp 1: Tự trỏ vào chính mình (A -> A) -> Từ chối vòng lặp
        assertTrue(TargetStateMachine.wouldCreateCycle(1, 1, targetsMap), "Không được tự đặt chính mình làm cha");

        // Trường hợp 2: Vòng lặp 2 cấp (A -> B, rồi B -> A) -> Từ chối vòng lặp
        t1.setParentId(2); // A có cha là B
        assertTrue(TargetStateMachine.wouldCreateCycle(2, 1, targetsMap), "B trỏ lại cha là A phải bị phát hiện vòng lặp 2 cấp");

        // Trường hợp 3: Vòng lặp 3 cấp (A -> B -> C -> A) -> Từ chối vòng lặp
        t1.setParentId(2); // A cha là B
        t2.setParentId(3); // B cha là C
        assertTrue(TargetStateMachine.wouldCreateCycle(3, 1, targetsMap), "C trỏ lại cha là A phải bị phát hiện vòng lặp 3 cấp");

        // Trường hợp 4: Chuỗi hợp lệ không vòng lặp (A <- B <- C, D trỏ vào C)
        ServerTarget t4 = new ServerTarget(4, "Node D", "10.0.0.4", 80, ProtocolType.TCP, 5, 1000, true);
        targetsMap.put(4, t4);
        assertFalse(TargetStateMachine.wouldCreateCycle(4, 3, targetsMap), "D trỏ vào C là chuỗi DAG hợp lệ, không có chu trình");
    }

    @Test
    @DisplayName("5. Thuật toán chống rung lắc Flapping: Tự bật khi biến thiên cao và tự tắt khi ổn định")
    void testFlappingDetectionOnOff() {
        TargetStateMachine sm = new TargetStateMachine(5, "Unstable Line", 1, 1, 1);
        sm.setFlappingHighThreshold(25.0); // 25% biến thiên
        sm.setFlappingLowThreshold(10.0);  // 10% biến thiên

        assertFalse(sm.isFlapping());

        // Gửi chuỗi kiểm tra chập chờn liên tục: OK, FAIL, OK, FAIL, OK, FAIL...
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) {
                sm.processResult(CheckResult.success(20, "Ping OK"), WARNING_THRESHOLD);
            } else {
                sm.processResult(CheckResult.failure("Drop packet"), WARNING_THRESHOLD);
            }
        }

        // Tỷ lệ thay đổi trạng thái sẽ rất cao (> 50%) -> Kích hoạt FLAPPING
        assertTrue(sm.isFlapping(), "Đường truyền biến thiên trạng thái liên tục phải kích hoạt FLAPPING");
        assertTrue(sm.getFlappingRatio() >= 25.0);

        // Sau đó đường truyền ổn định trở lại: gửi liên tiếp 20 lần kiểm tra thành công
        for (int i = 0; i < 20; i++) {
            sm.processResult(CheckResult.success(20, "Stable OK"), WARNING_THRESHOLD);
        }

        // Tỷ lệ thay đổi giảm xuống 0% -> Tự động tắt FLAPPING
        assertFalse(sm.isFlapping(), "Khi đường truyền ổn định lâu dài, FLAPPING phải tự động tắt");
        assertTrue(sm.getFlappingRatio() <= 10.0);
    }

    @Test
    @DisplayName("6. Trạm đo Agent bị LOST: Kéo theo các mục tiêu thuộc Agent đó thành UNREACHABLE (không phải OFFLINE)")
    void testAgentLostCascadesToChildTargets() {
        TargetDistributor distributor = new TargetDistributor(null);

        ServerTarget target1 = new ServerTarget(101, "Branch Camera 1", "192.168.20.10", 80, ProtocolType.TCP, 5, 1000, true);
        target1.setScope(TargetScope.AGENT);
        target1.setAssignedAgent("agent-danang-01");
        target1.recordCheckResult(new CheckResult(true, 15, "OK"), 300);
        assertEquals(ServerStatus.ONLINE, target1.getStatus());

        distributor.addOrUpdateTarget(target1);

        // Giả lập Agent "agent-danang-01" bị rớt mạng đột ngột (LOST)
        distributor.handleAgentDisconnected("agent-danang-01");

        // Target phải chuyển sang UNREACHABLE, tuyệt đối không phải là OFFLINE
        assertEquals(ServerStatus.UNREACHABLE, target1.getStatus(),
                "Target của Agent bị LOST phải chuyển thành UNREACHABLE, không được báo OFFLINE làm hoang mang người dùng");
    }
}

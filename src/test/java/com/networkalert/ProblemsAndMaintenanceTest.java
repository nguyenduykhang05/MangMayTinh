package com.networkalert;

import com.networkalert.model.*;
import com.networkalert.repository.ProblemRepository;
import com.networkalert.service.MaintenanceService;
import com.networkalert.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử toàn diện cho Giai đoạn 3D:
 * 1. Quản lý Sự cố (Problems Lifecycle: Active -> Acknowledged -> Auto-Resolved)
 * 2. Cửa sổ bảo trì hệ thống (Maintenance Windows: One-off & Recurring)
 * 3. Loại trừ thời gian bảo trì ra khỏi SLA (Uptime Calculation Exclusion)
 * 4. Kiểm tra toàn bộ SVG Icon tồn tại trong resources/icons
 */
public class ProblemsAndMaintenanceTest {

    private ProblemRepository problemRepo;
    private MaintenanceService maintenanceService;

    @BeforeEach
    public void setUp() {
        problemRepo = ProblemRepository.getInstance();
        maintenanceService = MaintenanceService.getInstance();
    }

    @Test
    @DisplayName("1. Vòng đời sự cố: Mở -> Xác nhận (Ack) -> Tự đóng khi Hard Online")
    public void testProblemLifecycle() {
        int testTargetId = 9988;
        String testTargetName = "Database Cluster Master";

        // Tạo sự cố mới (Active)
        Problem p = problemRepo.createOrGetActiveProblem(
                testTargetId, testTargetName, "Server",
                ProblemSeverity.DISASTER, "Mất kết nối CSDL", "Không thể ping tới cổng 1433"
        );
        assertNotNull(p);
        assertEquals(ProblemSeverity.DISASTER, p.getSeverity());
        assertEquals(ProblemStatus.ACTIVE, p.getStatus());

        // Kiểm tra nằm trong danh sách Active
        List<Problem> active = problemRepo.getActiveProblems();
        assertTrue(active.stream().anyMatch(item -> item.getId() == p.getId()));

        // Trùng lặp: Gọi lại không sinh problem thứ 2
        Problem p2 = problemRepo.createOrGetActiveProblem(
                testTargetId, testTargetName, "Server",
                ProblemSeverity.DISASTER, "Lỗi lặp lại", "Chi tiết"
        );
        assertEquals(p.getId(), p2.getId());

        // Người dùng xác nhận sự cố (Acknowledge)
        boolean acked = problemRepo.acknowledgeProblem(p.getId(), "viet_admin", "Đang chuyển hướng sang node Slave");
        assertTrue(acked);
        assertEquals(ProblemStatus.ACKNOWLEDGED, p.getStatus());
        assertEquals("viet_admin", p.getAckUser());
        assertEquals("Đang chuyển hướng sang node Slave", p.getAckComment());

        // Tự động đóng (Resolved) khi mục tiêu hồi phục trạng thái HARD ONLINE
        problemRepo.resolveActiveProblemsForTarget(testTargetId, "Mục tiêu đã hồi phục kết nối (HARD ONLINE - RTT: 2 ms)");
        assertEquals(ProblemStatus.RESOLVED, p.getStatus());
        assertNotNull(p.getResolvedTime());
        assertTrue(p.getDescription().contains("HARD ONLINE"));

        // Không còn trong danh sách Active
        List<Problem> activeAfter = problemRepo.getActiveProblems();
        assertFalse(activeAfter.stream().anyMatch(item -> item.getId() == p.getId()));
    }

    @Test
    @DisplayName("2. Cửa sổ bảo trì một lần (One-off) và lặp tuần (Weekly Recurring)")
    public void testMaintenanceWindows() {
        TestClock testClock = TestClock.of("2026-10-25T03:30:00Z"); // 2026-10-25 là Chủ Nhật
        LocalDateTime now = LocalDateTime.now(testClock);

        // One-off bao gồm thời điểm hiện tại -> Phải Active
        MaintenanceWindow activeOneOff = new MaintenanceWindow(
                101, "Nâng cấp RAM khẩn cấp", MaintenanceScope.TARGET, "10",
                now.minusMinutes(30), now.plusMinutes(60)
        );
        assertTrue(activeOneOff.isActive(testClock));

        // One-off trong quá khứ -> Không Active
        MaintenanceWindow pastOneOff = new MaintenanceWindow(
                102, "Bảo trì tuần trước", MaintenanceScope.TARGET, "10",
                now.minusDays(2), now.minusDays(1)
        );
        assertFalse(pastOneOff.isActive(testClock));

        // Weekly recurring đúng thứ và giờ hiện tại (Chủ Nhật, 03:30) -> Active
        DayOfWeek today = now.getDayOfWeek();
        LocalTime startTime = now.toLocalTime().minusMinutes(10);
        LocalTime endTime = now.toLocalTime().plusMinutes(50);
        MaintenanceWindow activeRecurring = new MaintenanceWindow(
                103, "Bảo trì định kỳ", MaintenanceScope.ALL, "",
                today, startTime, endTime
        );
        assertTrue(activeRecurring.isActive(testClock));

        // Recurring khác thứ -> Không Active
        DayOfWeek otherDay = today.plus(1);
        MaintenanceWindow inactiveDayRecurring = new MaintenanceWindow(
                104, "Bảo trì ngày mai", MaintenanceScope.ALL, "",
                otherDay, startTime, endTime
        );
        assertFalse(inactiveDayRecurring.isActive(testClock));
    }

    @Test
    @DisplayName("3. Áp dụng bảo trì theo Phạm vi (Target, Group, Agent, All)")
    public void testMaintenanceScopes() {
        TestClock testClock = TestClock.of("2026-10-24T10:00:00Z");
        LocalDateTime now = LocalDateTime.now(testClock);

        ServerTarget target1 = new ServerTarget(55, "Web Core", "10.0.0.1", 80, ProtocolType.HTTP, 5, 1000, true);
        target1.setGroup("PRODUCTION");
        target1.setAssignedAgent("agent-danang-02");

        // Scope TARGET
        MaintenanceWindow winTarget = new MaintenanceWindow(201, "BT Target 55", MaintenanceScope.TARGET, "55", now.minusHours(1), now.plusHours(1));
        assertTrue(winTarget.appliesTo(target1, testClock));

        MaintenanceWindow winOtherTarget = new MaintenanceWindow(202, "BT Target 99", MaintenanceScope.TARGET, "99", now.minusHours(1), now.plusHours(1));
        assertFalse(winOtherTarget.appliesTo(target1, testClock));

        // Scope GROUP
        MaintenanceWindow winGroup = new MaintenanceWindow(203, "BT Nhóm PROD", MaintenanceScope.GROUP, "PRODUCTION", now.minusHours(1), now.plusHours(1));
        assertTrue(winGroup.appliesTo(target1, testClock));

        MaintenanceWindow winOtherGroup = new MaintenanceWindow(204, "BT Nhóm STAGING", MaintenanceScope.GROUP, "STAGING", now.minusHours(1), now.plusHours(1));
        assertFalse(winOtherGroup.appliesTo(target1, testClock));

        // Scope AGENT
        MaintenanceWindow winAgent = new MaintenanceWindow(205, "BT Agent Danang", MaintenanceScope.AGENT, "agent-danang-02", now.minusHours(1), now.plusHours(1));
        assertTrue(winAgent.appliesTo(target1, testClock));

        // Scope ALL
        MaintenanceWindow winAll = new MaintenanceWindow(206, "BT Toàn hệ thống", MaintenanceScope.ALL, "", now.minusHours(1), now.plusHours(1));
        assertTrue(winAll.appliesTo(target1, testClock));
    }

    @Test
    @DisplayName("4. Loại trừ bảo trì ra khỏi SLA (Uptime Exclusion) & Chặn cảnh báo")
    public void testMaintenanceExclusionFromSLAAndNoAlerts() {
        ServerTarget target = new ServerTarget(777, "SLA Excluded Target", "10.0.0.2", 8080, ProtocolType.TCP, 5, 1000, true);

        // Thiết lập bảo trì đang hiệu lực cho target này
        LocalDateTime now = LocalDateTime.now(Clock.systemDefaultZone());
        MaintenanceWindow activeWindow = new MaintenanceWindow(
                301, "Bảo trì nâng cấp", MaintenanceScope.TARGET, "777",
                now.minusMinutes(10), now.plusMinutes(50)
        );
        maintenanceService.addWindow(activeWindow);

        try {
            // Thực hiện kiểm tra thất bại trong khi bảo trì
            CheckResult failResult = CheckResult.failure("Kết nối bị từ chối (Connection Refused)");
            Optional<TargetStateMachine.StateTransition> transitionOpt = target.recordCheckResult(failResult, 300);

            // 1. Phải chuyển trạng thái sang MAINTENANCE (màu xanh dương)
            assertEquals(ServerStatus.MAINTENANCE, target.getStatus());

            // 2. Không sinh StateTransition (Chặn hoàn toàn cảnh báo Toast / Email / Telegram / Sound)
            assertFalse(transitionOpt.isPresent());

            // 3. Loại trừ hoàn toàn khỏi tính SLA: totalChecks không tăng!
            assertEquals(0, target.getTotalChecks());
            assertEquals(0, target.getSuccessfulChecks());
            assertEquals(100.0, target.getUptimePercentage());

            // 4. Nhịp tim Heartbeat bar ghi nhận trạng thái MAINTENANCE
            List<ServerTarget.HeartbeatRecord> hb = target.getHeartbeats();
            assertFalse(hb.isEmpty());
            assertEquals(ServerStatus.MAINTENANCE, hb.get(hb.size() - 1).getStatus());

        } finally {
            maintenanceService.removeWindow(301);
        }
    }

    @Test
    @DisplayName("5. Kiểm tra toàn bộ SVG Icon trong code đều tồn tại vật lý trong resources/icons")
    public void testAllIconsExistInResources() {
        File iconsDir = new File("src/main/resources/icons");
        assertTrue(iconsDir.exists() && iconsDir.isDirectory(), "Thư mục icons phải tồn tại");

        String[] requiredIcons = {
                "activity", "alert-circle", "alert-triangle", "check-circle", "check",
                "chevron-down", "copy", "cpu", "download", "eraser", "git-branch",
                "globe", "info", "layers", "moon", "pause", "play", "plus", "radio",
                "refresh-cw", "search", "server", "shield", "sliders", "sun", "tool",
                "trash-2", "x-circle", "x", "zap"
        };

        for (String iconName : requiredIcons) {
            File svgFile = new File(iconsDir, iconName + ".svg");
            assertTrue(svgFile.exists(), "Icon '" + iconName + ".svg' không tồn tại trong resources/icons");
            assertTrue(svgFile.length() > 0, "Icon '" + iconName + ".svg' rỗng nội dung");
        }
    }

    @Test
    @DisplayName("6. Tính toán thời lượng sự cố tất định với TestClock")
    public void testProblemDurationWithTestClock() {
        TestClock testClock = TestClock.of("2026-10-24T12:00:00Z");
        Problem p = new Problem(1, 100, "WebServer", "Server", ProblemSeverity.HIGH, ProblemStatus.ACTIVE, "Down", "Down detail", null, testClock);

        assertEquals("0 giây", p.getDurationFormatted(testClock));

        // Tiến 45 giây
        testClock.advanceSeconds(45);
        assertEquals("45 giây", p.getDurationFormatted(testClock));

        // Tiến thêm 10 phút
        testClock.advanceSeconds(600);
        assertEquals("10 phút 45s", p.getDurationFormatted(testClock));

        // Xác nhận sự cố tại thời điểm này
        p.acknowledge("admin", "Investigating", testClock);
        assertEquals(ProblemStatus.ACKNOWLEDGED, p.getStatus());
        assertNotNull(p.getAckTime());

        // Tiến thêm 2 giờ
        testClock.advanceSeconds(7200);
        assertEquals("2 giờ 10p", p.getDurationFormatted(testClock));

        // Đóng sự cố
        p.resolve("Fixed power cable", testClock);
        assertEquals(ProblemStatus.RESOLVED, p.getStatus());
        assertNotNull(p.getResolvedTime());

        // Tiến thêm 1 ngày, thời lượng không đổi do sự cố đã resolve
        testClock.advanceSeconds(86400);
        assertEquals("2 giờ 10p", p.getDurationFormatted(testClock));
    }
}

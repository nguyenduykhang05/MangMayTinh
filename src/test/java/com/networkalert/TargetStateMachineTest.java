package com.networkalert;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.TargetStateMachine;
import com.networkalert.model.TargetStateMachine.StateTransition;
import com.networkalert.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử toàn diện cho TargetStateMachine (Bảng chuyển trạng thái & Hysteresis)
 */
public class TargetStateMachineTest {

    private TargetStateMachine sm;
    private final long WARNING_THRESHOLD = 300L;

    @BeforeEach
    void setUp() {
        // N = 3 lần fail mới OFFLINE, M = 2 lần OK mới phục hồi
        sm = new TargetStateMachine(1, "Google Web", 3, 2);
    }

    @Test
    @DisplayName("Khởi tạo ban đầu ở trạng thái UNKNOWN")
    void testInitialState() {
        assertEquals(ServerStatus.UNKNOWN, sm.getCurrentStatus());
        assertEquals(0, sm.getConsecutiveFailures());
        assertEquals(0, sm.getConsecutiveSuccesses());
    }

    @Test
    @DisplayName("Khởi tạo UNKNOWN -> ONLINE khi check thành công với độ trễ thấp")
    void testUnknownToOnline() {
        Optional<StateTransition> transition = sm.processResult(CheckResult.success(50, "OK"), WARNING_THRESHOLD);
        assertTrue(transition.isPresent());
        assertEquals(ServerStatus.UNKNOWN, transition.get().getFromStatus());
        assertEquals(ServerStatus.ONLINE, transition.get().getToStatus());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("Khởi tạo UNKNOWN -> WARNING khi check thành công lần đầu nhưng độ trễ cao")
    void testUnknownToWarning() {
        Optional<StateTransition> transition = sm.processResult(CheckResult.success(650, "Slow response"), WARNING_THRESHOLD);
        assertTrue(transition.isPresent());
        assertEquals(ServerStatus.UNKNOWN, transition.get().getFromStatus());
        assertEquals(ServerStatus.WARNING, transition.get().getToStatus());
        assertEquals(ServerStatus.WARNING, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("Hysteresis thất bại ban đầu: UNKNOWN chỉ chuyển sang OFFLINE sau đủ N=3 lần fail")
    void testUnknownToOfflineWithHysteresis() {
        // Lần 1 fail -> Chưa chuyển OFFLINE
        Optional<StateTransition> t1 = sm.processResult(CheckResult.failure("Timeout"), WARNING_THRESHOLD);
        assertFalse(t1.isPresent());
        assertEquals(ServerStatus.UNKNOWN, sm.getCurrentStatus());
        assertEquals(1, sm.getConsecutiveFailures());

        // Lần 2 fail -> Vẫn giữ UNKNOWN
        Optional<StateTransition> t2 = sm.processResult(CheckResult.failure("Timeout"), WARNING_THRESHOLD);
        assertFalse(t2.isPresent());
        assertEquals(ServerStatus.UNKNOWN, sm.getCurrentStatus());
        assertEquals(2, sm.getConsecutiveFailures());

        // Lần 3 fail -> Đủ N=3 lần -> Chuyển sang OFFLINE
        Optional<StateTransition> t3 = sm.processResult(CheckResult.failure("Connection Refused"), WARNING_THRESHOLD);
        assertTrue(t3.isPresent());
        assertEquals(ServerStatus.UNKNOWN, t3.get().getFromStatus());
        assertEquals(ServerStatus.OFFLINE, t3.get().getToStatus());
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("Chuyển trạng thái giữa ONLINE và WARNING theo biến thiên độ trễ")
    void testOnlineWarningTransitions() {
        // Khởi tạo ONLINE
        sm.processResult(CheckResult.success(100, "OK"), WARNING_THRESHOLD);
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());

        // Trễ tăng vọt lên 450ms -> Chuyển WARNING
        Optional<StateTransition> t1 = sm.processResult(CheckResult.success(450, "High latency"), WARNING_THRESHOLD);
        assertTrue(t1.isPresent());
        assertEquals(ServerStatus.ONLINE, t1.get().getFromStatus());
        assertEquals(ServerStatus.WARNING, t1.get().getToStatus());
        assertEquals(ServerStatus.WARNING, sm.getCurrentStatus());

        // Tiếp tục trễ 500ms -> Giữ nguyên WARNING (không sinh sự kiện mới)
        Optional<StateTransition> t2 = sm.processResult(CheckResult.success(500, "Still high"), WARNING_THRESHOLD);
        assertFalse(t2.isPresent());
        assertEquals(ServerStatus.WARNING, sm.getCurrentStatus());

        // Trễ giảm xuống 80ms -> Chuyển lại ONLINE
        Optional<StateTransition> t3 = sm.processResult(CheckResult.success(80, "Recovered speed"), WARNING_THRESHOLD);
        assertTrue(t3.isPresent());
        assertEquals(ServerStatus.WARNING, t3.get().getFromStatus());
        assertEquals(ServerStatus.ONLINE, t3.get().getToStatus());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("Hysteresis chống flapping: ONLINE chỉ rớt OFFLINE khi thất bại đủ 3 lần liên tiếp")
    void testOnlineToOfflineHysteresis() {
        sm.processResult(CheckResult.success(50, "OK"), WARNING_THRESHOLD);
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());

        // Gặp 1 lần rớt mạng tạm thời (flapping/packet loss) -> Không rớt OFFLINE ngay
        Optional<StateTransition> t1 = sm.processResult(CheckResult.failure("Packet dropped"), WARNING_THRESHOLD);
        assertFalse(t1.isPresent());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());
        assertEquals(1, sm.getConsecutiveFailures());

        // Ngay sau đó lại thành công -> Bộ đếm thất bại bị reset về 0
        Optional<StateTransition> t2 = sm.processResult(CheckResult.success(60, "OK"), WARNING_THRESHOLD);
        assertFalse(t2.isPresent());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());
        assertEquals(0, sm.getConsecutiveFailures());

        // Bây giờ thực sự đứt mạng liên tục 3 lần:
        sm.processResult(CheckResult.failure("Host down 1"), WARNING_THRESHOLD);
        sm.processResult(CheckResult.failure("Host down 2"), WARNING_THRESHOLD);
        Optional<StateTransition> t3 = sm.processResult(CheckResult.failure("Host down 3"), WARNING_THRESHOLD);

        assertTrue(t3.isPresent());
        assertEquals(ServerStatus.ONLINE, t3.get().getFromStatus());
        assertEquals(ServerStatus.OFFLINE, t3.get().getToStatus());
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("Hysteresis khôi phục: OFFLINE chỉ phục hồi về ONLINE sau M=2 lần thành công liên tiếp")
    void testOfflineToOnlineHysteresis() {
        // Đưa máy chủ về OFFLINE
        sm.processResult(CheckResult.failure("Fail 1"), WARNING_THRESHOLD);
        sm.processResult(CheckResult.failure("Fail 2"), WARNING_THRESHOLD);
        sm.processResult(CheckResult.failure("Fail 3"), WARNING_THRESHOLD);
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());

        // Lần thành công thứ 1: Chưa đủ M=2 -> Vẫn giữ OFFLINE
        Optional<StateTransition> r1 = sm.processResult(CheckResult.success(70, "Ping OK"), WARNING_THRESHOLD);
        assertFalse(r1.isPresent(), "Lần thành công đầu tiên chưa được công nhận hồi phục");
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());
        assertEquals(1, sm.getConsecutiveSuccesses());

        // Lần thành công thứ 2: Đủ M=2 -> Chuyển sang ONLINE
        Optional<StateTransition> r2 = sm.processResult(CheckResult.success(80, "Ping OK"), WARNING_THRESHOLD);
        assertTrue(r2.isPresent());
        assertEquals(ServerStatus.OFFLINE, r2.get().getFromStatus());
        assertEquals(ServerStatus.ONLINE, r2.get().getToStatus());
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("Khôi phục với độ trễ cao: Chuyển trực tiếp OFFLINE -> WARNING (1 sự kiện duy nhất)")
    void testOfflineToWarningSingleTransition() {
        // Đưa máy chủ về OFFLINE
        sm.processResult(CheckResult.failure("Fail 1"), WARNING_THRESHOLD);
        sm.processResult(CheckResult.failure("Fail 2"), WARNING_THRESHOLD);
        sm.processResult(CheckResult.failure("Fail 3"), WARNING_THRESHOLD);
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());

        // Lần 1: Thành công nhưng chậm (600ms)
        sm.processResult(CheckResult.success(600, "Slow response"), WARNING_THRESHOLD);
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());

        // Lần 2: Tiếp tục thành công chậm (612ms) -> Đủ M=2 -> Chuyển thẳng OFFLINE -> WARNING
        Optional<StateTransition> r = sm.processResult(CheckResult.success(612, "Slow response"), WARNING_THRESHOLD);
        assertTrue(r.isPresent());
        assertEquals(ServerStatus.OFFLINE, r.get().getFromStatus());
        assertEquals(ServerStatus.WARNING, r.get().getToStatus());
        assertEquals(ServerStatus.WARNING, sm.getCurrentStatus());
        assertTrue(r.get().getDescription().contains("độ trễ cao"));
    }

    @Test
    @DisplayName("Tạm dừng (pause) và Tiếp tục (resume) giám sát")
    void testPauseAndResume() {
        sm.processResult(CheckResult.success(100, "OK"), WARNING_THRESHOLD);
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());

        // Tạm dừng
        Optional<StateTransition> p = sm.pause();
        assertTrue(p.isPresent());
        assertEquals(ServerStatus.PAUSED, p.get().getToStatus());
        assertEquals(ServerStatus.PAUSED, sm.getCurrentStatus());

        // Khi PAUSED, kết quả kiểm tra không làm đổi trạng thái
        Optional<StateTransition> ignored = sm.processResult(CheckResult.success(50, "OK"), WARNING_THRESHOLD);
        assertFalse(ignored.isPresent());
        assertEquals(ServerStatus.PAUSED, sm.getCurrentStatus());

        // Tiếp tục lại
        Optional<StateTransition> res = sm.resume();
        assertTrue(res.isPresent());
        assertEquals(ServerStatus.UNKNOWN, res.get().getToStatus());
        assertEquals(ServerStatus.UNKNOWN, sm.getCurrentStatus());
    }

    @Test
    @DisplayName("Kiểm thử TargetStateMachine với TestClock ghi nhận đúng timestamp tất định")
    void testStateMachineWithTestClock() {
        TestClock testClock = TestClock.of("2026-10-24T12:00:00Z");
        TargetStateMachine smClock = new TargetStateMachine(1, "Clock Target", 3, 2, 10, testClock);

        testClock.setMillis(1_700_000_000_000L);
        Optional<StateTransition> t1 = smClock.processResult(CheckResult.success(20, "OK"), 300);
        assertTrue(t1.isPresent());
        assertEquals(1_700_000_000_000L, t1.get().getTimestamp());

        // Tiến đồng hồ 5 phút
        testClock.advanceSeconds(300);
        // Fail 3 lần
        smClock.processResult(CheckResult.failure("Fail 1"), 300);
        smClock.processResult(CheckResult.failure("Fail 2"), 300);
        Optional<StateTransition> tFail = smClock.processResult(CheckResult.failure("Fail 3"), 300);
        assertTrue(tFail.isPresent());
        assertEquals(1_700_000_000_000L + 300_000L, tFail.get().getTimestamp());
        assertEquals(ServerStatus.OFFLINE, tFail.get().getToStatus());
    }
}

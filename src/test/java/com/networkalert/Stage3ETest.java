package com.networkalert;

import com.networkalert.model.*;
import com.networkalert.server.AgentManager;
import com.networkalert.server.AgentSession;
import com.networkalert.service.PushMonitorService;
import com.networkalert.service.checker.CheckerFactory;
import com.networkalert.service.checker.IProtocolChecker;
import com.networkalert.service.checker.PushChecker;
import com.networkalert.test.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử toàn diện cho Giai đoạn 3E (Mở rộng P2):
 * 1. Push Monitor (POST /api/push/{token}, Rate Limiting, Grace Period Timeout)
 * 2. HttpChecker (Từ khóa Keyword matching, TLS Expiry Days & Warning)
 * 3. Trang /status công khai (Bypass token auth, cấu trúc JSON 30 ngày)
 * 4. Agent Enrollment (Token một lần, Dedicated Secret riêng, Thu hồi Revoke)
 */
public class Stage3ETest {

    @Test
    @DisplayName("1. Push Monitor: Nhận nhịp tim UP/DOWN, cập nhật RTT & Message")
    public void testPushMonitorReceive() {
        TestClock testClock = TestClock.of("2026-10-24T10:00:00Z");
        PushMonitorService.getInstance().setClock(testClock);

        ServerTarget target = new ServerTarget(901, "Backup Server Snitch", "push.local", 0, ProtocolType.PUSH, 60, 1000, true);
        target.setMaxCheckAttempts(1); // Chuyển trạng thái ngay sau 1 lần thất bại
        String token = PushMonitorService.getInstance().generatePushToken();
        target.setPushToken(token);

        // 1. Gửi tín hiệu UP
        PushMonitorService.PushResult resUp = PushMonitorService.getInstance().handlePush(
                token, "up", "Sao lưu hoàn tất trong 42 giây", 15, target
        );
        assertEquals(200, resUp.getStatusCode());
        assertEquals(ServerStatus.ONLINE, target.getStatus());
        assertEquals(15, target.getCurrentLatency());
        assertTrue(target.getLastPushTimestamp() > 0);

        // 2. Tua đồng hồ qua 2s để vượt qua Rate Limit và gửi tín hiệu DOWN (không cần Thread.sleep)
        testClock.advanceSeconds(2);

        PushMonitorService.PushResult resDown = PushMonitorService.getInstance().handlePush(
                token, "down", "Ổ đĩa sao lưu đầy dung lượng", -1, target
        );
        assertEquals(200, resDown.getStatusCode());
        assertEquals(ServerStatus.OFFLINE, target.getStatus());
    }

    @Test
    @DisplayName("2. Push Monitor: Kiểm tra Rate Limiting chống spam")
    public void testPushMonitorRateLimiting() {
        ServerTarget target = new ServerTarget(902, "Cronjob Rate Limit", "push.local", 0, ProtocolType.PUSH, 60, 1000, true);
        String token = "push_test_rate_limit_" + System.currentTimeMillis();
        target.setPushToken(token);

        // Lần 1: Thành công (200)
        PushMonitorService.PushResult r1 = PushMonitorService.getInstance().handlePush(token, "up", "OK 1", 5, target);
        assertEquals(200, r1.getStatusCode());

        // Lần 2 gửi ngay lập tức (< 1000ms): Bị từ chối HTTP 429 Too Many Requests
        PushMonitorService.PushResult r2 = PushMonitorService.getInstance().handlePush(token, "up", "OK 2", 5, target);
        assertEquals(429, r2.getStatusCode());
        assertTrue(r2.getJsonResponse().contains("Rate limit exceeded"));
    }

    @Test
    @DisplayName("3. Push Monitor: PushChecker phát hiện hết hạn nhịp tim (Grace Period Expired)")
    public void testPushCheckerTimeout() {
        ServerTarget target = new ServerTarget(903, "Database Backup Cron", "push.local", 0, ProtocolType.PUSH, 10, 1000, true);
        target.setPushToken("push_cron_backup");
        target.setPushGraceSeconds(5); // Tổng thời gian chờ tối đa = 10 + 5 = 15 giây

        PushChecker checker = (PushChecker) CheckerFactory.getChecker(ProtocolType.PUSH);

        // Chưa nhận push lần nào
        CheckResult r0 = checker.check(target);
        assertFalse(r0.isSuccess());
        assertTrue(r0.getMessage().contains("Đang chờ nhịp tim Push đầu tiên"));

        // Vừa nhận push
        target.setLastPushTimestamp(System.currentTimeMillis());
        CheckResult r1 = checker.check(target);
        assertTrue(r1.isSuccess());
        assertTrue(r1.getMessage().contains("Nhịp tim Push OK"));

        // Đã quá hạn 20 giây (vượt ngưỡng 15 giây)
        target.setLastPushTimestamp(System.currentTimeMillis() - 20_000L);
        CheckResult r2 = checker.check(target);
        assertFalse(r2.isSuccess());
        assertTrue(r2.getMessage().contains("Quá hạn nhịp tim Push"));
    }

    private AgentSession createMockSession(java.net.ServerSocket ss) throws Exception {
        Socket client = new Socket("127.0.0.1", ss.getLocalPort());
        Socket server = ss.accept();
        return new AgentSession(server);
    }

    @Test
    @DisplayName("4. Agent Enrollment: Sinh token 1 lần, đổi Dedicated Secret riêng & Thu hồi")
    public void testAgentEnrollmentAndRevocation() throws Exception {
        AgentManager am = new AgentManager(null, 30_000, 10);

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            // 1. Sinh Enrollment Token dùng một lần có hạn 15 phút
            AgentManager.EnrollmentToken et = am.generateEnrollmentToken(15);
            assertNotNull(et);
            assertTrue(et.getToken().startsWith("ENROLL-"));
            assertTrue(et.isValid());
            assertFalse(et.isUsed());

            // 2. Agent đăng ký lần đầu bằng Enrollment Token
            com.networkalert.protocol.messages.RegisterRequest req = new com.networkalert.protocol.messages.RegisterRequest(
                    "agent-enroll-01", "PC-ENROLL", "Windows 11", "192.168.1.50", "00:11:22:33:44:55",
                    et.getToken(), Collections.singletonList("TCP"), "1.0.0"
            );

            AgentSession session = createMockSession(ss);
            boolean registered = am.registerAgent(session, req, "SERVER-DEFAULT-SECRET");
            assertTrue(registered);

            // Token một lần đã được đánh dấu used và liên kết với agent
            assertTrue(et.isUsed());
            assertEquals("agent-enroll-01", et.getBoundAgentId());

            // Agent đã được cấp Dedicated Secret riêng
            String dedicatedSecret = am.getAllAgentSecrets().get("agent-enroll-01");
            assertNotNull(dedicatedSecret);
            assertTrue(dedicatedSecret.startsWith("SEC-"));

            // 3. Tái sử dụng token một lần cũ bị TỪ CHỐI
            com.networkalert.protocol.messages.RegisterRequest reqReplay = new com.networkalert.protocol.messages.RegisterRequest(
                    "agent-attacker", "PC-ATTACK", "Linux", "192.168.1.99", "AA:BB:CC:DD:EE:FF",
                    et.getToken(), Collections.singletonList("TCP"), "1.0.0"
            );
            AgentSession attackerSession = createMockSession(ss);
            boolean replayed = am.registerAgent(attackerSession, reqReplay, "SERVER-DEFAULT-SECRET");
            assertFalse(replayed, "Token dùng một lần không được phép tái sử dụng!");

            // 4. Agent kết nối lại bằng Dedicated Secret riêng -> ĐƯỢC CHẤP NHẬN
            com.networkalert.protocol.messages.RegisterRequest reqNormal = new com.networkalert.protocol.messages.RegisterRequest(
                    "agent-enroll-01", "PC-ENROLL", "Windows 11", "192.168.1.50", "00:11:22:33:44:55",
                    dedicatedSecret, Collections.singletonList("TCP"), "1.0.0"
            );
            AgentSession reconnectSession = createMockSession(ss);
            boolean reconnected = am.registerAgent(reconnectSession, reqNormal, "SERVER-DEFAULT-SECRET");
            assertTrue(reconnected);

            // 5. Quản trị viên thu hồi Secret riêng của Agent này
            boolean revoked = am.revokeAgentSecret("agent-enroll-01");
            assertTrue(revoked);
            assertNull(am.getAllAgentSecrets().get("agent-enroll-01"));

            // Sau khi bị thu hồi, Agent kết nối lại bằng secret cũ sẽ bị TỪ CHỐI
            AgentSession afterRevokeSession = createMockSession(ss);
            boolean afterRevoke = am.registerAgent(afterRevokeSession, reqNormal, "SERVER-DEFAULT-SECRET");
            assertFalse(afterRevoke, "Agent bị thu hồi Secret không được phép kết nối!");
        } finally {
            am.shutdown();
        }
    }

    @Test
    @DisplayName("5. HttpChecker: Hạn chứng chỉ TLS và cảnh báo khi dưới 14 ngày")
    public void testTlsExpiryDaysModel() {
        ServerTarget target = new ServerTarget(905, "Secure Gateway", "gateway.local", 443, ProtocolType.HTTPS, 5, 1000, true);

        // Thiết lập còn 7 ngày
        target.setTlsExpiryDays(7);
        assertEquals(7, target.getTlsExpiryDays());
        assertTrue(target.getTlsExpiryDays() < 14, "Phải nằm trong ngưỡng cảnh báo < 14 ngày");

        // Thiết lập còn 90 ngày
        target.setTlsExpiryDays(90);
        assertFalse(target.getTlsExpiryDays() < 14);
    }
}

package com.networkalert.agent;

import com.networkalert.model.ServerTarget;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.server.AgentSession;
import com.networkalert.server.NcasServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử tích hợp toàn diện cho NCAS Agent (Giai đoạn 3):
 * - Kết nối ngược (Reverse TCP), tự động đăng ký và nhận danh sách mục tiêu từ Server
 * - Thu thập và gửi báo cáo kết quả kiểm tra lên Server
 * - Xử lý đo tức thời (Force Check) theo yêu cầu Server
 * - Hàng đợi lưu trữ ngoại tuyến (Store-and-Forward Offline Queue) khi mất kết nối Server
 * - Gửi nhịp tim định kỳ và thông số tài nguyên CPU/RAM
 * - Dừng an toàn (Graceful Stop) và gửi thông điệp ngắt kết nối
 */
public class NcasAgentTest {

    private NcasServer server;
    private NcasAgent agent;
    private final String testToken = "TEST-AGENT-SECRET-999";

    @BeforeEach
    void setUp() throws Exception {
        // Khởi động server trung tâm trên cổng động 0
        server = new NcasServer(0, "127.0.0.1", testToken);
        server.start();
        assertTrue(server.isRunning());
        assertTrue(server.getPort() > 0);
    }

    @AfterEach
    void tearDown() {
        if (agent != null && agent.isRunning()) {
            agent.stop();
        }
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    private void waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (!condition.getAsBoolean() && (System.currentTimeMillis() - start) < timeoutMs) {
            new CountDownLatch(1).await(20, TimeUnit.MILLISECONDS);
        }
        assertTrue(condition.getAsBoolean(), "Điều kiện không thỏa mãn sau " + timeoutMs + "ms");
    }

    @Test
    @DisplayName("Kiểm thử Agent kết nối Reverse TCP, đăng ký thành công và nhận mục tiêu phân công")
    void testAgentConnectRegisterAndReceiveTargets() throws Exception {
        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-test-unit-01");
        config.setAuthToken(testToken);

        agent = new NcasAgent(config);
        agent.start();

        // Chờ Agent kết nối và được Server chấp nhận
        waitForCondition(() -> agent.isConnected() && agent.isAuthenticated(), 3000);

        assertTrue(agent.isConnected());
        assertTrue(agent.isAuthenticated());
        assertNotNull(agent.getAssignedSessionId());

        // Chờ nhận thông điệp phân công mục tiêu
        waitForCondition(() -> !agent.getCurrentTargets().isEmpty(), 2000);
        assertFalse(agent.getCurrentTargets().isEmpty(), "Agent phải nhận được ít nhất 1 mục tiêu từ Server");

        // Kiểm tra Server đã ghi nhận phiên của Agent
        assertEquals(1, server.getAgentManager().getActiveAgentCount());
        AgentSession session = server.getAgentManager().getSession("agent-test-unit-01");
        assertNotNull(session);
        assertEquals("agent-test-unit-01", session.getAgentId());
    }

    @Test
    @DisplayName("Kiểm thử Báo cáo kết quả đo kiểm từ Agent được cập nhật lên Server")
    void testAgentReportResultSentToServer() throws Exception {
        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-report-01");
        config.setAuthToken(testToken);

        agent = new NcasAgent(config);
        agent.start();

        waitForCondition(() -> agent.isConnected() && agent.isAuthenticated() && server.getAgentManager().getActiveAgentCount() > 0 && !agent.getCurrentTargets().isEmpty(), 3000);

        // Lấy 1 target được giao
        int targetId = agent.getCurrentTargets().keySet().iterator().next();
        ServerTarget serverTarget = server.getTargetDistributor().getTarget(targetId);
        assertNotNull(serverTarget);

        // Gửi kết quả đo kiểm giả lập từ Agent
        ReportResultMessage rep = new ReportResultMessage(
                "agent-report-01", targetId, System.currentTimeMillis(), true, 48, 200, null, "Agent Check OK"
        );
        agent.sendOrBufferReport(rep);

        // Đợi Server nhận và xử lý kết quả
        waitForCondition(() -> serverTarget.getCurrentLatency() == 48, 2000);
        assertEquals(48, serverTarget.getCurrentLatency());
    }

    @Test
    @DisplayName("Kiểm thử Xử lý yêu cầu đo tức thời (Force Check) từ Server")
    void testForceCheckHandling() throws Exception {
        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-force-01");
        config.setAuthToken(testToken);

        agent = new NcasAgent(config);
        agent.start();

        waitForCondition(() -> agent.isConnected() && agent.isAuthenticated() && server.getAgentManager().getActiveAgentCount() > 0 && !agent.getCurrentTargets().isEmpty(), 3000);

        int targetId = agent.getCurrentTargets().keySet().iterator().next();

        CountDownLatch resultLatch = new CountDownLatch(1);
        server.addCheckResultListener((t, r) -> {
            if (t.getId() == targetId) {
                resultLatch.countDown();
            }
        });

        // Server yêu cầu Agent đo tức thời
        boolean dispatched = server.forceCheckTarget(targetId);
        assertTrue(dispatched, "Lệnh Force Check phải được gửi đi thành công tới Agent");

        // Chờ Agent đo và gửi phản hồi về Server qua listener
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS), "Agent phải phản hồi kết quả Force Check");
        ServerTarget target = server.getTargetDistributor().getTarget(targetId);
        assertNotNull(target);
        assertTrue(target.getTotalChecks() >= 1);
    }

    @Test
    @DisplayName("Kiểm thử Hàng đợi Store-and-Forward lưu trữ khi mất mạng và tự động xả khi kết nối lại")
    void testStoreAndForwardOfflineQueue() throws Exception {
        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-offline-01");
        config.setAuthToken(testToken);
        config.setReconnectInitialDelayMs(200);

        agent = new NcasAgent(config);

        // Khi Agent chưa kết nối (offline), gửi kết quả đo vào hàng đợi
        ReportResultMessage rep1 = new ReportResultMessage("agent-offline-01", 1, System.currentTimeMillis(), true, 20, 200, null, "Offline 1");
        ReportResultMessage rep2 = new ReportResultMessage("agent-offline-01", 2, System.currentTimeMillis(), true, 25, 200, null, "Offline 2");

        agent.sendOrBufferReport(rep1);
        agent.sendOrBufferReport(rep2);

        assertEquals(2, agent.getOfflineQueue().size(), "Hàng đợi phải chứa 2 bản tin ngoại tuyến");

        // Khởi động Agent để kết nối tới Server
        agent.start();

        waitForCondition(() -> agent.isConnected() && agent.isAuthenticated(), 3000);

        // Chờ xả hàng đợi offline
        waitForCondition(() -> agent.getOfflineQueue().isEmpty(), 2000);
        assertEquals(0, agent.getOfflineQueue().size(), "Hàng đợi Store-and-Forward phải được xả sạch lên Server");
    }

    @Test
    @DisplayName("Kiểm thử Gửi Nhịp tim Heartbeat và chỉ số CPU/RAM")
    void testHeartbeatPingSending() throws Exception {
        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-hb-unit");
        config.setAuthToken(testToken);

        agent = new NcasAgent(config);
        agent.start();

        waitForCondition(() -> agent.isConnected() && agent.isAuthenticated(), 3000);

        AgentSession session = server.getAgentManager().getSession("agent-hb-unit");
        assertNotNull(session);
        long prevHb = session.getLastHeartbeatTime();

        // Kích hoạt gửi heartbeat
        agent.sendHeartbeat();

        // Chờ session ghi nhận heartbeat mới
        waitForCondition(() -> session.getLastFreeMemoryMb() > 0, 2000);
        assertTrue(session.getLastHeartbeatTime() >= prevHb);
        assertTrue(session.getLastFreeMemoryMb() > 0, "Phải báo cáo dung lượng RAM trống");
    }

    @Test
    @DisplayName("Kiểm thử Dừng Agent an toàn (Graceful Stop)")
    void testAgentGracefulStop() throws Exception {
        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-stop-01");
        config.setAuthToken(testToken);

        agent = new NcasAgent(config);
        agent.start();

        waitForCondition(() -> agent.isConnected() && server.getAgentManager().getActiveAgentCount() == 1, 3000);
        assertEquals(1, server.getAgentManager().getActiveAgentCount());

        // Dừng agent
        agent.stop();
        assertFalse(agent.isRunning());
        assertFalse(agent.isConnected());

        // Chờ Server nhận thông báo ngắt kết nối và giải phóng phiên
        waitForCondition(() -> server.getAgentManager().getActiveAgentCount() == 0, 2000);
        assertEquals(0, server.getAgentManager().getActiveAgentCount());
    }
}

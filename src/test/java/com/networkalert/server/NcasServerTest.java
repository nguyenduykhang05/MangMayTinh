package com.networkalert.server;

import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.AssignTargetsMessage;
import com.networkalert.protocol.messages.ConsoleCommand;
import com.networkalert.protocol.messages.ConsoleResponse;
import com.networkalert.protocol.messages.DisconnectMessage;
import com.networkalert.protocol.messages.HeartbeatPing;
import com.networkalert.protocol.messages.HeartbeatPong;
import com.networkalert.protocol.messages.RegisterRequest;
import com.networkalert.protocol.messages.RegisterResponse;
import com.networkalert.protocol.messages.ReportResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.networkalert.test.TestClock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử tích hợp toàn diện cho Máy chủ Trung tâm NCAS Server (Giai đoạn 2):
 * - Vòng đời khởi động, lắng nghe cổng ngẫu nhiên và dừng an toàn
 * - Xác thực danh tính Agent và phân công mục tiêu giám sát tự động
 * - Từ chối kết nối khi sai Auth Token
 * - Nhịp tim Heartbeat Ping / Pong
 * - Thu thập báo cáo kết quả đo kiểm, chuyển đổi State Machine và ghi log sự cố
 * - Thực thi các lệnh điều khiển Console CLI qua mạng
 * - Ngắt kết nối an toàn (Graceful Disconnect)
 * - Phát hiện Agent chết đột tử qua Heartbeat Timeout
 */
public class NcasServerTest {

    private NcasServer server;
    private final String testToken = "TEST-SECRET-TOKEN-456";

    @BeforeEach
    void setUp() throws Exception {
        // Khởi động server trên cổng động 0 để tránh xung đột cổng
        server = new NcasServer(0, "127.0.0.1", testToken);
        server.start();
        assertTrue(server.isRunning());
        assertTrue(server.getPort() > 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Kiểm thử vòng đời máy chủ: Khởi động, lắng nghe và dừng")
    void testServerLifecycle() {
        assertTrue(server.isRunning());
        int boundPort = server.getPort();
        assertTrue(boundPort > 1024, "Cổng phải được cấp phát hợp lệ: " + boundPort);

        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    @DisplayName("Kiểm thử Đăng ký Agent thành công và nhận phân bổ mục tiêu giám sát")
    void testAgentRegistrationAndTargetAssignment() throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            InputStream in = new BufferedInputStream(client.getInputStream());

            // 1. Gửi RegisterRequest
            RegisterRequest req = new RegisterRequest(
                    "agent-hcm-01", "PC-HCM", "Windows 11", "192.168.1.50",
                    "00:11:22:33:44:55", testToken, Arrays.asList("TCP", "HTTP", "ICMP"), "1.0.0"
            );
            Frame regFrame = Frame.create(MessageType.REGISTER_REQ, req);
            FrameEncoder.encodeToStream(regFrame, out);

            // 2. Nhận RegisterResponse
            Frame respFrame = FrameDecoder.decodeFromStream(in);
            assertNotNull(respFrame);
            assertEquals(MessageType.REGISTER_RESP, respFrame.getType());

            RegisterResponse resp = respFrame.parsePayload(RegisterResponse.class);
            assertTrue(resp.isAccepted());
            assertNotNull(resp.getAssignedSessionId());
            assertEquals(10, resp.getHeartbeatIntervalSeconds());

            // 3. Nhận AssignTargetsMessage
            Frame assignFrame = FrameDecoder.decodeFromStream(in);
            assertNotNull(assignFrame);
            assertEquals(MessageType.ASSIGN_TARGETS, assignFrame.getType());

            AssignTargetsMessage assignMsg = assignFrame.parsePayload(AssignTargetsMessage.class);
            assertNotNull(assignMsg.getTargets());
            assertFalse(assignMsg.getTargets().isEmpty(), "Phải nhận được ít nhất 1 mục tiêu được phân công");

            // 4. Kiểm tra trên AgentManager của Server
            assertEquals(1, server.getAgentManager().getActiveAgentCount());
            AgentSession session = server.getAgentManager().getSession("agent-hcm-01");
            assertNotNull(session);
            assertEquals("PC-HCM", session.getHostname());
            assertTrue(session.isAuthenticated());
        }
    }

    @Test
    @DisplayName("Kiểm thử Từ chối Đăng ký khi sai Token xác thực")
    void testAgentRegistrationAuthFailure() throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            InputStream in = new BufferedInputStream(client.getInputStream());

            RegisterRequest req = new RegisterRequest(
                    "agent-hacker", "PC-HACK", "Linux", "10.0.0.99",
                    "AA:BB:CC:DD:EE:FF", "WRONG-TOKEN-999", List.of("TCP"), "1.0.0"
            );
            Frame regFrame = Frame.create(MessageType.REGISTER_REQ, req);
            FrameEncoder.encodeToStream(regFrame, out);

            Frame respFrame = FrameDecoder.decodeFromStream(in);
            assertNotNull(respFrame);
            assertEquals(MessageType.REGISTER_RESP, respFrame.getType());

            RegisterResponse resp = respFrame.parsePayload(RegisterResponse.class);
            assertFalse(resp.isAccepted(), "Phải từ chối khi sai token");
            assertTrue(resp.getMessage().contains("thất bại") || resp.getMessage().contains("Token"));

            assertEquals(0, server.getAgentManager().getActiveAgentCount());
        }
    }

    @Test
    @DisplayName("Kiểm thử Nhịp tim Heartbeat Ping / Pong")
    void testHeartbeatPingPong() throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            InputStream in = new BufferedInputStream(client.getInputStream());

            // Đăng ký trước
            RegisterRequest req = new RegisterRequest("agent-hb", "PC-HB", "Linux", "192.168.1.10", "MAC", testToken, List.of("TCP"), "1.0.0");
            FrameEncoder.encodeToStream(Frame.create(MessageType.REGISTER_REQ, req), out);

            // Bỏ qua RegisterResponse và AssignTargets
            FrameDecoder.decodeFromStream(in); // REGISTER_RESP
            FrameDecoder.decodeFromStream(in); // ASSIGN_TARGETS

            // Gửi Heartbeat Ping
            HeartbeatPing ping = new HeartbeatPing("agent-hb", System.currentTimeMillis(), 3, 24.5, 4096);
            FrameEncoder.encodeToStream(Frame.create(MessageType.HEARTBEAT_PING, ping), out);

            // Nhận Heartbeat Pong
            Frame pongFrame = FrameDecoder.decodeFromStream(in);
            assertNotNull(pongFrame);
            assertEquals(MessageType.HEARTBEAT_PONG, pongFrame.getType());

            HeartbeatPong pong = pongFrame.parsePayload(HeartbeatPong.class);
            assertEquals("OK", pong.getServerStatus());

            // Kiểm tra chỉ số máy tính trạm được cập nhật trên Server
            AgentSession session = server.getAgentManager().getSession("agent-hb");
            assertNotNull(session);
            assertEquals(24.5, session.getLastCpuUsage(), 0.01);
            assertEquals(4096, session.getLastFreeMemoryMb());
            assertEquals(3, session.getActiveTasks());
        }
    }

    @Test
    @DisplayName("Kiểm thử Xử lý Báo cáo Kết quả đo kiểm và Chuyển trạng thái State Machine")
    void testReportResultAndStateTransitions() throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            InputStream in = new BufferedInputStream(client.getInputStream());

            // Đăng ký
            RegisterRequest req = new RegisterRequest("agent-report", "PC-REP", "Linux", "192.168.1.20", "MAC", testToken, List.of("TCP"), "1.0.0");
            FrameEncoder.encodeToStream(Frame.create(MessageType.REGISTER_REQ, req), out);

            FrameDecoder.decodeFromStream(in); // REGISTER_RESP
            Frame assignFrame = FrameDecoder.decodeFromStream(in); // ASSIGN_TARGETS
            AssignTargetsMessage assignMsg = assignFrame.parsePayload(AssignTargetsMessage.class);
            int targetId = assignMsg.getTargets().get(0).getId();

            ServerTarget target = server.getTargetDistributor().getTarget(targetId);
            assertNotNull(target);

            // Gửi kết quả thành công đầu tiên
            CountDownLatch okLatch = new CountDownLatch(1);
            server.addCheckResultListener((t, r) -> {
                if (t.getId() == targetId && r.isSuccess()) {
                    okLatch.countDown();
                }
            });

            ReportResultMessage okReport = new ReportResultMessage(
                    "agent-report", targetId, System.currentTimeMillis(), true, 35, 200, null, "Connect OK"
            );
            FrameEncoder.encodeToStream(Frame.create(MessageType.REPORT_RESULT, okReport), out);

            // Đợi xử lý qua listener
            assertTrue(okLatch.await(2, TimeUnit.SECONDS), "Server phải xử lý xong ReportResult thành công");
            assertEquals(35, target.getCurrentLatency());
            assertEquals(ServerStatus.ONLINE, target.getStatus());

            // Gửi 3 lần thất bại liên tiếp để kích hoạt chuyển sang OFFLINE (theo ngưỡng chống flapping FailThreshold=3)
            CountDownLatch failLatch = new CountDownLatch(3);
            server.addCheckResultListener((t, r) -> {
                if (t.getId() == targetId && !r.isSuccess()) {
                    failLatch.countDown();
                }
            });

            for (int i = 0; i < 3; i++) {
                ReportResultMessage failReport = new ReportResultMessage(
                        "agent-report", targetId, System.currentTimeMillis(), false, -1, -1, null, "Connection refused"
                );
                FrameEncoder.encodeToStream(Frame.create(MessageType.REPORT_RESULT, failReport), out);
            }

            // Đợi xử lý 3 bản tin lỗi
            assertTrue(failLatch.await(2, TimeUnit.SECONDS), "Server phải xử lý đủ 3 ReportResult thất bại");
            assertEquals(ServerStatus.OFFLINE, target.getStatus(), "Mục tiêu phải chuyển sang OFFLINE sau 3 lần lỗi liên tiếp");
        }
    }

    @Test
    @DisplayName("Kiểm thử Thực thi Lệnh Quản trị Console CLI")
    void testConsoleCommands() throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            InputStream in = new BufferedInputStream(client.getInputStream());

            // Đăng ký 1 Agent trước
            RegisterRequest reg = new RegisterRequest("agent-cmd", "HOST-CMD", "Linux", "10.0.0.1", "MAC", testToken, List.of("TCP"), "1.0.0");
            FrameEncoder.encodeToStream(Frame.create(MessageType.REGISTER_REQ, reg), out);
            FrameDecoder.decodeFromStream(in); // RESP
            FrameDecoder.decodeFromStream(in); // ASSIGN

            // 1. Gửi lệnh list-agents
            ConsoleCommand cmdAgents = new ConsoleCommand("list-agents");
            FrameEncoder.encodeToStream(Frame.create(MessageType.CONSOLE_COMMAND, cmdAgents), out);

            Frame resp1 = FrameDecoder.decodeFromStream(in);
            assertNotNull(resp1);
            assertEquals(MessageType.CONSOLE_COMMAND_RESP, resp1.getType());
            ConsoleResponse r1 = resp1.parsePayload(ConsoleResponse.class);
            assertTrue(r1.isSuccess());
            assertTrue(r1.getDataJson().contains("agent-cmd"));

            // 2. Gửi lệnh list-targets
            ConsoleCommand cmdTargets = new ConsoleCommand("list-targets");
            FrameEncoder.encodeToStream(Frame.create(MessageType.CONSOLE_COMMAND, cmdTargets), out);

            Frame resp2 = FrameDecoder.decodeFromStream(in);
            assertNotNull(resp2);
            ConsoleResponse r2 = resp2.parsePayload(ConsoleResponse.class);
            assertTrue(r2.isSuccess());
            assertNotNull(r2.getDataJson());

            // 3. Gửi lệnh server-info
            ConsoleCommand cmdInfo = new ConsoleCommand("server-info");
            FrameEncoder.encodeToStream(Frame.create(MessageType.CONSOLE_COMMAND, cmdInfo), out);

            Frame resp3 = FrameDecoder.decodeFromStream(in);
            assertNotNull(resp3);
            ConsoleResponse r3 = resp3.parsePayload(ConsoleResponse.class);
            assertTrue(r3.isSuccess());
            assertTrue(r3.getDataJson().contains("uptimeSeconds"));
        }
    }

    @Test
    @DisplayName("Kiểm thử Ngắt kết nối an toàn (Graceful Disconnect)")
    void testGracefulDisconnect() throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            InputStream in = new BufferedInputStream(client.getInputStream());

            RegisterRequest reg = new RegisterRequest("agent-disc", "HOST-DISC", "Linux", "10.0.0.2", "MAC", testToken, List.of("TCP"), "1.0.0");
            FrameEncoder.encodeToStream(Frame.create(MessageType.REGISTER_REQ, reg), out);
            FrameDecoder.decodeFromStream(in); // RESP
            FrameDecoder.decodeFromStream(in); // ASSIGN
            assertEquals(1, server.getAgentManager().getActiveAgentCount());

            CountDownLatch discLatch = new CountDownLatch(1);
            server.getAgentManager().addAgentChangeListener(() -> {
                if (server.getAgentManager().getActiveAgentCount() == 0) {
                    discLatch.countDown();
                }
            });

            // Gửi MSG_DISCONNECT
            DisconnectMessage disc = DisconnectMessage.of("USER_SHUTDOWN", "Agent tắt chủ động");
            FrameEncoder.encodeToStream(Frame.create(MessageType.DISCONNECT, disc), out);

            assertTrue(discLatch.await(2, TimeUnit.SECONDS), "Agent phải được hủy đăng ký");
            assertEquals(0, server.getAgentManager().getActiveAgentCount(), "Agent phải được hủy đăng ký");
        }
    }

    @Test
    @DisplayName("Kiểm thử Phát hiện Agent chết đột tử khi quá hạn Nhịp tim (Heartbeat Timeout)")
    void testDeadAgentHeartbeatTimeout() throws Exception {
        // Tạo server với TestClock và timeout ngắn 300ms
        TestClock testClock = TestClock.of("2026-10-24T12:00:00Z");
        NcasServer shortTimeoutServer = new NcasServer(0, "127.0.0.1", testToken, 300L, testClock);
        shortTimeoutServer.start();

        try {
            try (Socket client = new Socket("127.0.0.1", shortTimeoutServer.getPort())) {
                OutputStream out = new BufferedOutputStream(client.getOutputStream());
                InputStream in = new BufferedInputStream(client.getInputStream());

                RegisterRequest reg = new RegisterRequest("agent-dead", "HOST-DEAD", "Linux", "10.0.0.3", "MAC", testToken, List.of("TCP"), "1.0.0");
                FrameEncoder.encodeToStream(Frame.create(MessageType.REGISTER_REQ, reg), out);
                FrameDecoder.decodeFromStream(in);
                FrameDecoder.decodeFromStream(in);
                assertEquals(1, shortTimeoutServer.getAgentManager().getActiveAgentCount());

                // Không gửi heartbeat ping, tua TestClock tiến 500ms không cần sleep
                testClock.advanceMillis(500);

                // Kích hoạt quét nhịp tim
                shortTimeoutServer.getAgentManager().scanHeartbeats();

                assertEquals(0, shortTimeoutServer.getAgentManager().getActiveAgentCount(),
                        "Agent phải bị xóa khỏi bảng phiên do mất nhịp tim");
            }
        } finally {
            shortTimeoutServer.stop();
        }
    }
}

package com.networkalert.console;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.networkalert.agent.AgentConfig;
import com.networkalert.agent.NcasAgent;
import com.networkalert.protocol.messages.ConsoleResponse;
import com.networkalert.server.NcasServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử tích hợp toàn diện cho Bàn điều khiển Quản trị NCAS Console (Giai đoạn 4):
 * - Kết nối tới NCAS Server qua socket TCP
 * - Thực thi các lệnh quản trị: server-info, list-agents, list-targets, check-now, rebalance
 * - Định dạng dữ liệu dạng lưới ASCII Grid Table
 * - Đăng ký luồng sự kiện Real-time Event Stream
 * - Ngắt kết nối an toàn (Graceful Disconnect)
 */
public class NcasConsoleTest {

    private NcasServer server;
    private NcasAgent agent;
    private NcasConsole console;
    private final String testToken = "TEST-CONSOLE-SECRET-123";
    private static final Gson GSON = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        // 1. Khởi động NCAS Server trên cổng động 0
        server = new NcasServer(0, "127.0.0.1", testToken);
        server.start();
        assertTrue(server.isRunning());
        assertTrue(server.getPort() > 0);

        // 2. Khởi động 1 Agent kết nối vào Server để có dữ liệu quản trị
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setServerHost("127.0.0.1");
        agentConfig.setServerPort(server.getPort());
        agentConfig.setAgentId("agent-console-test");
        agentConfig.setAuthToken(testToken);

        agent = new NcasAgent(agentConfig);
        agent.start();

        // Chờ Agent kết nối và xác thực thành công trên Server (sử dụng CountDownLatch thay vì Thread.sleep)
        long start = System.currentTimeMillis();
        while ((!agent.isConnected() || !agent.isAuthenticated() || server.getAgentManager().getActiveAgentCount() == 0 || agent.getCurrentTargets().isEmpty()) && (System.currentTimeMillis() - start < 3000)) {
            new CountDownLatch(1).await(20, TimeUnit.MILLISECONDS);
        }
        assertTrue(agent.isConnected());
        assertTrue(agent.isAuthenticated());
    }

    @AfterEach
    void tearDown() {
        if (console != null) {
            console.disconnect();
        }
        if (agent != null && agent.isRunning()) {
            agent.stop();
        }
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Kiểm thử Console kết nối tới Server và lấy thông tin server-info")
    void testConnectAndServerInfo() throws Exception {
        ConsoleConfig config = new ConsoleConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());

        console = new NcasConsole(config);
        console.connect();
        assertTrue(console.isConnected());

        ConsoleResponse resp = console.executeCommand("server-info", null);
        assertNotNull(resp);
        assertTrue(resp.isSuccess());
        assertNotNull(resp.getDataJson());
        assertTrue(resp.getDataJson().contains("uptimeSeconds"));
        assertTrue(resp.getDataJson().contains("activeAgents"));

        console.disconnect();
        assertFalse(console.isConnected());
    }

    @Test
    @DisplayName("Kiểm thử Console lấy danh sách Agent và hiển thị bảng ASCII")
    void testListAgentsAndAsciiTable() throws Exception {
        ConsoleConfig config = new ConsoleConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());

        console = new NcasConsole(config);
        console.connect();

        ConsoleResponse resp = console.executeCommand("list-agents", null);
        assertNotNull(resp);
        assertTrue(resp.isSuccess());
        assertNotNull(resp.getDataJson());
        assertTrue(resp.getDataJson().contains("agent-console-test"));

        // Kiểm tra bộ định dạng ASCII Table
        Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
        List<Map<String, Object>> agents = GSON.fromJson(resp.getDataJson(), type);
        assertEquals(1, agents.size());

        String asciiTable = AsciiTableFormatter.formatAgents(agents);
        assertNotNull(asciiTable);
        assertTrue(asciiTable.contains("+---"));
        assertTrue(asciiTable.contains("agent-console-test"));
        assertTrue(asciiTable.contains("Agent ID"));
    }

    @Test
    @DisplayName("Kiểm thử Console lấy danh sách Mục tiêu đo kiểm và hiển thị bảng ASCII")
    void testListTargetsAndAsciiTable() throws Exception {
        ConsoleConfig config = new ConsoleConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());

        console = new NcasConsole(config);
        console.connect();

        ConsoleResponse resp = console.executeCommand("list-targets", null);
        assertNotNull(resp);
        assertTrue(resp.isSuccess());
        assertNotNull(resp.getDataJson());

        Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
        List<Map<String, Object>> targets = GSON.fromJson(resp.getDataJson(), type);
        assertFalse(targets.isEmpty(), "Danh sách mục tiêu từ Server không được rỗng");

        String asciiTable = AsciiTableFormatter.formatTargets(targets);
        assertNotNull(asciiTable);
        assertTrue(asciiTable.contains("+---"));
        assertTrue(asciiTable.contains("Tên mục tiêu"));
        assertTrue(asciiTable.contains("Host:Port"));
    }

    @Test
    @DisplayName("Kiểm thử Lệnh đo tức thời (check-now / Force Check) qua Console")
    void testForceCheckCommand() throws Exception {
        ConsoleConfig config = new ConsoleConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());

        console = new NcasConsole(config);
        console.connect();

        // Lấy 1 target được phân công cho Agent
        int targetId = agent.getCurrentTargets().keySet().iterator().next();

        Map<String, String> params = new HashMap<>();
        params.put("targetId", String.valueOf(targetId));

        ConsoleResponse resp = console.executeCommand("check-now", params);
        assertNotNull(resp);
        assertTrue(resp.isSuccess(), "Lệnh Force Check gửi đi phải thành công: " + resp.getMessage());
        assertTrue(resp.getMessage().contains(String.valueOf(targetId)));
    }

    @Test
    @DisplayName("Kiểm thử Lệnh tái phân bổ tải mục tiêu (rebalance) qua Console")
    void testRebalanceCommand() throws Exception {
        ConsoleConfig config = new ConsoleConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());

        console = new NcasConsole(config);
        console.connect();

        ConsoleResponse resp = console.executeCommand("rebalance", null);
        assertNotNull(resp);
        assertTrue(resp.isSuccess());
        assertTrue(resp.getMessage().contains("tái phân bổ") || resp.getMessage().contains("mục tiêu"));
    }

    @Test
    @DisplayName("Kiểm thử Đăng ký nhận luồng sự kiện Real-time (subscribeStream)")
    void testSubscribeStream() throws Exception {
        ConsoleConfig config = new ConsoleConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());

        console = new NcasConsole(config);
        console.connect();

        assertFalse(console.isStreaming());
        console.subscribeStream();
        assertTrue(console.isStreaming());

        console.setStreaming(false);
        assertFalse(console.isStreaming());
    }
}

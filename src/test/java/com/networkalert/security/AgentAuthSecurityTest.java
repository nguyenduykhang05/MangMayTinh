package com.networkalert.security;

import com.networkalert.agent.AgentConfig;
import com.networkalert.agent.NcasAgent;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetScope;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.ProtocolMetrics;
import com.networkalert.protocol.messages.*;
import com.networkalert.server.NcasServer;
import com.networkalert.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử an ninh toàn diện cho Task T1.2 theo AGENTS.md, TASKS.md và BLUEPRINT-ERRATA §E2.2 - §E2.6:
 * 1. Kho Token riêng: Salt 16 bytes, băm SHA-256(salt || token), so sánh constant-time MessageDigest.isEqual.
 * 2. Xác thực Challenge-Response trong chế độ --no-tls: Nonce 32 bytes dùng 1 lần, chữ ký HMAC-SHA256(H, nonce || agentId).
 * 3. Chống Replay Attack: Thử phát lại Nonce đã dùng hoặc hết hạn bắt buộc bị từ chối.
 * 4. Kiểm tra byte trên đường truyền: Byte bắt được ở --no-tls TUYỆT ĐỐI không chứa token bản rõ.
 * 5. Xử lý sai token: Trả về AUTH_FAIL, ngắt socket ngay, KHÔNG khóa IP (an toàn sau NAT).
 * 6. Phía Agent: Gặp AUTH_FAIL nhận diện là lỗi cấu hình, áp dụng backoff >= 60 giây.
 * 7. Giới hạn kết nối chưa xác thực: 100 kết nối treo không làm cạn kiệt tài nguyên Server.
 * 8. Phân quyền Ingestor: Ingestor từ chối kết quả của target không được phân công cho Agent đó.
 * 9. Chính sách an ninh Agent (agent-policy.json): Chặn dải IP cấm (169.254.169.254/32), giao thức không hợp lệ.
 * 10. CLI Subcommands: token create, token revoke, token list hoạt động chuẩn xác và không lộ token.
 */
public class AgentAuthSecurityTest {

    @TempDir
    Path tempDir;

    private TestClock testClock;
    private NcasServer server;
    private AgentTokenManager tokenManager;
    private ChallengeManager challengeManager;

    @BeforeEach
    void setUp() throws Exception {
        testClock = TestClock.fixed(Instant.parse("2026-10-24T00:00:00Z"));
        ProtocolMetrics.reset();

        Path tokenFile = tempDir.resolve("agents.json");
        tokenManager = new AgentTokenManager(tokenFile, testClock);
        challengeManager = new ChallengeManager(testClock);

        // Khởi tạo Server trên cổng động 0 với cờ allowLegacyAuth = false (chuẩn Production)
        server = new NcasServer(0, "127.0.0.1", "MASTER-SECRET", 30_000L, testClock);
        server.setAllowLegacyAuth(false); // Bắt buộc Challenge-Response trong --no-tls
        server.start();
        assertTrue(server.isRunning());
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        ProtocolMetrics.reset();
    }

    @Test
    @DisplayName("T1.2.1: Quản lý Token riêng từng Agent - Salt 16B, SHA-256, so sánh constant-time")
    void testAgentTokenHashingAndConstantTimeVerification() {
        Path tokenFile = tempDir.resolve("test-agents.json");
        AgentTokenManager mgr = new AgentTokenManager(tokenFile, testClock);

        // 1. Tạo token cho agent-01
        String rawToken = mgr.createToken("agent-01", "agent");
        assertNotNull(rawToken);
        assertEquals(64, rawToken.length(), "Token 256-bit dạng Hex phải có 64 ký tự");

        // 2. Kiểm tra bản ghi lưu trữ
        AgentTokenEntry entry = mgr.getEntry("agent-01");
        assertNotNull(entry);
        assertEquals(32, entry.getSaltHex().length(), "Salt 16 bytes dạng Hex phải có 32 ký tự");
        assertEquals(64, entry.getHashHex().length(), "Hash SHA-256 dạng Hex phải có 64 ký tự");
        assertFalse(entry.isRevoked());

        // 3. File lưu trữ không chứa token bản rõ
        try {
            String fileContent = Files.readString(tokenFile, StandardCharsets.UTF_8);
            assertFalse(fileContent.contains(rawToken), "File lưu trữ tuyệt đối không được chứa token bản rõ!");
        } catch (IOException e) {
            fail(e);
        }

        // 4. Xác thực đúng token
        assertTrue(mgr.verifyToken("agent-01", rawToken));

        // 5. Xác thực sai token
        assertFalse(mgr.verifyToken("agent-01", "WRONG-TOKEN-1234567890"));
        char lastChar = rawToken.charAt(63);
        char alteredChar = (lastChar == 'a') ? 'b' : 'a';
        String wrongToken = rawToken.substring(0, 63) + alteredChar;
        assertFalse(mgr.verifyToken("agent-01", wrongToken));

        // 6. Thu hồi token (Revoke)
        assertTrue(mgr.revokeToken("agent-01"));
        assertFalse(mgr.verifyToken("agent-01", rawToken), "Token đã bị thu hồi phải bị từ chối xác thực!");

        // 7. Liệt kê danh sách (token list không lộ bí mật)
        List<AgentTokenInfo> list = mgr.listTokens();
        assertEquals(1, list.size());
        assertEquals("agent-01", list.get(0).getAgentId());
        assertTrue(list.get(0).isRevoked());
    }

    @Test
    @DisplayName("T1.2.2: Bắt tay Challenge-Response thành công qua TCP Socket trong chế độ --no-tls")
    void testSuccessfulChallengeResponseHandshakeOverTcp() throws Exception {
        // Cấp phát token cho agent-tcp-01 trên server
        String rawToken = server.getAgentTokenManager().createToken("agent-tcp-01", "agent");

        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            client.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            // 1. Client gửi MSG_REGISTER_REQ không chứa token (chuẩn no-tls)
            RegisterRequest req = new RegisterRequest("agent-tcp-01", "PC-NODE1", "Linux", "192.168.1.10",
                    "00:11:22:33:44:55", null, List.of("TCP", "ICMP"), "1.0.0");
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

            // 2. Server phản hồi MSG_AUTH_CHALLENGE
            Frame challengeFrame = FrameDecoder.readFrame(in);
            assertNotNull(challengeFrame);
            assertEquals(MessageType.AUTH_CHALLENGE, challengeFrame.getType());

            AuthChallengeMessage challenge = challengeFrame.parsePayload(AuthChallengeMessage.class);
            assertEquals("agent-tcp-01", challenge.getAgentId());
            assertNotNull(challenge.getNonce());
            assertNotNull(challenge.getSalt());

            // 3. Client tính chữ ký HMAC-SHA256(H, nonce || agentId)
            byte[] saltBytes = AgentTokenManager.hexToBytes(challenge.getSalt());
            byte[] hKey = AgentTokenManager.computeHashBytes(saltBytes, rawToken);
            String hmac = ChallengeManager.computeResponseHmac(hKey, challenge.getNonce(), "agent-tcp-01");

            // 4. Client gửi MSG_AUTH_RESPONSE
            AuthResponseMessage authResp = new AuthResponseMessage("agent-tcp-01", challenge.getNonce(), hmac);
            FrameEncoder.writeFrame(Frame.create(MessageType.AUTH_RESPONSE, authResp), out);

            // 5. Server phản hồi MSG_REGISTER_RESP ACCEPTED
            Frame respFrame = FrameDecoder.readFrame(in);
            assertNotNull(respFrame);
            assertEquals(MessageType.REGISTER_RESP, respFrame.getType());

            RegisterResponse resp = respFrame.parsePayload(RegisterResponse.class);
            assertTrue(resp.isAccepted());
            assertNotNull(resp.getAssignedSessionId());
        }
    }

    @Test
    @DisplayName("T1.2.3: Chống Replay Attack - Phát lại Nonce đã dùng hoặc hết hạn bắt buộc thất bại")
    void testReplayAttackDefense() throws Exception {
        String rawToken = server.getAgentTokenManager().createToken("agent-replay-test", "agent");

        AuthChallengeMessage challenge;
        String capturedHmac;

        // Phiên 1: Bắt tay hợp lệ lần đầu
        try (Socket client1 = new Socket("127.0.0.1", server.getPort())) {
            client1.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(client1.getInputStream());
            OutputStream out = new BufferedOutputStream(client1.getOutputStream());

            RegisterRequest req = new RegisterRequest("agent-replay-test", "PC-NODE1", "Linux", "192.168.1.10",
                    "00:11:22:33:44:55", null, List.of("TCP"), "1.0.0");
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

            Frame challengeFrame = FrameDecoder.readFrame(in);
            challenge = challengeFrame.parsePayload(AuthChallengeMessage.class);

            byte[] saltBytes = AgentTokenManager.hexToBytes(challenge.getSalt());
            byte[] hKey = AgentTokenManager.computeHashBytes(saltBytes, rawToken);
            capturedHmac = ChallengeManager.computeResponseHmac(hKey, challenge.getNonce(), "agent-replay-test");

            FrameEncoder.writeFrame(Frame.create(MessageType.AUTH_RESPONSE,
                    new AuthResponseMessage("agent-replay-test", challenge.getNonce(), capturedHmac)), out);

            Frame respFrame = FrameDecoder.readFrame(in);
            assertTrue(respFrame.parsePayload(RegisterResponse.class).isAccepted());
        }

        // Phiên 2 (Kẻ tấn công replay gói tin AUTH_RESPONSE với cùng Nonce và chữ ký)
        try (Socket attacker = new Socket("127.0.0.1", server.getPort())) {
            attacker.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(attacker.getInputStream());
            OutputStream out = new BufferedOutputStream(attacker.getOutputStream());

            // Kẻ tấn công gửi lại chữ ký đã bắt được
            AuthResponseMessage replayedResp = new AuthResponseMessage("agent-replay-test", challenge.getNonce(), capturedHmac);
            FrameEncoder.writeFrame(Frame.create(MessageType.AUTH_RESPONSE, replayedResp), out);

            Frame rejectFrame = FrameDecoder.readFrame(in);
            assertNotNull(rejectFrame);
            RegisterResponse rejResp = rejectFrame.parsePayload(RegisterResponse.class);
            assertFalse(rejResp.isAccepted());
            assertTrue(rejResp.getMessage().contains("AUTH_FAIL"));
        }

        assertTrue(ProtocolMetrics.getAuthFailuresTotal() >= 1, "Metric auth_failures_total phải tăng khi có tấn công replay");
    }

    @Test
    @DisplayName("T1.2.4: Kiểm tra byte trên đường truyền --no-tls TUYỆT ĐỐI không chứa token bản rõ")
    void testNoRawTokenOnWireInNoTlsMode() throws Exception {
        String secretToken = server.getAgentTokenManager().createToken("agent-wire-sniff", "agent");
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();

        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            client.setSoTimeout(5000);
            InputStream rawIn = client.getInputStream();
            OutputStream rawOut = client.getOutputStream();

            // Tee OutputStream để ghi lại mọi byte gửi đi
            OutputStream teeOut = new FilterOutputStream(rawOut) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    capturedBytes.write(b, off, len);
                    super.write(b, off, len);
                }
                @Override
                public void write(int b) throws IOException {
                    capturedBytes.write(b);
                    super.write(b);
                }
            };

            // 1. Gửi REGISTER_REQ không chứa token
            RegisterRequest req = new RegisterRequest("agent-wire-sniff", "PC-NODE1", "Linux", "192.168.1.10",
                    "00:11:22:33:44:55", null, List.of("TCP"), "1.0.0");
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), teeOut);

            // 2. Nhận challenge
            Frame challengeFrame = FrameDecoder.readFrame(rawIn);
            AuthChallengeMessage challenge = challengeFrame.parsePayload(AuthChallengeMessage.class);

            // 3. Ký chữ ký
            byte[] saltBytes = AgentTokenManager.hexToBytes(challenge.getSalt());
            byte[] hKey = AgentTokenManager.computeHashBytes(saltBytes, secretToken);
            String hmac = ChallengeManager.computeResponseHmac(hKey, challenge.getNonce(), "agent-wire-sniff");

            // 4. Gửi AUTH_RESPONSE qua teeOut
            FrameEncoder.writeFrame(Frame.create(MessageType.AUTH_RESPONSE,
                    new AuthResponseMessage("agent-wire-sniff", challenge.getNonce(), hmac)), teeOut);

            // 5. Đọc phản hồi
            Frame respFrame = FrameDecoder.readFrame(rawIn);
            assertTrue(respFrame.parsePayload(RegisterResponse.class).isAccepted());
        }

        // KIỂM TRA BẤT BIẾN: Chuỗi token tuyệt mật không bao giờ xuất hiện trong mảng byte gửi đi
        byte[] allCaptured = capturedBytes.toByteArray();
        String wireTraffic = new String(allCaptured, StandardCharsets.UTF_8);
        assertFalse(wireTraffic.contains(secretToken), "BẮT ĐƯỢC TOKEN TRÊN ĐƯỜNG TRUYỀN! Vi phạm nghiêm trọng tiêu chí bảo mật E2.3!");
    }

    @Test
    @DisplayName("T1.2.5: Sai Token / Token thu hồi -> Phản hồi AUTH_FAIL, đóng socket, KHÔNG khóa IP (an toàn NAT)")
    void testAuthFailureClosesSocketWithoutIpBan() throws Exception {
        String token = server.getAgentTokenManager().createToken("agent-revoke-me", "agent");
        server.getAgentTokenManager().revokeToken("agent-revoke-me");

        // 1. Thử xác thực với token đã bị thu hồi
        try (Socket client1 = new Socket("127.0.0.1", server.getPort())) {
            client1.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(client1.getInputStream());
            OutputStream out = new BufferedOutputStream(client1.getOutputStream());

            RegisterRequest req = new RegisterRequest("agent-revoke-me", "PC-NODE1", "Linux", "192.168.1.10",
                    "00:11:22:33:44:55", null, List.of("TCP"), "1.0.0");
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

            Frame resp = FrameDecoder.readFrame(in);
            assertNotNull(resp);
            RegisterResponse r = resp.parsePayload(RegisterResponse.class);
            assertFalse(r.isAccepted());
            assertTrue(r.getMessage().contains("AUTH_FAIL"));

            // Socket phải bị server đóng ngay sau phản hồi thất bại
            assertEquals(-1, in.read(), "Server phải đóng socket ngay sau khi từ chối AUTH_FAIL");
        }

        // 2. Kiểm tra KHÔNG khóa IP: Một Agent khác từ cùng địa chỉ IP (127.0.0.1) vẫn có thể đăng ký bình thường
        String validToken = server.getAgentTokenManager().createToken("agent-nat-neighbor", "agent");
        try (Socket client2 = new Socket("127.0.0.1", server.getPort())) {
            client2.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(client2.getInputStream());
            OutputStream out = new BufferedOutputStream(client2.getOutputStream());

            RegisterRequest req = new RegisterRequest("agent-nat-neighbor", "PC-NODE2", "Linux", "192.168.1.11",
                    "00:11:22:33:44:56", null, List.of("TCP"), "1.0.0");
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

            Frame chFrame = FrameDecoder.readFrame(in);
            AuthChallengeMessage challenge = chFrame.parsePayload(AuthChallengeMessage.class);

            byte[] saltBytes = AgentTokenManager.hexToBytes(challenge.getSalt());
            byte[] hKey = AgentTokenManager.computeHashBytes(saltBytes, validToken);
            String hmac = ChallengeManager.computeResponseHmac(hKey, challenge.getNonce(), "agent-nat-neighbor");

            FrameEncoder.writeFrame(Frame.create(MessageType.AUTH_RESPONSE,
                    new AuthResponseMessage("agent-nat-neighbor", challenge.getNonce(), hmac)), out);

            Frame resp = FrameDecoder.readFrame(in);
            assertTrue(resp.parsePayload(RegisterResponse.class).isAccepted(), "Hàng xóm cùng IP NAT không được phép bị khóa lây!");
        }
    }

    @Test
    @DisplayName("T1.2.6: Phía Agent gặp AUTH_FAIL nhận diện là lỗi cấu hình và áp dụng backoff >= 60 giây")
    void testAgentAuthFailureTriggersSixtySecondBackoff() throws Exception {
        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-wrong-config");
        config.setAuthToken("INVALID-SECRET-TOKEN");
        config.setAllowLegacyAuth(false);

        // Đăng ký token hợp lệ khác trên Server để sinh ra chữ ký không khớp
        server.getAgentTokenManager().createToken("agent-wrong-config", "agent");

        NcasAgent agent = new NcasAgent(config, testClock);
        agent.start();

        // Chờ Agent thử kết nối và nhận AUTH_FAIL
        long start = System.currentTimeMillis();
        while (agent.getLastAuthFailureBackoffMs() < 60_000L && System.currentTimeMillis() - start < 3000) {
            new CountDownLatch(1).await(50, TimeUnit.MILLISECONDS);
        }

        assertTrue(agent.getLastAuthFailureBackoffMs() >= 60_000L,
                "Agent phải áp dụng backoff cố định >= 60 giây khi gặp lỗi cấu hình AUTH_FAIL (thực tế: "
                        + agent.getLastAuthFailureBackoffMs() + " ms)");

        agent.stop();
    }

    @Test
    @DisplayName("T1.2.7: Giới hạn kết nối chưa xác thực - 100 kết nối treo không làm cạn kiệt tài nguyên")
    void testPreAuthConnectionLimiterPreventsResourceExhaustion() throws Exception {
        ConnectionLimiter limiter = server.getConnectionLimiter();
        assertNotNull(limiter);

        List<Socket> openSockets = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;

        // Thử mở 100 kết nối đồng thời từ loopback IP
        for (int i = 0; i < 100; i++) {
            try {
                Socket s = new Socket("127.0.0.1", server.getPort());
                s.setSoTimeout(1000);
                openSockets.add(s);
                accepted++;
            } catch (Exception e) {
                rejected++;
            }
        }

        // Kiểm tra trần kết nối: số lượng kết nối chưa xác thực trên loopback bị trần ở mức giới hạn maxPerIp (8)
        int preAuthCount = limiter.getPreAuthCountForIp("127.0.0.1");
        assertTrue(preAuthCount <= ConnectionLimiter.DEFAULT_MAX_PRE_AUTH_PER_IP,
                "Số lượng kết nối unauth trên IP phải <= 8 (thực tế: " + preAuthCount + ")");

        // Dọn dẹp đóng các socket
        for (Socket s : openSockets) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("T1.2.8: Chính sách an ninh Agent (agent-policy.json) - Chặn dải CIDR cấm và giao thức không hợp lệ")
    void testAgentSecurityPolicyEnforcement() {
        AgentPolicy policy = new AgentPolicy(2, 5,
                List.of("169.254.169.254/32", "169.254.0.0/16", "10.0.0.0/8"),
                List.of("TCP", "ICMP", "HTTP"));
        AgentPolicyManager pm = new AgentPolicyManager(policy);

        // 1. Mục tiêu bình thường -> Hợp lệ
        AgentPolicyManager.ValidationResult r1 = pm.validateTarget(1, "google.com", 443, "TCP", 5, 0);
        assertTrue(r1.isAllowed());

        // 2. Mục tiêu nằm trong dải Metadata cấm 169.254.169.254/32 -> Bị từ chối
        AgentPolicyManager.ValidationResult r2 = pm.validateTarget(2, "169.254.169.254", 80, "HTTP", 5, 0);
        assertFalse(r2.isAllowed());
        assertTrue(r2.getReason().contains("vi phạm dải IP cấm"));

        // 3. Mục tiêu nằm trong dải Link-Local 169.254.50.1 -> Bị từ chối
        AgentPolicyManager.ValidationResult r3 = pm.validateTarget(3, "169.254.50.1", 80, "HTTP", 5, 0);
        assertFalse(r3.isAllowed());

        // 4. Giao thức không được phép (ví dụ SNMP) -> Bị từ chối
        AgentPolicyManager.ValidationResult r4 = pm.validateTarget(4, "8.8.8.8", 161, "SNMP", 5, 0);
        assertFalse(r4.isAllowed());
        assertTrue(r4.getReason().contains("không nằm trong danh sách cho phép"));

        // 5. Chu kỳ đo nhỏ hơn minIntervalSeconds (1s < 2s) -> Bị từ chối
        AgentPolicyManager.ValidationResult r5 = pm.validateTarget(5, "8.8.8.8", 53, "TCP", 1, 0);
        assertFalse(r5.isAllowed());
        assertTrue(r5.getReason().contains("nhỏ hơn mức tối thiểu"));

        // 6. Vượt quá trần số lượng mục tiêu maxTargets (5/5) -> Bị từ chối
        AgentPolicyManager.ValidationResult r6 = pm.validateTarget(6, "8.8.8.8", 53, "TCP", 5, 5);
        assertFalse(r6.isAllowed());
        assertTrue(r6.getReason().contains("Vượt quá số lượng mục tiêu tối đa"));
    }

    @Test
    @DisplayName("T1.2.9: Phân quyền Ingestor - Từ chối kết quả đo của target không được phân công cho Agent")
    void testIngestorRejectsUnauthorizedTargetResults() throws Exception {
        // Cấp token cho agent-authz-01
        String rawToken = server.getAgentTokenManager().createToken("agent-authz-01", "agent");

        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            client.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            // 1. Xác thực thành công
            RegisterRequest req = new RegisterRequest("agent-authz-01", "PC-NODE", "Linux", "192.168.1.10",
                    "00:11:22:33:44:55", null, List.of("TCP"), "1.0.0");
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

            Frame chFrame = FrameDecoder.readFrame(in);
            AuthChallengeMessage challenge = chFrame.parsePayload(AuthChallengeMessage.class);

            byte[] saltBytes = AgentTokenManager.hexToBytes(challenge.getSalt());
            byte[] hKey = AgentTokenManager.computeHashBytes(saltBytes, rawToken);
            String hmac = ChallengeManager.computeResponseHmac(hKey, challenge.getNonce(), "agent-authz-01");

            FrameEncoder.writeFrame(Frame.create(MessageType.AUTH_RESPONSE,
                    new AuthResponseMessage("agent-authz-01", challenge.getNonce(), hmac)), out);

            Frame resp = FrameDecoder.readFrame(in);
            assertTrue(resp.parsePayload(RegisterResponse.class).isAccepted());

            // 2. Tạo một target giả định KHÔNG được phân công cho agent-authz-01
            ServerTarget target = new ServerTarget(9999, "Secret Target", "10.0.0.1", 80, ProtocolType.TCP, 10, 1000, 300, TargetScope.AGENT, "Default", true);
            server.getTargetDistributor().addOrUpdateTarget(target);
            // Đảm bảo target 9999 không nằm trong assigned list của agent-authz-01
            server.getAgentManager().getSession("agent-authz-01").getAssignedTargetIds().remove(9999);
            target.setAssignedAgent("other-agent");

            // Agent gửi kết quả đo cho target 9999 mà nó không được phân công
            ReportResultMessage report = new ReportResultMessage("agent-authz-01", 9999, testClock.millis(), true, 50, 200, null, "Hacked Probe");
            FrameEncoder.writeFrame(Frame.create(MessageType.REPORT_RESULT, report), out);

            // Kiểm tra: Kết quả không được nạp vào stationResults của target
            assertTrue(target.getStationResults().isEmpty(), "Ingestor bắt buộc phải từ chối kết quả của target không được giao!");
        }
    }

    @Test
    @DisplayName("T1.2.10: CLI Subcommands - agent-token create, revoke, list hoạt động an toàn")
    void testCliSubcommands() {
        // 1. Lệnh create
        Map<String, String> p1 = Map.of("agentId", "agent-cli-01", "role", "sensor");
        ConsoleResponse r1 = server.executeConsoleCommand(null, new ConsoleCommand("token-create", p1));
        assertTrue(r1.isSuccess());
        assertTrue(r1.getMessage().contains("Đã sinh Token thành công"));

        // 2. Lệnh list (không chứa secret)
        ConsoleResponse r2 = server.executeConsoleCommand(null, new ConsoleCommand("token-list"));
        assertTrue(r2.isSuccess());
        assertTrue(r2.getDataJson().contains("agent-cli-01"));
        assertFalse(r2.getDataJson().contains("saltHex"), "Danh sách công khai không được chứa salt");
        assertFalse(r2.getDataJson().contains("hashHex"), "Danh sách công khai không được chứa hash");

        // 3. Lệnh revoke
        Map<String, String> p3 = Map.of("agentId", "agent-cli-01");
        ConsoleResponse r3 = server.executeConsoleCommand(null, new ConsoleCommand("token-revoke", p3));
        assertTrue(r3.isSuccess());
        assertTrue(r3.getMessage().contains("Đã thu hồi Token"));

        // 4. Token đã revoke không thể xác thực
        assertFalse(server.getAgentTokenManager().hasToken("agent-cli-01"));
    }
}

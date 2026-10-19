package com.networkalert.security;

import com.networkalert.agent.AgentConfig;
import com.networkalert.agent.NcasAgent;
import com.networkalert.console.ConsoleConfig;
import com.networkalert.console.NcasConsole;
import com.networkalert.model.ServerTarget;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.ConsoleCommand;
import com.networkalert.protocol.messages.ConsoleResponse;
import com.networkalert.protocol.messages.RegisterRequest;
import com.networkalert.protocol.messages.RegisterResponse;
import com.networkalert.server.NcasServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử an ninh TLS 1.2/1.3 và ghim Fingerprint SHA-256 cho NCAS 2.0
 * (Theo TASKS T1.3 & BLUEPRINT-ERRATA §E2.1).
 */
public class TlsSecurityTest {

    private static Path tempDir;
    private static Path serverKeystorePath;
    private static Path rotatedKeystorePath;
    private static final char[] KEYSTORE_PASS = "test-tls-pass-123".toCharArray();
    private static String serverCertFingerprint;
    private static String rotatedCertFingerprint;

    private static final Clock testClock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneId.of("UTC"));

    @BeforeAll
    static void initKeystores(@TempDir Path tempPath) throws Exception {
        tempDir = tempPath;
        serverKeystorePath = tempDir.resolve("test-server-keystore.p12");
        rotatedKeystorePath = tempDir.resolve("test-rotated-keystore.p12");

        // 1. Tạo Keystore máy chủ 1
        TlsContextFactory.generateSelfSignedKeystore(serverKeystorePath, KEYSTORE_PASS, "ncas-server-1", 30);
        serverCertFingerprint = TlsContextFactory.getCertificateFingerprint(serverKeystorePath, KEYSTORE_PASS);
        assertNotNull(serverCertFingerprint);
        assertTrue(serverCertFingerprint.startsWith("sha256:"));

        // 2. Tạo Keystore máy chủ 2 (dùng cho kịch bản xoay chứng chỉ)
        TlsContextFactory.generateSelfSignedKeystore(rotatedKeystorePath, KEYSTORE_PASS, "ncas-server-2", 30);
        rotatedCertFingerprint = TlsContextFactory.getCertificateFingerprint(rotatedKeystorePath, KEYSTORE_PASS);
        assertNotNull(rotatedCertFingerprint);
        assertTrue(rotatedCertFingerprint.startsWith("sha256:"));

        assertNotEquals(serverCertFingerprint, rotatedCertFingerprint, "Hai chứng chỉ tạo riêng biệt phải có fingerprint khác nhau!");
    }

    @Test
    @DisplayName("T1.3.1 - Ghim Fingerprint đúng: Kết nối và bắt tay TLS thành công")
    void testValidFingerprintPinningConnectsSuccessfully() throws Exception {
        NcasServer server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setTlsEnabled(true);
        server.setKeystorePath(serverKeystorePath.toAbsolutePath().toString());
        server.setKeystorePassword(KEYSTORE_PASS);
        server.start();

        try {
            SSLContext clientCtx = TlsContextFactory.createClientSSLContext(Collections.singletonList(serverCertFingerprint));
            try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket()) {
                clientSocket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5000);
                TlsContextFactory.configureClientSocket(clientSocket);
                clientSocket.startHandshake();

                assertTrue(clientSocket.isConnected());
                assertNotNull(clientSocket.getSession());
                String proto = clientSocket.getSession().getProtocol();
                assertTrue(proto.equals("TLSv1.3") || proto.equals("TLSv1.2"), "Phải thương lượng TLS 1.2 hoặc 1.3, thực tế: " + proto);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("T1.3.2 - Ghim Fingerprint sai: Bắt tay TLS bị từ chối ngay lập tức")
    void testInvalidFingerprintPinningRejected() throws Exception {
        NcasServer server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setTlsEnabled(true);
        server.setKeystorePath(serverKeystorePath.toAbsolutePath().toString());
        server.setKeystorePassword(KEYSTORE_PASS);
        server.start();

        try {
            String fakeFingerprint = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            SSLContext clientCtx = TlsContextFactory.createClientSSLContext(Collections.singletonList(fakeFingerprint));

            try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket()) {
                clientSocket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5000);
                TlsContextFactory.configureClientSocket(clientSocket);

                assertThrows(Exception.class, clientSocket::startHandshake,
                        "Fingerprint sai bắt buộc phải ném lỗi bắt tay TLS!");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("T1.3.3 - Nghiêm cấm TLS <= 1.1: Máy chủ từ chối kết nối TLS 1.0 và TLS 1.1")
    void testTlsLegacyProtocolsStrictlyForbidden() throws Exception {
        NcasServer server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setTlsEnabled(true);
        server.setKeystorePath(serverKeystorePath.toAbsolutePath().toString());
        server.setKeystorePassword(KEYSTORE_PASS);
        server.start();

        try {
            // Khởi tạo client chỉ cho phép TLSv1.1
            SSLContext clientCtx = SSLContext.getInstance("TLSv1.1");
            FingerprintTrustManager tm = new FingerprintTrustManager(serverCertFingerprint);
            clientCtx.init(null, new TrustManager[] { tm }, new SecureRandom());

            try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket()) {
                clientSocket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5000);
                clientSocket.setEnabledProtocols(new String[] { "TLSv1.1" });

                assertThrows(SSLException.class, clientSocket::startHandshake,
                        "Máy chủ phải từ chối kết nối có giao thức TLS <= 1.1!");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("T1.3.4 - Xoay chứng chỉ không gián đoạn: Hỗ trợ nhiều Fingerprint đồng thời")
    void testZeroDowntimeCertificateRotation() throws Exception {
        // Cấu hình danh sách chấp nhận cả 2 Fingerprint: [cert1, cert2]
        List<String> pinnedList = Arrays.asList(serverCertFingerprint, rotatedCertFingerprint);

        // 1. Máy chủ chạy chứng chỉ cũ (cert1)
        NcasServer server1 = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server1.setTlsEnabled(true);
        server1.setKeystorePath(serverKeystorePath.toAbsolutePath().toString());
        server1.setKeystorePassword(KEYSTORE_PASS);
        server1.start();

        try {
            SSLContext clientCtx = TlsContextFactory.createClientSSLContext(pinnedList);
            try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket()) {
                clientSocket.connect(new InetSocketAddress("127.0.0.1", server1.getPort()), 5000);
                TlsContextFactory.configureClientSocket(clientSocket);
                clientSocket.startHandshake();
                assertTrue(clientSocket.isConnected());
            }
        } finally {
            server1.stop();
        }

        // 2. Máy chủ xoay sang chứng chỉ mới (cert2)
        NcasServer server2 = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server2.setTlsEnabled(true);
        server2.setKeystorePath(rotatedKeystorePath.toAbsolutePath().toString());
        server2.setKeystorePassword(KEYSTORE_PASS);
        server2.start();

        try {
            SSLContext clientCtx = TlsContextFactory.createClientSSLContext(pinnedList);
            try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket()) {
                clientSocket.connect(new InetSocketAddress("127.0.0.1", server2.getPort()), 5000);
                TlsContextFactory.configureClientSocket(clientSocket);
                clientSocket.startHandshake();
                assertTrue(clientSocket.isConnected(), "Client hỗ trợ danh sách ghim phải kết nối thành công tới chứng chỉ xoay mới!");
            }
        } finally {
            server2.stop();
        }
    }

    @Test
    @DisplayName("T1.3.5 - Agent và Server kết nối mã hóa TLS đầu cuối với Token gửi trong TLS")
    void testAgentServerEndToEndTlsAuthentication() throws Exception {
        NcasServer server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setTlsEnabled(true);
        server.setKeystorePath(serverKeystorePath.toAbsolutePath().toString());
        server.setKeystorePassword(KEYSTORE_PASS);

        // Đăng ký token riêng cho Agent trong AgentTokenManager
        String aId = "agent-tls-node-01";
        String rawToken = server.getAgentTokenManager().createToken(aId, "AGENT");
        server.start();

        NcasAgent agent = null;
        try {
            AgentConfig config = new AgentConfig();
            config.setAgentId(aId);
            config.setAuthToken(rawToken);
            config.setServerHost("127.0.0.1");
            config.setServerPort(server.getPort());
            config.setTlsEnabled(true);
            config.addServerFingerprint(serverCertFingerprint);
            config.setHeartbeatIntervalSeconds(5);

            agent = new NcasAgent(config);
            agent.start();

            // Chờ Agent hoàn tất bắt tay TLS và đăng ký thành công (dùng parkNanos thay Thread.sleep theo AGENTS.md §3)
            int waited = 0;
            while (!agent.isAuthenticated() && waited < 40) {
                java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L); // 100ms
                waited++;
            }

            assertTrue(agent.isConnected(), "Agent phải kết nối TCP thành công qua TLS");
            assertTrue(agent.isAuthenticated(), "Agent phải xác thực thành công trong kênh an toàn TLS");
            assertNotNull(agent.getAssignedSessionId());
        } finally {
            if (agent != null) agent.stop();
            server.stop();
        }
    }

    @Test
    @DisplayName("T1.3.6 - Console thực thi lệnh quản trị qua kênh bảo mật TLS ghim Fingerprint")
    void testConsoleCommandOverTls() throws Exception {
        NcasServer server = new NcasServer(0, "127.0.0.1", "CONSOLE-TOKEN", 30_000L, testClock);
        server.setTlsEnabled(true);
        server.setKeystorePath(serverKeystorePath.toAbsolutePath().toString());
        server.setKeystorePassword(KEYSTORE_PASS);
        server.start();

        NcasConsole console = null;
        try {
            ConsoleConfig config = new ConsoleConfig();
            config.setServerHost("127.0.0.1");
            config.setServerPort(server.getPort());
            config.setAuthToken("CONSOLE-TOKEN");
            config.setTlsEnabled(true);
            config.addServerFingerprint(serverCertFingerprint);

            console = new NcasConsole(config);
            console.connect();
            assertTrue(console.isConnected(), "Console phải kết nối TLS thành công");

            // Thực thi lệnh list-targets qua kênh TLS
            ConsoleResponse resp = console.executeCommand("list-targets", Collections.emptyMap());
            assertNotNull(resp);
            assertTrue(resp.isSuccess(), "Lệnh console qua TLS phải thành công");
        } finally {
            if (console != null) console.disconnect();
            server.stop();
        }
    }

    @Test
    @DisplayName("T1.3.7 - FingerprintTrustManager: Chuẩn hóa linh hoạt mọi định dạng fingerprint")
    void testFingerprintNormalization() {
        String baseHex = "8f61fbdaa9e146ff8f4328ec4c7f60a7f7932272655099bb4ace94279d7c41b2";
        String withPrefix = "sha256:" + baseHex;
        String withColons = "8F:61:FB:DA:A9:E1:46:FF:8F:43:28:EC:4C:7F:60:A7:F7:93:22:72:65:50:99:BB:4A:CE:94:27:9D:7C:41:B2";
        String mixed = "sha256:8f:61:fb:da:A9:E1:46:ff:8f:43:28:ec:4c:7f:60:a7:f7:93:22:72:65:50:99:bb:4a:ce:94:27:9d:7c:41:b2";

        assertEquals(baseHex, FingerprintTrustManager.normalizeFingerprint(withPrefix));
        assertEquals(baseHex, FingerprintTrustManager.normalizeFingerprint(withColons));
        assertEquals(baseHex, FingerprintTrustManager.normalizeFingerprint(mixed));

        FingerprintTrustManager tm = new FingerprintTrustManager(withPrefix, withColons, mixed);
        assertEquals(3, tm.getPinnedFingerprints().size());
        for (String fp : tm.getPinnedFingerprints()) {
            assertEquals("sha256:" + baseHex, fp);
        }

        // Kiểm tra ném lỗi khi định dạng không phải hex hợp lệ
        assertThrows(IllegalArgumentException.class, () -> new FingerprintTrustManager("invalid-non-hex-string"));
    }

    @Test
    @DisplayName("T1.3.8 - Trong kênh TLS: Từ chối Agent gửi token rỗng")
    void testTlsRejectsEmptyToken() throws Exception {
        NcasServer server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setTlsEnabled(true);
        server.setKeystorePath(serverKeystorePath.toAbsolutePath().toString());
        server.setKeystorePassword(KEYSTORE_PASS);
        server.start();

        try {
            SSLContext clientCtx = TlsContextFactory.createClientSSLContext(Collections.singletonList(serverCertFingerprint));
            try (SSLSocket socket = (SSLSocket) clientCtx.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5000);
                TlsContextFactory.configureClientSocket(socket);
                socket.startHandshake();

                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());

                RegisterRequest req = new RegisterRequest("agent-no-token", "host", "os", "127.0.0.1", "00:00:00:00:00:00", "", Collections.emptyList(), "1.0.0");
                FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

                Frame resp = FrameDecoder.readFrame(in);
                assertNotNull(resp);
                assertEquals(MessageType.REGISTER_RESP, resp.getType());
                RegisterResponse r = resp.parsePayload(RegisterResponse.class);
                assertFalse(r.isAccepted());
                assertTrue(r.getMessage().contains("AUTH_FAIL"));
            }
        } finally {
            server.stop();
        }
    }
}

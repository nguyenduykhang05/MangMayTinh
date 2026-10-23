package com.networkalert.web;

import com.networkalert.model.AlertConfig;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.ConfigRepository;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.service.AlertService;
import com.networkalert.service.MonitorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("T5.1: Web NOC Hardening (DNS Rebinding, CSRF, Security Headers, Input Validation)")
public class WebNocHardeningTest {

    private EmbeddedWebDashboard dashboard;
    private int port = 8199;
    private String apiToken = "test_hardened_secret_token_12345";
    private AlertService alertService;
    private MonitorService monitorService;

    @BeforeEach
    void setUp() {
        ConfigRepository cfgRepo = new ConfigRepository();
        IncidentRepository incRepo = new IncidentRepository();
        alertService = new AlertService(incRepo, cfgRepo);
        AlertConfig cfg = alertService.getConfig();
        cfg.setApiToken(apiToken);
        cfg.setWebDashboardLocalhostOnly(true);

        monitorService = new MonitorService(alertService);
        dashboard = new EmbeddedWebDashboard(port, monitorService, incRepo, null, alertService);
        assertTrue(dashboard.start(), "Web NOC Dashboard phải khởi động thành công");
        this.port = dashboard.getPort();
    }

    @AfterEach
    void tearDown() {
        if (dashboard != null) {
            dashboard.stop();
        }
    }

    @Test
    @DisplayName("Tiêu chí 1: Host lạ từ bên ngoài thì trả về 403 Forbidden (Chống DNS Rebinding)")
    void testHostHeaderValidationStrangeHostReturns403() throws Exception {
        // Dùng raw Socket để kiểm soát chính xác header Host (vì HttpURLConnection mặc định ghim Host)
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            String req = "GET /api/status HTTP/1.1\r\n" +
                    "Host: attacker.evil.com:8080\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = in.readLine();
            assertNotNull(statusLine, "Server phải trả về phản hồi HTTP");
            assertTrue(statusLine.contains("403 Forbidden"),
                    "Host header lạ (attacker.evil.com) phải bị từ chối 403 Forbidden nhưng nhận: " + statusLine);
        }
    }

    @Test
    @DisplayName("Tiêu chí 1 (Hợp lệ): Host localhost hoặc 127.0.0.1 thì trả về 200 OK")
    void testHostHeaderValidationValidHostsReturn200() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/status");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);

        int code = conn.getResponseCode();
        assertEquals(200, code, "Host 127.0.0.1 phải được chấp nhận với 200 OK");
        conn.disconnect();
    }

    @Test
    @DisplayName("Tiêu chí 2: Request thay đổi trạng thái (POST) thiếu Origin và Bearer thì trả về 403 Forbidden (CSRF)")
    void testCsrfProtectionPostWithoutOriginOrBearerReturns403() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/add-target");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Content-Type", "application/json");
        // Không gửi Origin, không gửi Authorization Bearer

        byte[] payload = "{\"name\":\"EvilTarget\",\"host\":\"192.168.1.1\"}".getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        assertEquals(403, code, "POST thiếu cả Origin lẫn Bearer token phải bị từ chối 403 Forbidden (CSRF defense)");
        conn.disconnect();
    }

    @Test
    @DisplayName("Tiêu chí 2 (Hợp lệ qua Origin): POST có Origin khớp host được phép xử lý")
    void testCsrfProtectionPostWithMatchingOriginAllowed() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/add-target");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Origin", "http://127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        conn.setRequestProperty("Content-Type", "application/json");

        byte[] payload = "{\"name\":\"LegitServer\",\"host\":\"127.0.0.1\",\"port\":80}".getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        assertEquals(200, code, "POST có Origin hợp lệ và Bearer token phải được chấp nhận");
        conn.disconnect();
    }

    @Test
    @DisplayName("Tiêu chí 2 (Hợp lệ qua Bearer): POST từ API client/cURL có Bearer token hợp lệ được phép xử lý dù không có Origin")
    void testCsrfProtectionPostWithBearerTokenAllowedWithoutOrigin() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/add-target");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        conn.setRequestProperty("Content-Type", "application/json");
        // Không có Origin (giả lập cURL / CLI script)

        byte[] payload = "{\"name\":\"ApiTarget\",\"host\":\"127.0.0.1\",\"port\":8080}".getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        assertEquals(200, code, "API client có Bearer token hợp lệ phải được chấp nhận dù không có Origin");
        conn.disconnect();
    }

    @Test
    @DisplayName("Tiêu chí 3: Host bắt đầu bằng dấu gạch ngang '-' bị từ chối 400 Bad Request")
    void testToolPingRejectsHyphenHostWith400() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/tools/ping");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        conn.setRequestProperty("Content-Type", "application/json");

        byte[] payload = "{\"host\":\"-t\"}".getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        assertEquals(400, code, "Host bắt đầu bằng '-' (-t) phải bị từ chối 400 Bad Request");
        conn.disconnect();
    }

    @Test
    @DisplayName("Tiêu chí 3: Host chứa ký tự lạ / command injection bị từ chối 400 Bad Request")
    void testToolPingRejectsShellInjectionHostWith400() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/tools/ping");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        conn.setRequestProperty("Content-Type", "application/json");

        byte[] payload = "{\"host\":\"8.8.8.8;calc.exe\"}".getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        assertEquals(400, code, "Host chứa ký tự shell injection (;) phải bị từ chối 400 Bad Request");
        conn.disconnect();
    }

    @Test
    @DisplayName("Tiêu chí 4: Quét ngoài dải /24 của máy chủ (/api/tools/scan-lan) bị từ chối 400 Bad Request")
    void testToolScanLanRejectsSubnetOutside24With400() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/tools/scan-lan?subnet=10.99.88.0/24");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);

        int code = conn.getResponseCode();
        assertEquals(400, code, "Quét dải subnet ngoài /24 của máy chủ phải bị từ chối 400 Bad Request");
        conn.disconnect();
    }

    @Test
    @DisplayName("Tiêu chí 5: Mặc định chỉ bind 127.0.0.1 (Loopback)")
    void testDefaultBindAddressIsLocalhostOnly() {
        InetSocketAddress addr = dashboard.getServer().getAddress();
        assertNotNull(addr);
        assertTrue(addr.getAddress().isLoopbackAddress(),
                "Mặc định máy chủ Web NOC chỉ được bind vào loopback address (127.0.0.1)");
    }

    @Test
    @DisplayName("ERRATA §E10.3: Giới hạn dung lượng Body <= 64 KiB, vượt quá thì trả về 413 Payload Too Large")
    void testBodySizeLimitExceeding64KiBReturns413() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/add-target");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);
        conn.setRequestProperty("Content-Type", "application/json");

        // Tạo payload 70 KiB (> 64 KiB)
        byte[] largePayload = new byte[70 * 1024];
        java.util.Arrays.fill(largePayload, (byte) 'A');

        try (OutputStream os = conn.getOutputStream()) {
            os.write(largePayload);
        }

        int code = conn.getResponseCode();
        assertEquals(413, code, "Request body vượt quá 64 KiB phải trả về 413 Payload Too Large");
        conn.disconnect();
    }

    @Test
    @DisplayName("ERRATA §E10.3: Gắn đầy đủ Security Headers trên mọi phản hồi HTTP")
    void testSecurityHeadersPresentOnAllResponses() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/api/status");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Host", "127.0.0.1:" + port);
        conn.setRequestProperty("Authorization", "Bearer " + apiToken);

        int code = conn.getResponseCode();
        assertEquals(200, code);

        assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"));
        assertEquals("DENY", conn.getHeaderField("X-Frame-Options"));
        assertEquals("no-referrer", conn.getHeaderField("Referrer-Policy"));
        assertNotNull(conn.getHeaderField("Content-Security-Policy"));
        assertEquals("no-store", conn.getHeaderField("Cache-Control"));

        conn.disconnect();
    }
}

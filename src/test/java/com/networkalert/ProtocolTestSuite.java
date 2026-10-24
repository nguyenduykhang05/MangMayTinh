package com.networkalert;

import com.networkalert.model.*;
import com.networkalert.service.checker.*;
import com.networkalert.simulator.*;
import com.networkalert.util.CsvExporter;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử tự động toàn diện cho các giao thức mạng và thành phần hệ thống
 */
public class ProtocolTestSuite {

    @Test
    @DisplayName("Kiểm thử giao thức TCP: Handshake thành công và phát hiện sập cổng")
    void testTcpChecker() {
        MockTcpServer tcpServer = new MockTcpServer(9100);
        assertTrue(tcpServer.start(), "Khởi động MockTcpServer cổng 9100 phải thành công");

        ServerTarget target = new ServerTarget(1, "Test TCP", "127.0.0.1", 9100, ProtocolType.TCP, 1, 1500, true);
        TcpChecker checker = new TcpChecker();

        // 1. Kiểm tra khi server đang bật
        CheckResult resultOnline = checker.check(target);
        assertTrue(resultOnline.isSuccess(), "TCP Checker phải trả về thành công khi server chạy");
        assertTrue(resultOnline.getLatencyMs() >= 0, "Độ trễ phải lớn hơn hoặc bằng 0ms");

        // 2. Dừng server và kiểm tra phát hiện OFFLINE
        tcpServer.stop();
        CheckResult resultOffline = checker.check(target);
        assertFalse(resultOffline.isSuccess(), "TCP Checker phải phát hiện thất bại khi server đã dừng");
    }

    @Test
    @DisplayName("Kiểm thử giao thức UDP: Datagram Echo phản hồi")
    void testUdpChecker() {
        MockUdpServer udpServer = new MockUdpServer(9101);
        assertTrue(udpServer.start(), "Khởi động MockUdpServer cổng 9101 phải thành công");

        ServerTarget target = new ServerTarget(2, "Test UDP", "127.0.0.1", 9101, ProtocolType.UDP, 1, 1500, true);
        UdpChecker checker = new UdpChecker();

        CheckResult result = checker.check(target);
        assertTrue(result.isSuccess(), "UDP Echo phải nhận được phản hồi");

        udpServer.stop();
    }

    @Test
    @DisplayName("Kiểm thử giao thức HTTP: Phân tích mã 200 OK và lỗi 500 Server Error")
    void testHttpChecker() {
        MockHttpServer httpServer = new MockHttpServer(9102);
        assertTrue(httpServer.start(), "Khởi động MockHttpServer cổng 9102 phải thành công");

        ServerTarget target = new ServerTarget(3, "Test HTTP", "127.0.0.1", 9102, ProtocolType.HTTP, 1, 1500, true);
        HttpChecker checker = new HttpChecker();

        // Kiểm tra mã 200
        httpServer.setResponseCode(200);
        CheckResult res200 = checker.check(target);
        assertTrue(res200.isSuccess(), "HTTP 200 phải được ghi nhận là ONLINE");
        assertEquals(200, res200.getResponseCode());

        // Đổi sang mã 500
        httpServer.setResponseCode(500);
        CheckResult res500 = checker.check(target);
        assertFalse(res500.isSuccess(), "HTTP 500 phải được ghi nhận là OFFLINE");
        assertEquals(500, res500.getResponseCode());

        httpServer.stop();
    }

    @Test
    @DisplayName("Kiểm thử giao thức DNS: Phân giải tên miền và đo thời gian truy vấn")
    void testDnsChecker() {
        ServerTarget target = new ServerTarget(4, "Test DNS", "google.com", 53, ProtocolType.DNS, 1, 2500, true);
        DnsChecker checker = new DnsChecker();

        CheckResult result = checker.check(target);
        assertTrue(result.isSuccess(), "DNS phân giải google.com phải thành công");
        assertTrue(result.getMessage().contains("IPs"), "Thông báo phải chứa danh sách IP phân giải được");
    }

    @Test
    @DisplayName("Kiểm thử giao thức DNS: Gửi gói tin UDP Query tới DNS Server thực tế (Cloudflare 1.1.1.1)")
    void testDnsServerUdpQueryReal() {
        ServerTarget target = new ServerTarget(6, "Cloudflare DNS", "1.1.1.1", 53, ProtocolType.DNS, 1, 3000, true);
        DnsChecker checker = new DnsChecker();

        CheckResult result = checker.check(target);
        assertTrue(result.isSuccess(), "Truy vấn DNS Server 1.1.1.1 qua UDP 53 phải thành công");
        assertTrue(result.getLatencyMs() > 0, "Độ trễ truy vấn UDP DNS thực tế phải > 0ms");
        assertTrue(result.getMessage().contains("IPs"), "Thông báo phải chứa danh sách IP trả về từ DNS Server");
    }

    @Test
    @DisplayName("Kiểm thử giao thức DNS: Phát hiện OFFLINE khi máy chủ DNS không phản hồi / timeout")
    void testDnsCheckerOfflineDetection() {
        // IP 192.0.2.1 là dải TEST-NET-1 (RFC 5737), không định tuyến và không có DNS Server nào hoạt động
        ServerTarget unreachableTarget = new ServerTarget(7, "Unreachable DNS", "192.0.2.1", 53, ProtocolType.DNS, 1, 400, true);
        DnsChecker checker = new DnsChecker();

        CheckResult result = checker.check(unreachableTarget);
        assertFalse(result.isSuccess(), "Máy chủ DNS không phản hồi phải bị đánh dấu OFFLINE");
        assertNotNull(result.getMessage());
    }

    @Test
    @DisplayName("Kiểm thử tính toán Uptime % và cập nhật Metric thời gian thực")
    void testUptimeMetrics() {
        ServerTarget target = new ServerTarget(5, "Test Metrics", "127.0.0.1", 80, ProtocolType.TCP, 1, 1000, true);
        target.getStateMachine().setFailThreshold(1);

        assertEquals(100.0, target.getUptimePercentage());

        // 3 lần thành công
        target.recordCheckResult(CheckResult.success(10, "OK"), 300);
        target.recordCheckResult(CheckResult.success(15, "OK"), 300);
        target.recordCheckResult(CheckResult.success(20, "OK"), 300);
        assertEquals(100.0, target.getUptimePercentage());

        // 1 lần thất bại
        target.recordCheckResult(CheckResult.failure("Timeout"), 300);
        assertEquals(75.0, target.getUptimePercentage());
        assertEquals(ServerStatus.OFFLINE, target.getStatus());
    }

    @Test
    @DisplayName("Kiểm thử xuất báo cáo thống kê CSV")
    void testCsvExport() throws Exception {
        File tempFile = File.createTempFile("test_report_", ".csv");
        tempFile.deleteOnExit();

        List<ServerTarget> targets = new ArrayList<>();
        ServerTarget t = new ServerTarget(1, "Web App", "127.0.0.1", 8080, ProtocolType.HTTP, 5, 1000, true);
        t.recordCheckResult(CheckResult.success(25, "OK"), 300);
        targets.add(t);

        List<IncidentLog> incidents = new ArrayList<>();
        incidents.add(new IncidentLog(1, "Web App", "127.0.0.1", 8080, ProtocolType.HTTP, "OFFLINE", "Mất kết nối"));

        boolean ok = CsvExporter.exportReport(tempFile, targets, incidents);
        assertTrue(ok, "Xuất báo cáo CSV phải trả về true");
        assertTrue(tempFile.exists() && tempFile.length() > 0, "File CSV phải được tạo và có dung lượng");
    }

    @Test
    @DisplayName("Kiểm thử quét cổng mạng: Nhận diện cổng MỞ và tra cứu dịch vụ")
    void testPortScannerService() {
        MockTcpServer tcp = new MockTcpServer(9120);
        assertTrue(tcp.start(), "Phải khởi động được MockTcpServer cổng 9120");

        com.networkalert.scanner.PortScannerService scanner = new com.networkalert.scanner.PortScannerService();
        com.networkalert.scanner.PortScanResult resOpen = scanner.scanSinglePort("127.0.0.1", 9120, 1000);
        assertTrue(resOpen.isOpen(), "Cổng 9120 phải được nhận diện là MỞ");
        assertEquals(9120, resOpen.getPort());

        tcp.stop();
        com.networkalert.scanner.PortScanResult resClosed = scanner.scanSinglePort("127.0.0.1", 9120, 500);
        assertFalse(resClosed.isOpen(), "Cổng 9120 phải được nhận diện là ĐÓNG sau khi stop");
    }

    @Test
    @DisplayName("Kiểm thử Web Dashboard Mini: Khởi động và phản hồi API trạng thái")
    void testEmbeddedWebDashboard() throws Exception {
        com.networkalert.repository.ConfigRepository cfgRepo = new com.networkalert.repository.ConfigRepository();
        com.networkalert.repository.IncidentRepository incRepo = new com.networkalert.repository.IncidentRepository();
        com.networkalert.service.AlertService alertSvc = new com.networkalert.service.AlertService(incRepo, cfgRepo);
        com.networkalert.service.MonitorService monSvc = new com.networkalert.service.MonitorService(alertSvc);

        com.networkalert.web.EmbeddedWebDashboard dashboard = new com.networkalert.web.EmbeddedWebDashboard(8089, monSvc, incRepo);
        assertTrue(dashboard.start(), "Web Dashboard phải khởi động thành công trên cổng 8089");

        // Gọi thử API /api/status
        java.net.URL url = new java.net.URL("http://localhost:8089/api/status");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        assertEquals(200, conn.getResponseCode(), "API /api/status phải trả về HTTP 200 OK");
        conn.disconnect();

        // Gọi thử API /api/network-info
        java.net.URL netUrl = new java.net.URL("http://localhost:8089/api/network-info");
        java.net.HttpURLConnection netConn = (java.net.HttpURLConnection) netUrl.openConnection();
        netConn.setConnectTimeout(2000);
        netConn.setReadTimeout(2000);
        assertEquals(200, netConn.getResponseCode(), "API /api/network-info phải trả về HTTP 200 OK");
        netConn.disconnect();

        dashboard.stop();
    }
}

package com.networkalert.service.checker;

import com.networkalert.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử phương thức Ping ICMP hệ thống và TCP Fallback theo ERRATA §E9.6 & Task T2.5
 */
public class PingCheckerTest {

    @Test
    @DisplayName("T2.5: Phòng vệ Command Injection đối với tham số host trong PingChecker")
    void testHostValidationDefense() {
        PingChecker.PingRunner mockRunner = (host, count, timeout) -> {
            fail("PingRunner không được phép chạy khi host chứa ký tự cấm!");
            return null;
        };

        PingChecker checker = new PingChecker(mockRunner);

        String[] dangerousHosts = {
                "127.0.0.1; whoami",
                "8.8.8.8 | dir",
                "192.168.1.1 & calc.exe",
                "$(id)",
                "`cat /etc/passwd`",
                "google.com\n127.0.0.1",
                "host with spaces"
        };

        for (String badHost : dangerousHosts) {
            ServerTarget target = new ServerTarget();
            target.setHost(badHost);
            target.setProtocol(ProtocolType.ICMP);

            CheckResult result = checker.check(target);
            assertEquals(NagiosStatus.UNKNOWN, result.getNagiosStatus(), "Host độc hại phải trả về UNKNOWN");
            assertEquals("icmp", result.getMethod());
            assertTrue(result.getMessage().contains("không hợp lệ"), "Thông điệp phải nêu rõ lỗi host không hợp lệ");
        }
    }

    @Test
    @DisplayName("T2.5: Phân tích thành công nhánh ICMP Ping (0% packet loss, method = icmp)")
    void testIcmpSuccessBranch() {
        PingChecker.PingRunner runner = (host, count, timeout) ->
                new PingChecker.PingOutput(4, 4, 0.0, 10.0, 15.0, 20.0, 10.0, "mock ok");

        PingChecker checker = new PingChecker(runner);

        ServerTarget target = new ServerTarget();
        target.setHost("127.0.0.1");
        target.setProtocol(ProtocolType.ICMP);
        target.setWarningThresholdMs(300);
        target.setTimeoutMs(1000);

        CheckResult result = checker.check(target);

        assertTrue(result.isSuccess());
        assertEquals(NagiosStatus.OK, result.getNagiosStatus());
        assertEquals("icmp", result.getMethod());
        assertEquals(15, result.getLatencyMs());

        // Kiểm tra PerfData
        assertNotNull(result.getPerfDataList());
        assertEquals(2, result.getPerfDataList().size());

        PerfData rta = result.getPerfDataList().get(0);
        assertEquals("rta", rta.getLabel());
        assertEquals(15.0, rta.getValue());
        assertEquals("ms", rta.getUnit());

        PerfData pl = result.getPerfDataList().get(1);
        assertEquals("pl", pl.getLabel());
        assertEquals(0.0, pl.getValue());
        assertEquals("%", pl.getUnit());
    }

    @Test
    @DisplayName("T2.5: Cảnh báo WARN khi mất gói một phần (packet loss > 0% và < 100%)")
    void testIcmpPacketLossWarningBranch() {
        PingChecker.PingRunner runner = (host, count, timeout) ->
                new PingChecker.PingOutput(4, 3, 25.0, 12.0, 18.0, 25.0, 13.0, "mock 25% loss");

        PingChecker checker = new PingChecker(runner);

        ServerTarget target = new ServerTarget();
        target.setHost("127.0.0.1");
        target.setProtocol(ProtocolType.ICMP);

        CheckResult result = checker.check(target);

        assertEquals(NagiosStatus.WARN, result.getNagiosStatus());
        assertEquals("icmp", result.getMethod());
        assertTrue(result.getMessage().contains("25% mất gói"));
    }

    @Test
    @DisplayName("T2.5: Cảnh báo WARN khi độ trễ ICMP vượt ngưỡng cảnh báo (rta > warningThreshold)")
    void testIcmpHighLatencyWarningBranch() {
        PingChecker.PingRunner runner = (host, count, timeout) ->
                new PingChecker.PingOutput(4, 4, 0.0, 320.0, 350.0, 380.0, 60.0, "mock high latency");

        PingChecker checker = new PingChecker(runner);

        ServerTarget target = new ServerTarget();
        target.setHost("127.0.0.1");
        target.setProtocol(ProtocolType.ICMP);
        target.setWarningThresholdMs(300);

        CheckResult result = checker.check(target);

        assertEquals(NagiosStatus.WARN, result.getNagiosStatus());
        assertEquals("icmp", result.getMethod());
        assertTrue(result.getMessage().contains("độ trễ cao"));
    }

    @Test
    @DisplayName("T2.5: Fallback sang TCP connect khi ICMP không khả dụng (method = tcp, hiển thị ICMP*)")
    void testTcpFallbackSuccessBranch() throws IOException {
        // Tạo ServerSocket cục bộ để mô phỏng cổng TCP mở
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            // Mock runner ICMP ném lỗi (hoặc 100% loss)
            PingChecker.PingRunner runner = (host, count, timeout) -> {
                throw new IOException("ICMP Permission Denied (Raw socket restricted)");
            };

            PingChecker checker = new PingChecker(runner);

            ServerTarget target = new ServerTarget();
            target.setHost("127.0.0.1");
            target.setPort(port);
            target.setProtocol(ProtocolType.ICMP);
            target.setTimeoutMs(1000);

            CheckResult result = checker.check(target);

            assertTrue(result.isSuccess());
            assertEquals(NagiosStatus.OK, result.getNagiosStatus());
            assertEquals("tcp", result.getMethod(), "Khi fallback sang TCP connect, method phải là 'tcp'");
            assertTrue(result.getMessage().contains("TCP Fallback"), "Thông điệp phải chỉ rõ TCP Fallback");

            // Kiểm tra trạng thái target ghi nhận fallback
            target.recordCheckResult(result, 300);
            assertEquals("tcp", target.getLastMethod());
            assertTrue(target.isIcmpFallback(), "Mục tiêu ICMP với method tcp phải kích hoạt cờ isIcmpFallback");
        }
    }

    @Test
    @DisplayName("T2.5: Báo lỗi CRIT khi cả ICMP lẫn TCP Fallback đều thất bại")
    void testBothIcmpAndTcpFailCritBranch() {
        PingChecker.PingRunner runner = (host, count, timeout) ->
                new PingChecker.PingOutput(4, 0, 100.0, 0, 0, 0, 0, "100% loss");

        PingChecker checker = new PingChecker(runner);

        ServerTarget target = new ServerTarget();
        target.setHost("127.0.0.1");
        target.setPort(65432); // Cổng không có listener
        target.setProtocol(ProtocolType.ICMP);
        target.setTimeoutMs(300);

        CheckResult result = checker.check(target);

        assertFalse(result.isSuccess());
        assertEquals(NagiosStatus.CRIT, result.getNagiosStatus());
        assertEquals("icmp", result.getMethod());
        assertTrue(result.getMessage().contains("ICMP Ping thất bại") && result.getMessage().contains("TCP fallback"));
    }

    @Test
    @DisplayName("T2.5: Phân tích cú pháp đầu ra ping chuẩn của Windows (English & Tiếng Việt)")
    void testPingOutputParsingWindows() {
        String winEnOutput = "Pinging 127.0.0.1 with 32 bytes of data:\n" +
                "Reply from 127.0.0.1: bytes=32 time=2ms TTL=128\n" +
                "Reply from 127.0.0.1: bytes=32 time=3ms TTL=128\n" +
                "Reply from 127.0.0.1: bytes=32 time=1ms TTL=128\n" +
                "Reply from 127.0.0.1: bytes=32 time=4ms TTL=128\n" +
                "\n" +
                "Ping statistics for 127.0.0.1:\n" +
                "    Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),\n" +
                "Approximate round trip times in milli-seconds:\n" +
                "    Minimum = 1ms, Maximum = 4ms, Average = 2ms";

        PingChecker.PingOutput outEn = PingChecker.parsePingOutput(winEnOutput, 4);
        assertEquals(4, outEn.getSent());
        assertEquals(4, outEn.getReceived());
        assertEquals(0.0, outEn.getPacketLossPercent());
        assertEquals(1.0, outEn.getMinRtt());
        assertEquals(4.0, outEn.getMaxRtt());
        assertEquals(2.0, outEn.getAvgRtt());
        assertEquals(3.0, outEn.getJitter());

        String winViOutput = "Ping thống kê cho 127.0.0.1:\n" +
                "    Gói: Đã gửi = 4, Đã nhận = 3, Bị mất = 1 (25% mất),\n" +
                "Thời gian khứ hồi xấp xỉ:\n" +
                "    Tối thiểu = 5ms, Tối đa = 15ms, Trung bình = 10ms";

        PingChecker.PingOutput outVi = PingChecker.parsePingOutput(winViOutput, 4);
        assertEquals(4, outVi.getSent());
        assertEquals(3, outVi.getReceived());
        assertEquals(25.0, outVi.getPacketLossPercent());
        assertEquals(5.0, outVi.getMinRtt());
        assertEquals(15.0, outVi.getMaxRtt());
        assertEquals(10.0, outVi.getAvgRtt());
        assertEquals(10.0, outVi.getJitter());
    }

    @Test
    @DisplayName("T2.5: Phân tích cú pháp đầu ra ping chuẩn của Linux (iputils-ping)")
    void testPingOutputParsingLinux() {
        String linuxOutput = "PING 127.0.0.1 (127.0.0.1) 56(84) bytes of data.\n" +
                "64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.038 ms\n" +
                "64 bytes from 127.0.0.1: icmp_seq=2 ttl=64 time=0.045 ms\n" +
                "64 bytes from 127.0.0.1: icmp_seq=3 ttl=64 time=0.042 ms\n" +
                "64 bytes from 127.0.0.1: icmp_seq=4 ttl=64 time=0.040 ms\n" +
                "\n" +
                "--- 127.0.0.1 ping statistics ---\n" +
                "4 packets transmitted, 4 received, 0% packet loss, time 3072ms\n" +
                "rtt min/avg/max/mdev = 0.038/0.041/0.045/0.005 ms";

        PingChecker.PingOutput outLinux = PingChecker.parsePingOutput(linuxOutput, 4);
        assertEquals(4, outLinux.getSent());
        assertEquals(4, outLinux.getReceived());
        assertEquals(0.0, outLinux.getPacketLossPercent());
        assertEquals(0.038, outLinux.getMinRtt());
        assertEquals(0.041, outLinux.getAvgRtt());
        assertEquals(0.045, outLinux.getMaxRtt());
        assertEquals(0.005, outLinux.getJitter());
    }

    @Test
    @DisplayName("T2.5: Kiểm thử thực tế DefaultSystemPingRunner với loopback 127.0.0.1")
    void testRealLocalhostPing() {
        PingChecker checker = new PingChecker(); // Dùng DefaultSystemPingRunner thật
        ServerTarget target = new ServerTarget();
        target.setHost("127.0.0.1");
        target.setProtocol(ProtocolType.ICMP);
        target.setTimeoutMs(2000);

        CheckResult result = checker.check(target);
        assertNotNull(result);
        assertTrue(result.isSuccess() || result.getNagiosStatus() == NagiosStatus.WARN);
        assertTrue(result.getMethod().equals("icmp") || result.getMethod().equals("tcp"));
    }
}

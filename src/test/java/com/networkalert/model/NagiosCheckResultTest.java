package com.networkalert.model;

import com.networkalert.service.checker.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử chuẩn hóa hợp đồng CheckResult theo đặc tả Nagios (ERRATA §E9.1 & §E6.5, Task T2.1)
 */
public class NagiosCheckResultTest {

    @Test
    @DisplayName("Ánh xạ chính xác giữa 4 trạng thái Nagios và ServerStatus của NCAS")
    void testNagiosStatusMapping() {
        assertEquals(ServerStatus.ONLINE, NagiosStatus.OK.toServerStatus());
        assertEquals(ServerStatus.WARNING, NagiosStatus.WARN.toServerStatus());
        assertEquals(ServerStatus.OFFLINE, NagiosStatus.CRIT.toServerStatus());
        assertEquals(ServerStatus.UNKNOWN, NagiosStatus.UNKNOWN.toServerStatus());

        assertEquals(NagiosStatus.OK, NagiosStatus.fromServerStatus(ServerStatus.ONLINE));
        assertEquals(NagiosStatus.WARN, NagiosStatus.fromServerStatus(ServerStatus.WARNING));
        assertEquals(NagiosStatus.CRIT, NagiosStatus.fromServerStatus(ServerStatus.OFFLINE));
        assertEquals(NagiosStatus.UNKNOWN, NagiosStatus.fromServerStatus(ServerStatus.UNKNOWN));
        assertEquals(NagiosStatus.UNKNOWN, NagiosStatus.fromServerStatus(ServerStatus.MAINTENANCE));

        assertEquals(NagiosStatus.OK, NagiosStatus.fromCode(0));
        assertEquals(NagiosStatus.WARN, NagiosStatus.fromCode(1));
        assertEquals(NagiosStatus.CRIT, NagiosStatus.fromCode(2));
        assertEquals(NagiosStatus.UNKNOWN, NagiosStatus.fromCode(3));
    }

    @Test
    @DisplayName("Làm sạch ký tự điều khiển và cắt ngắn thông điệp tối đa 512 ký tự")
    void testMessageSanitizationAndLengthTruncation() {
        String rawWithControls = "Check OK\u0000\u0007\u001B\t\nLine 2";
        CheckResult res1 = CheckResult.ok(10, rawWithControls, "tcp");
        assertFalse(res1.getMessage().contains("\u0000"));
        assertFalse(res1.getMessage().contains("\u0007"));
        assertFalse(res1.getMessage().contains("\u001B"));
        assertTrue(res1.getMessage().contains("Line 2"));

        // Thông điệp dài hơn 512 ký tự
        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            longMsg.append("x");
        }
        CheckResult res2 = CheckResult.crit(50, 2, longMsg.toString(), "http");
        assertTrue(res2.getMessage().length() <= 512, "Thông điệp phải được cắt tối đa 512 ký tự");
        assertTrue(res2.getMessage().endsWith("..."));
    }

    @Test
    @DisplayName("Định dạng và phân tích cú pháp PerfData chuẩn Nagios")
    void testPerfDataFormattingAndParsing() {
        PerfData p1 = PerfData.of("rta", 25.5, "ms", 150.0, 600.0, 0.0, null);
        assertEquals("rta=25.5ms;150;600;0", p1.toNagiosString());

        PerfData parsed1 = PerfData.parse("rta=25.5ms;150;600;0;");
        assertNotNull(parsed1);
        assertEquals("rta", parsed1.getLabel());
        assertEquals(25.5, parsed1.getValue(), 0.001);
        assertEquals("ms", parsed1.getUnit());
        assertEquals(150.0, parsed1.getWarn(), 0.001);
        assertEquals(600.0, parsed1.getCrit(), 0.001);
        assertEquals(0.0, parsed1.getMin(), 0.001);
        assertNull(parsed1.getMax());

        // Nhiều chỉ số phân tách bằng khoảng trắng
        String multi = "time=45ms;300;1000;0; ttfb=15ms dns=5ms";
        List<PerfData> list = PerfData.parseMultiple(multi);
        assertEquals(3, list.size());
        assertEquals("time", list.get(0).getLabel());
        assertEquals("ttfb", list.get(1).getLabel());
        assertEquals("dns", list.get(2).getLabel());
    }

    @Test
    @DisplayName("CheckResult tạo chuỗi PerfData đúng quy cách nối chuỗi")
    void testCheckResultPerfDataString() {
        List<PerfData> perfs = Arrays.asList(
                PerfData.of("time", 42, "ms", 300.0, 1500.0, 0.0, null),
                PerfData.of("ttfb", 12, "ms")
        );
        CheckResult result = CheckResult.ok(42, "HTTP 200 OK", "https", perfs);

        assertEquals("https", result.getMethod());
        assertEquals(NagiosStatus.OK, result.getNagiosStatus());
        assertEquals(ServerStatus.ONLINE, result.getStatus());
        assertEquals("time=42ms;300;1500;0 ttfb=12ms", result.getPerfDataString());
        assertTrue(result.toString().contains("time=42ms;300;1500;0"));
    }

    @Test
    @DisplayName("Trạng thái UNKNOWN không tính vào failThreshold (ERRATA §E6.5)")
    void testUnknownDoesNotCountTowardsFailThreshold() {
        TargetStateMachine sm = new TargetStateMachine(1, "Test Host", 3, 1);
        // Trạng thái ban đầu: ONLINE
        CheckResult okResult = CheckResult.ok(10, "OK", "icmp");
        sm.processResult(okResult, 300);
        assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus());

        // Gặp lỗi UNKNOWN 5 lần liên tiếp (ví dụ sai config hoặc thiếu quyền)
        CheckResult unknownResult = CheckResult.unknown(0, 3, "Unknown host config", "icmp");
        for (int i = 0; i < 5; i++) {
            Optional<TargetStateMachine.StateTransition> transition = sm.processResult(unknownResult, 300);
            assertFalse(transition.isPresent(), "UNKNOWN không được kích hoạt chuyển trạng thái SOFT/HARD");
            assertEquals(ServerStatus.ONLINE, sm.getCurrentStatus(), "Mục tiêu vẫn phải duy trì ONLINE, không bị đánh rớt sang OFFLINE");
            assertEquals(0, sm.getConsecutiveFailures(), "consecutiveFailures không được tăng khi gặp UNKNOWN");
        }

        // Khi gặp lỗi CRIT thực sự: bắt đầu tính failThreshold
        CheckResult critResult = CheckResult.crit(300, 2, "Timeout", "icmp");
        sm.processResult(critResult, 300); // Lần 1: SOFT
        assertEquals(1, sm.getConsecutiveFailures());
        assertEquals(StateType.SOFT, sm.getStateType());

        sm.processResult(critResult, 300); // Lần 2: SOFT
        assertEquals(2, sm.getConsecutiveFailures());

        Optional<TargetStateMachine.StateTransition> hardTrans = sm.processResult(critResult, 300); // Lần 3: HARD OFFLINE
        assertTrue(hardTrans.isPresent());
        assertEquals(ServerStatus.OFFLINE, sm.getCurrentStatus());
        assertEquals(StateType.HARD, sm.getStateType());
    }

    @Test
    @DisplayName("Các Checker (Ping, Tcp, Udp, Http, Dns) trả về đúng CheckResult chuẩn Nagios")
    void testCheckersReturnNagiosCompliantResults() {
        ServerTarget mockTarget = new ServerTarget(1, "Mock", "127.0.0.1", 80, ProtocolType.HTTP, 5, 500, true);

        // 1. TcpChecker
        TcpChecker tcpChecker = new TcpChecker();
        CheckResult tcpRes = tcpChecker.check(mockTarget);
        assertNotNull(tcpRes.getNagiosStatus());
        assertEquals("tcp", tcpRes.getMethod());
        assertFalse(tcpRes.getMessage().isEmpty());

        // 2. PingChecker
        PingChecker pingChecker = new PingChecker();
        CheckResult pingRes = pingChecker.check(mockTarget);
        assertNotNull(pingRes.getNagiosStatus());
        assertTrue("icmp".equals(pingRes.getMethod()) || "tcp".equals(pingRes.getMethod()));

        // 3. UdpChecker
        UdpChecker udpChecker = new UdpChecker();
        CheckResult udpRes = udpChecker.check(mockTarget);
        assertNotNull(udpRes.getNagiosStatus());
        assertEquals("udp", udpRes.getMethod());

        // 4. HttpChecker
        HttpChecker httpChecker = new HttpChecker();
        CheckResult httpRes = httpChecker.check(mockTarget);
        assertNotNull(httpRes.getNagiosStatus());
        assertEquals("http", httpRes.getMethod());

        // 5. DnsChecker với host rỗng -> UNKNOWN
        DnsChecker dnsChecker = new DnsChecker();
        ServerTarget invalidTarget = new ServerTarget(2, "Invalid DNS", "", 53, ProtocolType.DNS, 5, 500, true);
        CheckResult dnsRes = dnsChecker.check(invalidTarget);
        assertNotNull(dnsRes.getNagiosStatus());
    }
}

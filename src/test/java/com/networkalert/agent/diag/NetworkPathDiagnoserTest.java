package com.networkalert.agent.diag;

import com.networkalert.model.Problem;
import com.networkalert.model.ProblemStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.ProblemRepository;
import com.networkalert.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử động cơ tự chẩn đoán tại chỗ khi sắp HARD DOWN theo ERRATA §E9.9 & Task T2.9
 */
public class NetworkPathDiagnoserTest {

    private MutableClock testClock;

    static class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        public MutableClock(Instant initial, ZoneId zone) {
            this.now = initial;
            this.zone = zone;
        }

        public void advanceMillis(long ms) {
            this.now = this.now.plusMillis(ms);
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        testClock = new MutableClock(Instant.parse("2026-10-24T12:00:00Z"), ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("T2.9: Chẩn đoán phát hiện đứt kết nối vật lý / Gateway không phản hồi")
    void testGatewayDownDiagnosis() {
        NetworkPathDiagnoser.GatewayProber gwProber = (ip, timeout) ->
                new DiagnosticReport.ProbeResult(false, timeout, "192.168.1.1");
        NetworkPathDiagnoser.DnsProber dnsProber = (domain, timeout) ->
                new DiagnosticReport.ProbeResult(false, timeout, "DNS error");

        NetworkPathDiagnoser diagnoser = new NetworkPathDiagnoser(
                testClock, gwProber, dnsProber, null, null, null
        );

        ServerTarget target = new ServerTarget();
        target.setId(101);
        target.setName("Database Production");
        target.setHost("10.0.0.5");

        DiagnosticReport report = diagnoser.executeDiagnosis(target);
        assertNotNull(report);
        assertNotNull(report.getGateway());
        assertFalse(report.getGateway().isOk());
        assertTrue(report.getSummary().contains("Gateway không phản hồi"), "Tóm tắt phải chỉ ra Gateway không phản hồi");
    }

    @Test
    @DisplayName("T2.9: Chẩn đoán phát hiện Gateway OK nhưng DNS phân giải tên miền hỏng")
    void testDnsFailureDiagnosis() {
        NetworkPathDiagnoser.GatewayProber gwProber = (ip, timeout) ->
                new DiagnosticReport.ProbeResult(true, 2, "192.168.1.1");
        NetworkPathDiagnoser.DnsProber dnsProber = (domain, timeout) ->
                new DiagnosticReport.ProbeResult(false, timeout, "NXDOMAIN");

        NetworkPathDiagnoser diagnoser = new NetworkPathDiagnoser(
                testClock, gwProber, dnsProber, null, null, null
        );

        ServerTarget target = new ServerTarget();
        target.setId(102);
        target.setName("Web Portal");
        target.setHost("portal.company.internal");

        DiagnosticReport report = diagnoser.executeDiagnosis(target);
        assertNotNull(report);
        assertTrue(report.getGateway().isOk());
        assertFalse(report.getDns().isOk());
        assertTrue(report.getSummary().contains("Gateway OK") && report.getSummary().contains("DNS thất bại"),
                "Tóm tắt phải chỉ rõ Gateway OK nhưng DNS thất bại");
    }

    @Test
    @DisplayName("T2.9: Chẩn đoán phát hiện đường mạng bị đứt giữa chừng tại hop trung gian")
    void testTracerouteStopDiagnosis() {
        NetworkPathDiagnoser.GatewayProber gwProber = (ip, timeout) ->
                new DiagnosticReport.ProbeResult(true, 1, "192.168.1.1");
        NetworkPathDiagnoser.DnsProber dnsProber = (domain, timeout) ->
                new DiagnosticReport.ProbeResult(true, 12, "8.8.8.8");

        NetworkPathDiagnoser.TracerouteRunner traceRunner = (host, maxHops, timeout) -> {
            DiagnosticReport.TraceResult res = new DiagnosticReport.TraceResult();
            res.setMaxHops(15);
            res.setLastResponsiveHop(3);
            res.setLastResponsiveIp("10.50.1.1");
            res.setReachedTarget(false);
            res.getHops().add(new DiagnosticReport.HopInfo(1, "192.168.1.1", 1, true));
            res.getHops().add(new DiagnosticReport.HopInfo(2, "10.0.0.1", 5, true));
            res.getHops().add(new DiagnosticReport.HopInfo(3, "10.50.1.1", 8, true));
            res.getHops().add(new DiagnosticReport.HopInfo(4, "*", 0, false));
            return res;
        };

        NetworkPathDiagnoser diagnoser = new NetworkPathDiagnoser(
                testClock, gwProber, dnsProber, traceRunner, null, null
        );

        ServerTarget target = new ServerTarget();
        target.setId(103);
        target.setName("Branch Router");
        target.setHost("10.50.2.1");

        DiagnosticReport report = diagnoser.executeDiagnosis(target);
        assertNotNull(report);
        assertTrue(report.getSummary().contains("đường đi dừng ở hop 3 (10.50.1.1)"),
                "Tóm tắt phải thể hiện chính xác chặng dừng traceroute");
    }

    @Test
    @DisplayName("T2.9: Chẩn đoán phát hiện ICMP bị firewall chặn nhưng dịch vụ TCP vẫn mở")
    void testIcmpBlockedTcpOpenDiagnosis() {
        NetworkPathDiagnoser.GatewayProber gwProber = (ip, timeout) -> new DiagnosticReport.ProbeResult(true, 1, "192.168.1.1");
        NetworkPathDiagnoser.DnsProber dnsProber = (domain, timeout) -> new DiagnosticReport.ProbeResult(true, 10, "1.1.1.1");
        NetworkPathDiagnoser.TracerouteRunner traceRunner = (host, maxHops, timeout) -> {
            DiagnosticReport.TraceResult res = new DiagnosticReport.TraceResult();
            res.setLastResponsiveHop(4);
            res.setReachedTarget(true);
            return res;
        };
        NetworkPathDiagnoser.IcmpProber icmpProber = (host, timeout) -> new DiagnosticReport.ProbeResult(false, timeout, "Timeout");
        NetworkPathDiagnoser.TcpProber tcpProber = (host, port, timeout) -> new DiagnosticReport.ProbeResult(true, 25, "OPEN");

        NetworkPathDiagnoser diagnoser = new NetworkPathDiagnoser(
                testClock, gwProber, dnsProber, traceRunner, icmpProber, tcpProber
        );

        ServerTarget target = new ServerTarget();
        target.setId(104);
        target.setName("Cloud Web HTTPS");
        target.setHost("app.example.com");
        target.setPort(443);

        DiagnosticReport report = diagnoser.executeDiagnosis(target);
        assertNotNull(report);
        assertTrue(report.getSummary().contains("ICMP bị chặn nhưng TCP cổng 443 mở"),
                "Tóm tắt phải phân biệt được ICMP bị chặn và TCP mở");
    }

    @Test
    @DisplayName("T2.9: Kiểm soát tần suất Rate Limit đúng 1 lần / 5 phút / mục tiêu")
    void testRateLimiting5Minutes() {
        NetworkPathDiagnoser diagnoser = new NetworkPathDiagnoser(testClock);
        int targetId = 200;

        assertTrue(diagnoser.shouldRunDiagnosis(targetId), "Lần đầu tiên phải cho phép chẩn đoán");

        ServerTarget target = new ServerTarget();
        target.setId(targetId);
        target.setName("Target Rate Limit");
        target.setHost("127.0.0.1");

        diagnoser.triggerDiagnosisAsync(target);

        // Ngay sau đó (sau 10 giây)
        testClock.advanceMillis(10_000);
        assertFalse(diagnoser.shouldRunDiagnosis(targetId), "Chưa đủ 5 phút không được phép chạy lại");

        // Sau 4 phút 59 giây
        testClock.advanceMillis(289_000); // Tổng 299s
        assertFalse(diagnoser.shouldRunDiagnosis(targetId), "299 giây chưa đủ 300 giây (5 phút)");

        // Sau 5 phút 1 giây
        testClock.advanceMillis(2_000); // Tổng 301s
        assertTrue(diagnoser.shouldRunDiagnosis(targetId), "Đã qua 5 phút phải cho phép chẩn đoán tiếp");
    }

    @Test
    @DisplayName("T2.9: Trần dung lượng JSON báo cáo chẩn đoán luôn <= 2 KiB (2048 bytes)")
    void testJsonTruncationUnder2KiB() {
        DiagnosticReport report = new DiagnosticReport(999, "large.target.internal", 1700000000000L);
        report.setGateway(new DiagnosticReport.ProbeResult(true, 1, "192.168.1.1"));
        report.setDns(new DiagnosticReport.ProbeResult(true, 10, "8.8.8.8"));

        DiagnosticReport.TraceResult trace = new DiagnosticReport.TraceResult();
        trace.setLastResponsiveHop(10);
        trace.setLastResponsiveIp("10.0.0.10");

        // Tạo danh sách 50 hop dài
        for (int i = 1; i <= 50; i++) {
            trace.getHops().add(new DiagnosticReport.HopInfo(i, "192.168.100." + i, i * 2, true));
        }
        report.setTrace(trace);
        report.setSummary("Gateway OK, DNS OK, trace completed with 50 hops data");

        String json = report.toJson();
        assertNotNull(json);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= 2048, "Dung lượng JSON phải <= 2048 bytes (2 KiB), thực tế: " + bytes.length);
    }

    @Test
    @DisplayName("T2.9: Tích hợp thông điệp chẩn đoán vào Problem và cảnh báo AlertService")
    void testDiagnosisIntegrationWithProblemAndAlert() {
        ServerTarget target = new ServerTarget();
        target.setId(301);
        target.setName("Critical Gateway");
        target.setHost("192.168.100.1");
        target.setPort(80);

        String sampleDiag = "{\"summary\":\"Gateway không phản hồi (192.168.100.1) - đứt kết nối mạng nội bộ/Wi-Fi\"}";
        target.setLastDiagnosis(sampleDiag);
        assertEquals("Gateway không phản hồi (192.168.100.1) - đứt kết nối mạng nội bộ/Wi-Fi", target.getLastDiagnosisSummary());

        AlertService alertService = new AlertService(new com.networkalert.repository.IncidentRepository(), new com.networkalert.repository.ConfigRepository(), testClock);
        alertService.notifyOffline(target, "Connection refused");

        // Kiểm tra Problem mở ra có chứa diagnosis
        List<Problem> activeProblems = ProblemRepository.getInstance().getActiveProblems();
        Problem targetProblem = activeProblems.stream()
                .filter(p -> p.getTargetId() == 301)
                .findFirst().orElse(null);

        assertNotNull(targetProblem, "Problem phải được tạo ra khi target offline");
        assertEquals(sampleDiag, targetProblem.getDiagnosis());

        // Phục hồi
        alertService.notifyRecovered(target, 15);
        Problem resolvedProblem = ProblemRepository.getInstance().getAllProblems().stream()
                .filter(p -> p.getTargetId() == 301)
                .findFirst().orElse(null);

        assertNotNull(resolvedProblem);
        assertEquals(ProblemStatus.RESOLVED, resolvedProblem.getStatus(), "Problem phải được RESOLVED khi target phục hồi");
    }
}

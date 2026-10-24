package com.networkalert.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử các tính năng kiểu Uptime Kuma theo Giai đoạn 3B:
 * - Heartbeat ring buffer (tối đa 60 bản ghi)
 * - Uptime SLA theo phút: 24h, 7d, 30d
 * - Quorum trạm đo đa điểm
 * - Duration định dạng thời gian giữ trạng thái
 */
public class UptimeKumaFeatureTest {

    @Test
    public void testHeartbeatRingBufferCapacity() {
        ServerTarget target = new ServerTarget(1, "Web App", "127.0.0.1", 80, ProtocolType.TCP, 5, 1000, 300, true);

        assertEquals(0, target.getHeartbeats().size());

        // Ghi nhận 75 kết quả kiểm tra
        for (int i = 1; i <= 75; i++) {
            boolean success = (i % 5 != 0); // Mỗi 5 lần thì có 1 lần lỗi
            long latency = success ? (10 + i) : -1;
            target.recordStationResult("agent-hanoi-01", new CheckResult(success, latency, success ? "OK" : "Timeout"), 300);
        }

        List<ServerTarget.HeartbeatRecord> heartbeats = target.getHeartbeats();
        // Ring buffer chỉ giữ tối đa 60 bản ghi mới nhất
        assertEquals(60, heartbeats.size(), "Ring buffer phải giữ đúng 60 bản ghi gần nhất");

        // Bản ghi cuối cùng phải có latency tương ứng lần thứ 75 (75 % 5 == 0 -> lỗi)
        ServerTarget.HeartbeatRecord lastRecord = heartbeats.get(heartbeats.size() - 1);
        assertEquals(ServerStatus.OFFLINE, lastRecord.getStatus());
        assertEquals("agent-hanoi-01", lastRecord.getStationId());
    }

    @Test
    public void testUptimeSlaCalculations() {
        ServerTarget target = new ServerTarget(2, "Database", "127.0.0.1", 3306, ProtocolType.TCP, 5, 1000, 300, true);

        // Ban đầu chưa có dữ liệu, uptime mặc định 100%
        assertEquals(100.0, target.getUptimePercentage());
        assertEquals(100.0, target.getUptime24h());
        assertEquals(100.0, target.getUptime7d());
        assertEquals(100.0, target.getUptime30d());

        // Thêm 8 kết quả OK, 2 kết quả lỗi -> 80%
        for (int i = 0; i < 8; i++) {
            target.recordCheckResult(new CheckResult(true, 15, "OK"), 300);
        }
        for (int i = 0; i < 2; i++) {
            target.recordCheckResult(new CheckResult(false, -1, "Refused"), 300);
        }

        assertEquals(80.0, target.getUptimePercentage(), 0.01);
        assertEquals(80.0, target.getUptime24h(), 0.01);
        assertEquals(80.0, target.getUptime7d(), 0.01);
        assertEquals(80.0, target.getUptime30d(), 0.01);
    }

    @Test
    public void testStatusDurationFormatted() {
        ServerTarget target = new ServerTarget(3, "API Gateway", "127.0.0.1", 8080, ProtocolType.TCP, 5, 1000, 300, true);

        // Ban đầu khi chưa check
        assertEquals("Chưa xác định", target.getStatusDurationFormatted());

        // Check lần đầu thành công
        target.recordCheckResult(new CheckResult(true, 20, "OK"), 300);
        assertNotNull(target.getStatusDurationFormatted());
        assertTrue(target.getStatusDurationFormatted().contains("Vừa mới đây")
                || target.getStatusDurationFormatted().contains("phút"));
    }

    @Test
    public void testMultiStationLatenciesIsolation() {
        ServerTarget target = new ServerTarget(4, "DNS Cluster", "8.8.8.8", 53, ProtocolType.UDP, 5, 1000, 300, true);
        target.setScope(TargetScope.MULTI);

        // Ghi kết quả từ trạm Hanoi
        target.recordStationResult("agent-hanoi-01", new CheckResult(true, 15, "OK"), 300);
        target.recordStationResult("agent-hanoi-01", new CheckResult(true, 25, "OK"), 300);

        // Ghi kết quả từ trạm HCM
        target.recordStationResult("agent-hcm-02", new CheckResult(true, 40, "OK"), 300);

        var stationLats = target.getStationLatencies();
        assertEquals(2, stationLats.size(), "Phải lưu dữ liệu của 2 trạm riêng biệt");
        assertEquals(2, stationLats.get("agent-hanoi-01").size());
        assertEquals(1, stationLats.get("agent-hcm-02").size());
        assertEquals(25L, stationLats.get("agent-hanoi-01").get(1));
        assertEquals(40L, stationLats.get("agent-hcm-02").get(0));
    }
}

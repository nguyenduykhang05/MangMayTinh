package com.networkalert.agent;

import com.networkalert.agent.spool.DiskSpoolManager;
import com.networkalert.agent.spool.SpoolRecord;
import com.networkalert.model.ServerTarget;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.protocol.messages.TargetDto;
import com.networkalert.server.NcasServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kiểm thử Khả năng bền vững của NCAS Agent (TASKS T1.5 & ERRATA §E4)")
class NcasAgentResilienceTest {

    @TempDir
    Path testDir;

    private NcasServer server;
    private NcasAgent agent;
    private final String testToken = "TEST-RESILIENCE-TOKEN-123";

    @BeforeEach
    void setUp() throws Exception {
        server = new NcasServer(0, "127.0.0.1", testToken);
        server.start();
        assertTrue(server.isRunning());
    }

    @AfterEach
    void tearDown() {
        if (agent != null && agent.isRunning()) {
            agent.stop();
        }
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    private void waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (!condition.getAsBoolean() && (System.currentTimeMillis() - start) < timeoutMs) {
            new CountDownLatch(1).await(25, TimeUnit.MILLISECONDS);
        }
        assertTrue(condition.getAsBoolean(), "Điều kiện không thỏa mãn sau " + timeoutMs + "ms");
    }

    @Test
    @DisplayName("Cache cấu hình: Agent khởi động khi Server đang sập vẫn tiếp tục đo và đệm vào Disk Spool (§E4.5)")
    void testTargetCacheOfflineContinuationWhenServerDown() throws Exception {
        // Tắt server hoàn toàn để giả lập Server đang sập
        server.stop();
        assertFalse(server.isRunning());

        Path spoolDir = testDir.resolve("spool-offline");
        Path cacheFile = testDir.resolve("assigned.json");

        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(9999); // Cổng không có Server lắng nghe
        config.setAgentId("agent-cached-offline");
        config.setAuthToken(testToken);
        config.setSpoolDirPath(spoolDir.toString());
        config.setAssignedCachePath(cacheFile.toString());
        config.setReconnectInitialDelayMs(500);

        // Chuẩn bị sẵn file cache data/assigned.json với 1 target loopback
        TargetDto dto = new TargetDto(999, "Offline Target", "127.0.0.1", 80, "TCP", 1, 3, 500, true);
        com.networkalert.agent.cache.TargetConfigCache cache = new com.networkalert.agent.cache.TargetConfigCache(cacheFile);
        cache.saveTargets(Collections.singletonList(dto), System.currentTimeMillis());

        // Khởi động Agent trong lúc Server hoàn toàn ngắt kết nối
        agent = new NcasAgent(config);
        agent.start();

        // Agent phải nạp thành công mục tiêu từ cache nội bộ
        assertEquals(1, agent.getCurrentTargets().size());
        assertTrue(agent.getCurrentTargets().containsKey(999));
        assertFalse(agent.isConnected(), "Agent không kết nối được vì Server đang sập");

        // Đợi một khoảng thời gian để scheduler thực hiện phép đo định kỳ
        waitForCondition(() -> agent.getSpoolManager().getSpoolDepth() >= 1, 4000);

        // Kết quả đo phải được lưu trữ bền vững vào Disk Spool
        assertTrue(agent.getSpoolManager().getSpoolDepth() >= 1,
                "Kết quả đo phải được lưu vào Disk Spool khi Server offline");
    }

    @Test
    @DisplayName("Sống sót qua kill -9: Khởi động lại giữ nguyên streamId cũ, sinh streamId mới cho kết quả mới (§E4.1)")
    void testRestartPreservesOldStreamIdAndGeneratesNewStreamId() throws Exception {
        Path spoolDir = testDir.resolve("spool-stream");
        Path cacheFile = testDir.resolve("assigned-stream.json");

        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-stream-test");
        config.setAuthToken(testToken);
        config.setSpoolDirPath(spoolDir.toString());
        config.setAssignedCachePath(cacheFile.toString());

        agent = new NcasAgent(config);
        long firstStreamId = agent.getStreamId();

        // Ghi một số bản ghi vào spool
        ReportResultMessage rep1 = new ReportResultMessage("agent-stream-test", 1, System.currentTimeMillis(), true, 20L, 200, null, "OK");
        rep1.setStreamId(firstStreamId);
        rep1.setSeq(1L);
        agent.getSpoolManager().append(rep1);

        assertEquals(1, agent.getSpoolManager().getSpoolDepth());

        // Đóng đột ngột không qua socket (giả lập kill -9)
        agent.getSpoolManager().close();

        // Khởi động lại Agent mới trên cùng thư mục spool
        NcasAgent secondAgent = new NcasAgent(config);
        secondAgent.getSpoolManager().open();
        long secondStreamId = secondAgent.getStreamId();

        try {
            assertNotEquals(firstStreamId, secondStreamId, "Agent khởi động mới phải sinh streamId mới ngẫu nhiên");

            // Bản ghi cũ trong spool phải giữ nguyên firstStreamId
            List<SpoolRecord> unacked = secondAgent.getSpoolManager().getUnackedRecords();
            assertEquals(1, unacked.size());
            assertEquals(firstStreamId, unacked.get(0).getStreamId(), "Bản ghi cũ phải bảo lưu streamId ban đầu");
            assertEquals(1L, unacked.get(0).getSeq());
        } finally {
            secondAgent.getSpoolManager().close();
        }
    }

    @Test
    @DisplayName("Xóa data/spool thì Agent sinh streamId mới và hoạt động trơn tru không lỗi")
    void testDeletingDataGeneratesNewStreamAndRunsCleanly() throws Exception {
        Path spoolDir = testDir.resolve("spool-delete");
        Files.createDirectories(spoolDir);

        AgentConfig config = new AgentConfig();
        config.setServerHost("127.0.0.1");
        config.setServerPort(server.getPort());
        config.setAgentId("agent-del-data");
        config.setAuthToken(testToken);
        config.setSpoolDirPath(spoolDir.toString());
        config.setAssignedCachePath(testDir.resolve("assigned-del.json").toString());

        // Giả lập xóa sạch thư mục data/spool
        File[] files = spoolDir.toFile().listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }

        agent = new NcasAgent(config);
        agent.start();

        waitForCondition(() -> agent.isConnected() && agent.isAuthenticated(), 3000);
        assertTrue(agent.isConnected());
        assertEquals(0, agent.getSpoolManager().getSpoolDepth());
        assertTrue(agent.getStreamId() > 0);
    }
}

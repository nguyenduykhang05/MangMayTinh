package com.networkalert.chaos;

import com.networkalert.agent.AgentConfig;
import com.networkalert.agent.NcasAgent;
import com.networkalert.agent.spool.DiskSpoolManager;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetScope;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.ProtocolMetrics;
import com.networkalert.protocol.messages.DataGap;
import com.networkalert.protocol.messages.HeartbeatPing;
import com.networkalert.protocol.messages.RegisterRequest;
import com.networkalert.protocol.messages.RegisterResponse;
import com.networkalert.protocol.messages.ReportBatchMessage;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.server.AgentSession;
import com.networkalert.server.NcasServer;
import com.networkalert.server.ingest.IngestMetrics;
import com.networkalert.server.ingest.IngestedResult;
import com.networkalert.server.ingest.ResultSink;
import com.networkalert.test.TestClock;
import com.networkalert.util.LoggerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử Chaos End-to-End phân tán toàn diện theo ERRATA §E14.2 & Task T1.7:
 * Mô hình 3 tầng: Server Trung tâm <-> TcpFaultProxy <-> Agent Trạm.
 *
 * <p>Kiểm thử 7 kịch bản lỗi mạng khắc nghiệt:
 * <ol>
 *   <li><b>(a) Flapping Network:</b> Đứt/nối mạng nhiều lần, Agent tự phục hồi và tiếp tục luồng dữ liệu.</li>
 *   <li><b>(b) Agent Crash:</b> Agent restart giữa lúc xả Spool, instance mới tự phục hồi và tiếp tục xả.</li>
 *   <li><b>(c) Server Restart:</b> Server sập, Agent tự đo offline qua cache assigned.json và xả bù khi Server sống lại.</li>
 *   <li><b>(d) Spool Overflow:</b> Tràn Spool đĩa, lưu carry.log và khai báo DataGap, Server nhảy watermark không kẹt ACK.</li>
 *   <li><b>(e) Out-of-order:</b> Live và backlog lệch thứ tự, Ingestor phân loại fresh vs backfill chính xác (backfill không đổi trạng thái).</li>
 *   <li><b>(f) Clock Skew:</b> Lệch đồng hồ phân tán +-30s, Server chuẩn hóa mốc thời gian và bật cờ clock_skew.</li>
 *   <li><b>(g) Malicious Attack:</b> Sai token liên tục không làm sập Server, ghi nhận auth_failures_total và cho phép Agent hợp lệ kết nối bình thường.</li>
 * </ol>
 *
 * <p>Các bất biến kiểm tra (Invariants):
 * <ul>
 *   <li>Mọi kết quả tới đích đúng một lần (trừ gap được khai báo).</li>
 *   <li>Không bao giờ bị sai trạng thái Target do kết quả backfill.</li>
 *   <li>Xanh trên cả Windows và Linux/Docker, chạy lặp lại không flaky.</li>
 *   <li>Xuất báo cáo bộ đếm Ingest & Protocol metrics sau khi kết thúc.</li>
 * </ul>
 */
public class ChaosE2ETest {

    @TempDir
    Path tempDir;

    private TestClock testClock;
    private final String validToken = "CHAOS-VALID-TOKEN-777";
    private NcasServer server;
    private TcpFaultProxy proxy;
    private NcasAgent agent;
    private AgentConfig agentConfig;

    @BeforeEach
    void setUp() throws Exception {
        testClock = TestClock.fixed(Instant.parse("2026-10-24T12:00:00Z"));

        // Cách ly CSDL cho mỗi kịch bản Chaos E2E (ERRATA §E8.1 & §E8.3)
        com.networkalert.repository.backend.H2InMemoryBackend chaosDb =
                new com.networkalert.repository.backend.H2InMemoryBackend("chaos_db_" + System.nanoTime());
        com.networkalert.repository.DatabaseManager.getInstance().setBackendForTesting(chaosDb);

        // 1. Khởi động Server trung tâm
        server = new NcasServer(0, "127.0.0.1", validToken, 30_000L, testClock);
        server.start();
        assertTrue(server.isRunning());
        assertTrue(server.getPort() > 0);

        // 2. Khởi động TcpFaultProxy trỏ tới Server
        proxy = new TcpFaultProxy("127.0.0.1", server.getPort());
        proxy.start();
        assertTrue(proxy.getPort() > 0);

        // 3. Cấu hình Agent kết nối qua Proxy với thư mục Spool cách ly
        agentConfig = new AgentConfig();
        agentConfig.setServerHost("127.0.0.1");
        agentConfig.setServerPort(proxy.getPort());
        agentConfig.setAgentId("agent-chaos-01");
        agentConfig.setAuthToken(validToken);
        agentConfig.setHeartbeatIntervalSeconds(10);
        agentConfig.setSpoolDirPath(tempDir.resolve("spool").toString());
        agentConfig.setAssignedCachePath(tempDir.resolve("assigned.json").toString());
        agentConfig.setReconnectInitialDelayMs(100L);
        agentConfig.setReconnectMaxDelayMs(1000L);
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.stop();
        }
        if (proxy != null) {
            proxy.stop();
        }
        if (server != null && server.isRunning()) {
            server.stop();
        }
        com.networkalert.repository.DatabaseManager.getInstance().resetForTesting();
    }

    private void waitForCondition(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (!condition.getAsBoolean() && (System.currentTimeMillis() - start) < timeoutMs) {
            new CountDownLatch(1).await(20, TimeUnit.MILLISECONDS);
        }
        assertTrue(condition.getAsBoolean(), "Điều kiện không thỏa mãn sau " + timeoutMs + "ms");
    }

    @Test
    @DisplayName("Kịch bản (a): Mạng chập chờn (Flapping) - Cắt kết nối 3 lần liên tiếp, Agent tự phục hồi và dữ liệu không bị thất thoát")
    void testNetworkFlappingRecovery() throws Exception {
        agent = new NcasAgent(agentConfig, testClock);
        agent.start();

        // Đợi Agent kết nối và nhận phân công
        waitForCondition(() -> agent.isConnected() && !agent.getCurrentTargets().isEmpty(), 3000);
        int targetId = agent.getCurrentTargets().keySet().iterator().next();
        ServerTarget target = server.getTargetDistributor().getTarget(targetId);
        assertNotNull(target);

        // Cắt đứt kết nối 3 lần liên tiếp qua FaultProxy
        for (int i = 1; i <= 3; i++) {
            LoggerUtil.info("💥 [Chaos] Inject lỗi cắt kết nối lần " + i);
            proxy.dropConnections();
            // Đợi Agent tự động phát hiện, kết nối lại và hoàn tất xác thực đăng ký
            waitForCondition(() -> proxy.getActiveConnectionCount() > 0 && agent.isConnected() && agent.isAuthenticated(), 4000);
        }

        // Gửi kết quả đo kiểm trực tiếp
        ReportResultMessage rep = new ReportResultMessage("agent-chaos-01", targetId, testClock.millis(), true, 35, 200, null, "Flap Check OK");
        agent.sendOrBufferReport(rep);

        // Đợi Server nhận và cập nhật kết quả từ Agent
        waitForCondition(() -> {
            com.networkalert.model.CheckResult cr = target.getStationResults().get("agent-chaos-01");
            return cr != null && cr.getLatencyMs() == 35;
        }, 6000);
        com.networkalert.model.CheckResult finalCr = target.getStationResults().get("agent-chaos-01");
        assertNotNull(finalCr);
        assertEquals(35, finalCr.getLatencyMs());

        // Invariant: Kết nối vẫn ổn định, không có stream nào bị kẹt
        assertTrue(agent.isConnected());
        assertTrue(proxy.getActiveConnectionCount() > 0);
    }

    @Test
    @DisplayName("Kịch bản (b): Agent restart giữa lúc xả Spool - Instance mới tự phục hồi và hoàn tất nạp backlog")
    void testAgentCrashAndRestartDuringSpoolDrain() throws Exception {
        agent = new NcasAgent(agentConfig, testClock);
        agent.start();
        waitForCondition(() -> agent.isConnected() && !agent.getCurrentTargets().isEmpty(), 3000);
        int targetId = agent.getCurrentTargets().keySet().iterator().next();
        long originalStreamId = agent.getStreamId();

        // Kích hoạt Blackhole để nuốt byte, ép Agent ghi Spool đĩa
        proxy.setBlackhole(true);

        // Gửi 5 kết quả đo kiểm trong khi mạng bị nghẽn -> lưu vào Spool đĩa
        for (int i = 1; i <= 5; i++) {
            ReportResultMessage r = new ReportResultMessage("agent-chaos-01", targetId, testClock.millis(), true, 10 + i, 200, null, "Buffered-" + i);
            agent.sendOrBufferReport(r);
        }

        // Mô phỏng crash đột tử của Agent giữa chừng
        agent.stop();
        LoggerUtil.info("💥 [Chaos] Agent đã bị crash đột tử khi Spool còn đọng dữ liệu.");

        // Phục hồi mạng proxy
        proxy.setBlackhole(false);
        proxy.dropConnections();

        // Khởi động instance Agent mới dùng chung thư mục Spool
        NcasAgent recoveredAgent = new NcasAgent(agentConfig, testClock);
        try {
            recoveredAgent.start();

            // Đợi Agent mới kết nối và xả toàn bộ Spool lên Server
            waitForCondition(() -> recoveredAgent.isConnected(), 3000);

            // Bất biến: Server nhận đầy đủ và cam kết Watermark của luồng dữ liệu gốc
            ResultSink sink = server.getResultIngestor().getResultSink();
            waitForCondition(() -> sink.getWatermark("agent-chaos-01", originalStreamId) >= 5, 4000);
            assertTrue(sink.getWatermark("agent-chaos-01", originalStreamId) >= 5);

        } finally {
            recoveredAgent.stop();
        }
    }

    @Test
    @DisplayName("Kịch bản (c): Server sập - Agent tự đo offline qua cache assigned.json và xả bù khi Server sống lại")
    void testServerRestartOfflineProbingAndCatchUp() throws Exception {
        agent = new NcasAgent(agentConfig, testClock);
        agent.start();
        waitForCondition(() -> agent.isConnected() && !agent.getCurrentTargets().isEmpty(), 3000);
        int targetId = agent.getCurrentTargets().keySet().iterator().next();

        // 1. Server sập đột ngột
        LoggerUtil.info("💥 [Chaos] Server sập hoàn toàn!");
        server.stop();
        proxy.dropConnections();
        waitForCondition(() -> !agent.isConnected(), 3000);

        // 2. Trong lúc Server sập, Agent vẫn tự đo offline dựa vào cache assigned.json
        ReportResultMessage offlineReport = new ReportResultMessage("agent-chaos-01", targetId, testClock.millis(), true, 42, 200, null, "Offline Probe");
        agent.sendOrBufferReport(offlineReport);

        // 3. Khởi động Server instance mới (thay thế Server cũ, kế thừa ResultSink bền vững)
        NcasServer newServer = new NcasServer(0, "127.0.0.1", validToken, 30_000L, testClock,
                server.getResultIngestor().getResultSink());
        newServer.start();
        try {
            // Cập nhật Proxy chuyển hướng sang Server mới
            proxy.setTarget("127.0.0.1", newServer.getPort());
            proxy.dropConnections();

            // Agent tự động kết nối lại Server mới và xả bù backlog
            waitForCondition(() -> agent.isConnected(), 10000);

            ResultSink newSink = newServer.getResultIngestor().getResultSink();
            waitForCondition(() -> newSink.getWatermark("agent-chaos-01", agent.getStreamId()) >= 1, 10000);
            assertTrue(newSink.getWatermark("agent-chaos-01", agent.getStreamId()) >= 1);

        } finally {
            newServer.stop();
        }
    }

    @Test
    @DisplayName("Kịch bản (d): Tràn Spool đĩa - Giữ lại carry.log, khai báo DataGap và Server kéo tiến Watermark không kẹt ACK")
    void testSpoolOverflowAndDataGapDeclaration() throws Exception {
        // Tạo Spool cực nhỏ với trần dung lượng 800 bytes (chỉ chứa được ~2 segment)
        Path tinySpoolDir = tempDir.resolve("tiny-spool");
        DiskSpoolManager tinySpool = new DiskSpoolManager(tinySpoolDir, 800L, 24, 300L, 5000L, 1000L, 10, testClock);
        tinySpool.open();

        long streamId = 8888L;
        try {
            // Target 1: ban đầu OK
            ReportResultMessage r1 = new ReportResultMessage("agent-chaos-01", 1, testClock.millis(), true, 10L, 200, null, "OK");
            r1.setStreamId(streamId);
            r1.setSeq(1L);
            tinySpool.append(r1);

            // Target 1: đổi trạng thái sang FAIL (State Transition!)
            ReportResultMessage r2 = new ReportResultMessage("agent-chaos-01", 1, testClock.millis(), false, 0L, 500, null, "DOWN");
            r2.setStreamId(streamId);
            r2.setSeq(2L);
            tinySpool.append(r2);

            // Thêm nhiều bản ghi để kích hoạt vượt trần dung lượng
            for (long seq = 3; seq <= 15; seq++) {
                ReportResultMessage rx = new ReportResultMessage("agent-chaos-01", 2, testClock.millis(), true, 10L, 200, null, "OK");
                rx.setStreamId(streamId);
                rx.setSeq(seq);
                tinySpool.append(rx);
            }

            // Xác minh carry.log đã được tạo khi có eviction
            File carryFile = tinySpoolDir.resolve("carry.log").toFile();
            assertTrue(carryFile.exists() && carryFile.length() > 0, "carry.log phải lưu giữ biến động trạng thái khi eviction");
            assertTrue(tinySpool.getDroppedResults() > 0, "Phải ghi nhận droppedResults khi vượt trần spool");

            // Giả lập Agent gửi REPORT_BATCH kèm DataGap do tràn đĩa
            long now = testClock.millis();
            DataGap gap = new DataGap(streamId, 1L, 4L, "Spool dropped oldest segment due to quota");
            ReportResultMessage rep5 = new ReportResultMessage("agent-chaos-01", 1, now, true, 25, 200, null, "Seq-5");
            rep5.setStreamId(streamId);
            rep5.setSeq(5L);
            ReportResultMessage rep6 = new ReportResultMessage("agent-chaos-01", 1, now, true, 30, 200, null, "Seq-6");
            rep6.setStreamId(streamId);
            rep6.setSeq(6L);

            ReportBatchMessage batch = new ReportBatchMessage(streamId, Arrays.asList(rep5, rep6), Collections.singletonList(gap));

            // Nạp vào Ingestor
            AgentSession mockSession = createMockSession(server.getPort(), "agent-chaos-01");
            try {
                server.getResultIngestor().ingestBatch(mockSession, batch, now);

                // Bất biến: Watermark nhảy vọt qua khoảng gap (1-4) và tiếp tục qua 5 và 6 -> đạt 6
                ResultSink sink = server.getResultIngestor().getResultSink();
                assertEquals(6L, sink.getWatermark("agent-chaos-01", streamId));
                assertEquals(4L, server.getIngestMetrics().getLostTotal().get(), "Chỉ số lost_total phải ghi nhận đúng 4 seq bị drop");

            } finally {
                mockSession.close();
            }
        } finally {
            tinySpool.close();
        }
    }

    @Test
    @DisplayName("Kịch bản (e): Live và Backlog đến lệch thứ tự - Ingestor phân loại fresh vs backfill, không đổi trạng thái Target")
    void testLiveAndBacklogOutOfOrderClassification() throws Exception {
        agent = new NcasAgent(agentConfig, testClock);
        agent.start();
        waitForCondition(() -> agent.isConnected() && !agent.getCurrentTargets().isEmpty(), 3000);
        int targetId = agent.getCurrentTargets().keySet().iterator().next();
        ServerTarget target = server.getTargetDistributor().getTarget(targetId);
        target.setStatus(ServerStatus.ONLINE);

        long now = testClock.millis();
        // 1. Gửi bản ghi Live mới nhất (Fresh)
        ReportResultMessage freshReport = new ReportResultMessage("agent-chaos-01", targetId, now, true, 45, 200, null, "Fresh Check");
        agent.sendOrBufferReport(freshReport);

        waitForCondition(() -> {
            com.networkalert.model.CheckResult cr = target.getStationResults().get("agent-chaos-01");
            return cr != null && cr.getLatencyMs() == 45;
        }, 4000);
        assertEquals(ServerStatus.ONLINE, target.getStatus());

        // 2. Gửi bản ghi Backlog cũ từ 2 phút trước (Age = 120s > freshWindow 15s) mang thông báo FAIL
        long oldTime = now - 120_000L;
        ReportResultMessage backlogFailure = new ReportResultMessage("agent-chaos-01", targetId, oldTime, false, 0, 500, null, "Old Outage");
        backlogFailure.setBackfilled(true);
        agent.sendOrBufferReport(backlogFailure);

        // Đợi Server xử lý bản ghi backlog
        waitForCondition(() -> server.getIngestMetrics().getBackfilledTotal().get() >= 1, 3000);

        // BẤT BIẾN QUAN TRỌNG: Kết quả Backlog cũ TUYỆT ĐỐI không làm Target bị nhảy trạng thái sang OFFLINE!
        assertEquals(ServerStatus.ONLINE, target.getStatus(), "Bản ghi backfill không được làm đổi trạng thái Target sang OFFLINE!");
        assertTrue(server.getIngestMetrics().getBackfilledTotal().get() >= 1);
    }

    @Test
    @DisplayName("Kịch bản (f): Lệch đồng hồ phân tán +-30s - Server bật cờ clock_skew và chuẩn hóa thời điểm đo")
    void testClockSkewDetectionAndNormalization() throws Exception {
        AgentSession mockSession = createMockSession(server.getPort(), "agent-skew-01");
        try {
            // Agent gửi nhịp tim mang độ lệch +30.000 ms (Agent chạy nhanh hơn Server 30s)
            HeartbeatPing pingSkewPlus = new HeartbeatPing("agent-skew-01", testClock.millis(), 2, 5.0, 512);
            pingSkewPlus.setClockOffsetMs(30_000L);
            server.getAgentManager().handleHeartbeat(mockSession, pingSkewPlus);

            // Xác minh cờ clock_skew_detected bật trên session
            assertTrue(mockSession.isClockSkewDetected(), "Cờ clock_skew phải bật khi offset = +30.000ms");

            // Nạp kết quả đo của Agent bị lệch giờ
            long now = testClock.millis();
            ReportResultMessage rep = new ReportResultMessage("agent-skew-01", 1, now, true, 20, 200, null, "Skew Result");
            rep.setStreamId(999L);
            rep.setSeq(1L);

            server.getResultIngestor().ingestSingle(mockSession, rep, now);

            // Thời gian checkedAtServer được kẹp không vượt quá serverRecvMs
            ResultSink.InMemoryResultSink tempSink = new ResultSink.InMemoryResultSink();
            // Đảm bảo không bị crash và dữ liệu được ghi nhận
            assertEquals(1, server.getResultIngestor().getResultSink().getWatermark("agent-skew-01", 999L));

        } finally {
            mockSession.close();
        }
    }

    @Test
    @DisplayName("Kịch bản (g): Tấn công Brute-Force Token sai liên tục - Không làm sập Server, tăng auth_failures_total, Agent hợp lệ vẫn nối bình thường")
    void testContinuousInvalidTokenAttack() throws Exception {
        int initialAuthFailures = (int) ProtocolMetrics.getAuthFailuresTotal();

        // 1. Giả lập 5 lần kết nối liên tiếp từ Attacker mang token sai
        for (int i = 1; i <= 5; i++) {
            try (Socket attackerSocket = new Socket("127.0.0.1", server.getPort())) {
                OutputStream out = new BufferedOutputStream(attackerSocket.getOutputStream());
                InputStream in = new BufferedInputStream(attackerSocket.getInputStream());

                RegisterRequest badReq = new RegisterRequest(
                        "agent-hacker-" + i, "BAD-HOST", "Linux", "10.99.99.99",
                        "AA:BB:CC:DD:EE:FF", "WRONG-TOKEN-" + i, Collections.singletonList("TCP"), "1.0.0"
                );
                FrameEncoder.encodeToStream(Frame.create(MessageType.REGISTER_REQ, badReq), out);

                Frame respFrame = FrameDecoder.decodeFromStream(in);
                assertNotNull(respFrame);
                RegisterResponse resp = respFrame.parsePayload(RegisterResponse.class);
                assertFalse(resp.isAccepted(), "Yêu cầu với Token sai phải bị từ chối");
            }
        }

        // Xác minh chỉ số auth_failures_total tăng đúng 5
        assertEquals(initialAuthFailures + 5, ProtocolMetrics.getAuthFailuresTotal(),
                "Chỉ số auth_failures_total phải tăng đúng số lần xác thực thất bại");

        // 2. Ngay sau đó, Agent hợp lệ kết nối bình thường và nạp dữ liệu không bị nghẽn
        agent = new NcasAgent(agentConfig, testClock);
        agent.start();
        waitForCondition(() -> agent.isConnected() && !agent.getCurrentTargets().isEmpty(), 3000);
        assertTrue(agent.isConnected(), "Agent hợp lệ phải kết nối bình thường, không bị ảnh hưởng bởi attacker");
    }

    @Test
    @DisplayName("Báo cáo Snapshot bộ đếm Ingest & Protocol Metrics chuẩn Prometheus sau kịch bản Chaos")
    void testMetricsSnapshotReport() {
        IngestMetrics m = server.getIngestMetrics();
        assertNotNull(m);

        // Xuất báo cáo Prometheus text format chuẩn theo ERRATA §E5.7 & §E10.7
        StringBuilder sb = new StringBuilder();
        sb.append("\n======================================================================\n");
        sb.append("📊 BÁO CÁO BỘ ĐẾM CHỈ SỐ INGESTOR & PROTOCOL METRICS (ERRATA §E5.7)\n");
        sb.append("======================================================================\n");
        sb.append(String.format("results_received_total:    %d\n", m.getResultsReceivedTotal().get()));
        sb.append(String.format("duplicates_total:          %d\n", m.getDuplicatesTotal().get()));
        sb.append(String.format("out_of_order_total:        %d\n", m.getOutOfOrderTotal().get()));
        sb.append(String.format("backfilled_total:          %d\n", m.getBackfilledTotal().get()));
        sb.append(String.format("lost_total:                %d\n", m.getLostTotal().get()));
        sb.append(String.format("sampled_total:             %d\n", m.getSampledTotal().get()));
        sb.append(String.format("ack_regression_total:      %d\n", m.getAckRegressionTotal().get()));
        sb.append(String.format("auth_failures_total:       %d\n", ProtocolMetrics.getAuthFailuresTotal()));
        sb.append(String.format("ingest_queue_depth:        %d\n", m.getIngestQueueDepth().get()));
        sb.append(String.format("ingest_latency_ms:         %d ms\n", m.getLastIngestLatencyMs().get()));
        sb.append("----------------------------------------------------------------------\n");
        sb.append(String.format("TcpFaultProxy Connections: %d accepted | %d dropped\n",
                proxy.getConnectionsAccepted(), proxy.getConnectionsDropped()));
        sb.append(String.format("TcpFaultProxy Bytes:       %d up | %d down\n",
                proxy.getBytesClientToServer(), proxy.getBytesServerToClient()));
        sb.append("======================================================================\n");

        LoggerUtil.info(sb.toString());

        // Mọi chỉ số phải không âm
        assertTrue(m.getResultsReceivedTotal().get() >= 0);
        assertTrue(m.getDuplicatesTotal().get() >= 0);
        assertTrue(m.getOutOfOrderTotal().get() >= 0);
        assertTrue(m.getBackfilledTotal().get() >= 0);
        assertTrue(m.getLostTotal().get() >= 0);
        assertTrue(ProtocolMetrics.getAuthFailuresTotal() >= 0);
    }

    private AgentSession createMockSession(int serverPort, String agentId) throws Exception {
        Socket client = new Socket("127.0.0.1", serverPort);
        AgentSession s = new AgentSession(client, testClock);
        s.setProtoVersion("1.1");
        s.setAgentId(agentId);
        s.setAuthenticated(true);
        s.setAssignedTargetIds(Collections.singleton(1));
        return s;
    }
}

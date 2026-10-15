package com.networkalert.server;

import com.networkalert.model.AlertConfig;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetScope;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.AckMessage;
import com.networkalert.protocol.messages.DataGap;
import com.networkalert.protocol.messages.ReportBatchMessage;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.repository.ConfigRepository;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.server.ingest.IngestMetrics;
import com.networkalert.server.ingest.IngestedResult;
import com.networkalert.server.ingest.ResultIngestor;
import com.networkalert.server.ingest.ResultSink;
import com.networkalert.service.AlertService;
import com.networkalert.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử toàn diện đường ống Ingestor (ResultIngestor Pipeline) theo ERRATA §E5 & Task T1.6:
 * - Khử trùng lặp (Deduplication) và ACK mang Watermark hiện tại
 * - Xử lý lệch thứ tự (Out-of-Order) và nhảy cóc Watermark khi đủ chuỗi
 * - Quy tắc Backfill: lưu vào Sink nhưng không đổi trạng thái Target và không kích hoạt cảnh báo
 * - Chuẩn hóa thời gian phân tán NTP và cờ cảnh báo clock_skew
 * - Backpressure: từ chối và không ACK khi hàng đợi đầy
 * - Xử lý DataGap trong REPORT_BATCH kéo tiến Watermark và đếm lost_total
 * - Phân quyền Agent chỉ nạp dữ liệu cho Target được giao (E2.6)
 */
public class ResultIngestorTest {

    private TestClock testClock;
    private TargetRepository targetRepository;
    private TargetDistributor targetDistributor;
    private AlertService alertService;
    private ResultSink.InMemoryResultSink inMemorySink;
    private IngestMetrics metrics;
    private ResultIngestor ingestor;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private Socket serverSocketSide;
    private AgentSession session;

    private ServerTarget target1;
    private ServerTarget target2;

    @BeforeEach
    void setUp() throws Exception {
        testClock = TestClock.fixed(Instant.parse("2026-10-24T12:00:00Z"));

        targetRepository = new TargetRepository();
        target1 = new ServerTarget(1, "Web-Prod", "10.0.0.1", 80, ProtocolType.HTTP, 5, 2000, 300L, TargetScope.AGENT, "DEFAULT", true);
        target2 = new ServerTarget(2, "Multi-Cluster", "10.0.0.2", 443, ProtocolType.TCP, 10, 2000, 300L, TargetScope.MULTI, "DEFAULT", true);
        targetDistributor = new TargetDistributor(targetRepository);
        targetDistributor.addOrUpdateTarget(target1);
        targetDistributor.addOrUpdateTarget(target2);

        ConfigRepository configRepo = new ConfigRepository();
        IncidentRepository incidentRepo = new IncidentRepository();
        alertService = new AlertService(incidentRepo, configRepo, testClock);

        inMemorySink = new ResultSink.InMemoryResultSink();
        metrics = new IngestMetrics();
        ingestor = new ResultIngestor(targetDistributor, inMemorySink, alertService, metrics, testClock);

        // Tạo AgentSession kết nối qua loopback socket để nhận Frame ACK
        serverSocket = new ServerSocket(0);
        clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        serverSocketSide = serverSocket.accept();

        session = new AgentSession(serverSocketSide, testClock);
        session.setProtoVersion("1.1");
        session.setAgentId("agent-edge-01");
        session.setAuthenticated(true);
        session.setAssignedTargetIds(Collections.singleton(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) session.close();
        if (clientSocket != null) clientSocket.close();
        if (serverSocketSide != null) serverSocketSide.close();
        if (serverSocket != null) serverSocket.close();
    }

    @Test
    @DisplayName("T1.6-1: Deduplication - gửi lại seq cũ bị từ chối persist nhưng vẫn nhận ACK kèm watermark")
    void testDeduplicationAndAck() throws Exception {
        long streamId = 100L;
        long seq = 1L;
        long checkedAt = testClock.millis() - 1000L;
        long serverRecvMs = testClock.millis();

        ReportResultMessage r1 = new ReportResultMessage(1, true, 50, 200, "OK", checkedAt);
        r1.setStreamId(streamId);
        r1.setSeq(seq);

        ingestor.ingestSingle(session, r1, serverRecvMs);

        // 1. Bản ghi đầu tiên phải được nạp và lưu bền vào Sink
        assertEquals(1, inMemorySink.getStoredResults().size());
        assertEquals(1L, inMemorySink.getWatermark(session.getAgentId(), streamId));
        assertEquals(1, metrics.getResultsReceivedTotal().get());
        assertEquals(0, metrics.getDuplicatesTotal().get());

        // Đọc ACK frame trả về client
        InputStream in = new BufferedInputStream(clientSocket.getInputStream());
        Frame ackFrame1 = FrameDecoder.decodeFromStream(in);
        assertNotNull(ackFrame1);
        assertEquals(MessageType.ACK, ackFrame1.getType());
        AckMessage ack1 = ackFrame1.parsePayload(AckMessage.class);
        assertEquals(1L, ack1.getWatermarkForStream(streamId));

        // 2. Gửi lại chính seq 1 (duplicate)
        ReportResultMessage r2 = new ReportResultMessage(1, true, 45, 200, "OK Duplicate", checkedAt);
        r2.setStreamId(streamId);
        r2.setSeq(seq);

        ingestor.ingestSingle(session, r2, serverRecvMs);

        // Sink không tăng thêm bản ghi nào
        assertEquals(1, inMemorySink.getStoredResults().size(), "Bản trùng không được lưu thêm vào Sink");
        assertEquals(1, metrics.getDuplicatesTotal().get(), "Chỉ số duplicates_total phải tăng");

        // Vẫn phải nhận được ACK mang watermark = 1 để Agent xả spool
        Frame ackFrame2 = FrameDecoder.decodeFromStream(in);
        assertNotNull(ackFrame2);
        AckMessage ack2 = ackFrame2.parsePayload(AckMessage.class);
        assertEquals(1L, ack2.getWatermarkForStream(streamId));
    }

    @Test
    @DisplayName("T1.6-2: Xử lý Out-of-order - seq 2 tới trước seq 1, watermark nhảy cóc khi nhận đủ chuỗi")
    void testOutOfOrderHandling() throws Exception {
        long streamId = 200L;
        long t = testClock.millis();

        // 1. Gửi seq 2 trước
        ReportResultMessage r2 = new ReportResultMessage(1, true, 60, 200, "OK seq 2", t);
        r2.setStreamId(streamId);
        r2.setSeq(2L);
        ingestor.ingestSingle(session, r2, t);

        assertEquals(1, metrics.getOutOfOrderTotal().get(), "Phải ghi nhận 1 lượt out_of_order");
        assertEquals(0L, inMemorySink.getWatermark(session.getAgentId(), streamId), "Watermark chưa nhảy vì thiếu seq 1");

        InputStream in = new BufferedInputStream(clientSocket.getInputStream());
        Frame ackFrame1 = FrameDecoder.decodeFromStream(in);
        AckMessage ack1 = ackFrame1.parsePayload(AckMessage.class);
        assertEquals(0L, ack1.getWatermarkForStream(streamId));

        // 2. Gửi seq 1 để bù chỗ hổng
        ReportResultMessage r1 = new ReportResultMessage(1, true, 55, 200, "OK seq 1", t);
        r1.setStreamId(streamId);
        r1.setSeq(1L);
        ingestor.ingestSingle(session, r1, t);

        // Watermark giờ đây phải nhảy cóc lên 2 vì seq 1 và seq 2 đã đủ
        assertEquals(2L, inMemorySink.getWatermark(session.getAgentId(), streamId));
        Frame ackFrame2 = FrameDecoder.decodeFromStream(in);
        AckMessage ack2 = ackFrame2.parsePayload(AckMessage.class);
        assertEquals(2L, ack2.getWatermarkForStream(streamId));

        assertEquals(2, inMemorySink.getStoredResults().size());
    }

    @Test
    @DisplayName("T1.6-3: Quy tắc Backfill (E5.3) - lưu vào Sink nhưng KHÔNG đổi trạng thái Target và KHÔNG báo động")
    void testBackfillDoesNotChangeTargetStateOrTriggerAlert() {
        target1.setStatus(ServerStatus.ONLINE);
        assertEquals(ServerStatus.ONLINE, target1.getStatus());

        // Giả lập kết quả trễ 60 giây (vượt xa freshWindow = 2 * 5s + 5s = 15s)
        long serverRecvMs = testClock.millis();
        long oldCheckedAt = serverRecvMs - 60_000L;

        ReportResultMessage oldFailure = new ReportResultMessage(1, false, 0, 500, "Server Down 60s ago", oldCheckedAt);
        oldFailure.setStreamId(300L);
        oldFailure.setSeq(1L);

        AtomicInteger alertCallCount = new AtomicInteger(0);
        ingestor.addCheckResultListener((t, cr) -> alertCallCount.incrementAndGet());

        ingestor.ingestSingle(session, oldFailure, serverRecvMs);

        // 1. Phải được đánh dấu là backfilled
        assertEquals(1, metrics.getBackfilledTotal().get());
        assertEquals(1, inMemorySink.getStoredResults().size());
        IngestedResult saved = inMemorySink.getStoredResults().get(0);
        assertTrue(saved.isBackfilled(), "Kết quả phải có cờ backfilled=true");

        // 2. Trạng thái Target TUYỆT ĐỐI không bị thay đổi sang OFFLINE
        assertEquals(ServerStatus.ONLINE, target1.getStatus(), "Trạng thái Target không được đổi bởi dữ liệu backfill");

        // 3. Listener fresh dispatch không bị gọi
        assertEquals(0, alertCallCount.get(), "Không phát dispatch cho kết quả backfill");
    }

    @Test
    @DisplayName("T1.6-4: Chuẩn hóa thời gian phân tán NTP và bật cờ clock_skew khi lệch > 5000ms")
    void testClockNormalizationAndSkewFlag() {
        // Agent đi chậm 6000ms so với Server (offset = +6000ms)
        session.setClockOffsetMs(6000L);
        assertFalse(session.isClockSkewDetected());

        long serverRecvMs = testClock.millis();
        long agentCheckedAt = serverRecvMs - 10000L;

        ReportResultMessage r = new ReportResultMessage(1, true, 40, 200, "NTP probe", agentCheckedAt);
        r.setStreamId(400L);
        r.setSeq(1L);

        ingestor.ingestSingle(session, r, serverRecvMs);

        // Cờ clock_skew phải được bật
        assertTrue(session.isClockSkewDetected(), "Cờ clock_skew phải bật khi lệch > 5000ms");

        // Thời gian checkedAtServer được điều chỉnh cộng bù offset
        assertEquals(1, inMemorySink.getStoredResults().size());
        IngestedResult saved = inMemorySink.getStoredResults().get(0);
        assertEquals(agentCheckedAt + 6000L, saved.getCheckedAtServerMs(), "Thời điểm checkedAtServer phải được cộng offset");
    }

    @Test
    @DisplayName("T1.6-5: Backpressure - từ chối tiếp nhận và không gửi ACK khi vượt trần hàng đợi")
    void testBackpressureRejection() throws Exception {
        // Tạo Ingestor với hàng đợi tối đa chỉ 1
        ResultIngestor tinyIngestor = new ResultIngestor(targetDistributor, inMemorySink, alertService, metrics, testClock, 1);

        long now = testClock.millis();
        ReportResultMessage r1 = new ReportResultMessage(1, true, 30, 200, "OK 1", now);
        r1.setStreamId(500L);
        r1.setSeq(1L);

        // Gửi lô gồm 2 bản ghi -> 2 > maxQueueDepth 1 -> backpressure từ chối
        ReportBatchMessage batch = new ReportBatchMessage(500L, Arrays.asList(
                new ReportResultMessage(1, true, 30, 200, "Batch 1", now),
                new ReportResultMessage(1, true, 35, 200, "Batch 2", now)
        ), Collections.emptyList());

        tinyIngestor.ingestBatch(session, batch, now);

        assertEquals(1, metrics.getProtocolErrorCount("backpressure_drop"));
        assertEquals(0, inMemorySink.getStoredResults().size(), "Không ghi dữ liệu khi bị backpressure drop");

        // Không có ACK nào được gửi về socket
        assertEquals(0, clientSocket.getInputStream().available(), "Không bao giờ gửi ACK khi bị từ chối do backpressure");
    }

    @Test
    @DisplayName("T1.6-6: REPORT_BATCH kèm DataGap - watermark nhảy vọt qua khoảng gap và tăng lost_total")
    void testBatchIngestWithGapProgression() throws Exception {
        long streamId = 600L;
        long now = testClock.millis();

        // Gap từ seq 1 đến seq 5 (bị mất 5 seq)
        DataGap gap = new DataGap(streamId, 1L, 5L, "Spool dropped due to quota");
        ReportResultMessage item6 = new ReportResultMessage(1, true, 20, 200, "Seq 6", now);
        item6.setSeq(6L);
        ReportResultMessage item7 = new ReportResultMessage(1, true, 25, 200, "Seq 7", now);
        item7.setSeq(7L);

        ReportBatchMessage batch = new ReportBatchMessage(streamId, Arrays.asList(item6, item7), Collections.singletonList(gap));

        ingestor.ingestBatch(session, batch, now);

        // Watermark phải nhảy cóc qua gap (1-5) và tiếp tục qua 6 và 7 -> đạt 7L
        assertEquals(7L, inMemorySink.getWatermark(session.getAgentId(), streamId));
        assertEquals(5L, metrics.getLostTotal().get(), "Chỉ số lost_total phải tăng đúng 5 mốc bị mất");
        assertEquals(2, inMemorySink.getStoredResults().size());

        InputStream in = new BufferedInputStream(clientSocket.getInputStream());
        Frame ackFrame = FrameDecoder.decodeFromStream(in);
        assertNotNull(ackFrame);
        AckMessage ack = ackFrame.parsePayload(AckMessage.class);
        assertEquals(7L, ack.getWatermarkForStream(streamId));
    }

    @Test
    @DisplayName("T1.6-7: Phân quyền nạp dữ liệu (E2.6) - từ chối Agent báo cáo Target không được giao")
    void testAuthzRejection() {
        long now = testClock.millis();

        // Target 1 đã được phân công cho session. Target 3 không được phân công và không tồn tại
        ReportResultMessage unassigned = new ReportResultMessage(999, true, 30, 200, "Rogue Target", now);
        unassigned.setStreamId(700L);
        unassigned.setSeq(1L);

        ingestor.ingestSingle(session, unassigned, now);

        assertEquals(1, metrics.getProtocolErrorCount("target_not_found"));
        assertEquals(0, inMemorySink.getStoredResults().size());

        // Target có scope AGENT nhưng session không được giao target đó
        ServerTarget singleTarget3 = new ServerTarget(3, "Private Target", "10.0.0.3", 80, ProtocolType.TCP, 5, 2000, 300L, TargetScope.AGENT, "DEFAULT", true);
        targetDistributor.addOrUpdateTarget(singleTarget3);

        ReportResultMessage forbidden = new ReportResultMessage(3, true, 30, 200, "Forbidden Target", now);
        forbidden.setStreamId(700L);
        forbidden.setSeq(2L);

        ingestor.ingestSingle(session, forbidden, now);

        assertEquals(1, metrics.getAuthFailuresTotal().get(), "Chỉ số auth_failures_total phải tăng");
        assertEquals(0, inMemorySink.getStoredResults().size(), "Dữ liệu trái phép không được ghi vào Sink");
    }
}

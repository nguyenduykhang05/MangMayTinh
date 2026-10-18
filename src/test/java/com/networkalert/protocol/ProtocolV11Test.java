package com.networkalert.protocol;

import com.networkalert.agent.AgentConfig;
import com.networkalert.agent.NcasAgent;
import com.networkalert.model.ServerTarget;
import com.networkalert.protocol.messages.*;
import com.networkalert.server.AgentSession;
import com.networkalert.server.NcasServer;
import com.networkalert.server.StreamTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử toàn diện giao thức NCAS-P v1.1 (Task T1.4 & BLUEPRINT-ERRATA §E3).
 */
public class ProtocolV11Test {

    private NcasServer server;
    private final Clock testClock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneId.of("UTC"));
    private static final Path GOLDEN_V11_DIR = Path.of("src/test/resources/protocol/golden_v1_1");

    @BeforeEach
    void setUp() throws Exception {
        if (!Files.exists(GOLDEN_V11_DIR)) {
            Files.createDirectories(GOLDEN_V11_DIR);
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    @DisplayName("T1.4.1 - Golden Vectors v1.1: Mã hóa và giải mã chính xác các MessageType mới")
    void testV11GoldenVectorsEncodeDecode() throws Exception {
        // 1. Vector ACK (0x000F)
        AckMessage ackMsg = new AckMessage(
                Collections.singletonList(new StreamAck(123456789L, 100L)),
                10L,
                1700000000100L,
                1700000000105L
        );
        Frame ackFrame = Frame.create(MessageType.ACK, ackMsg);
        assertGoldenVectorMatchesOrSave("0f_ack", ackFrame);

        // 2. Vector REPORT_BATCH (0x0010)
        ReportResultMessage r1 = new ReportResultMessage("agent-1", 1, 1700000000000L, true, 10L, 200, null, "OK");
        r1.setStreamId(123456789L);
        r1.setSeq(101L);
        ReportResultMessage r2 = new ReportResultMessage("agent-1", 2, 1700000000005L, false, 3000L, 500, null, "FAIL");
        r2.setStreamId(123456789L);
        r2.setSeq(102L);

        DataGap gap = new DataGap(123456789L, 103L, 105L, "dropped");
        ReportBatchMessage batchMsg = new ReportBatchMessage(
                123456789L, 101L, 105L,
                Arrays.asList(r1, r2),
                Collections.singletonList(gap)
        );
        Frame batchFrame = Frame.create(MessageType.REPORT_BATCH, batchMsg);
        assertGoldenVectorMatchesOrSave("10_report_batch", batchFrame);

        // 3. Vector REGISTER_REQ v1.1
        RegisterRequest regReq = new RegisterRequest("agent-v11", "host-v11", "Linux", "10.0.0.1", "00:11:22:33:44:55", null, Arrays.asList("TCP", "HTTP"), "1.1.0");
        regReq.setProtoMax("1.1");
        regReq.setStreamId(123456789L);
        regReq.setAgentName("NCAS-Agent-Node1");
        regReq.setClockMs(1700000000000L);
        Frame regFrame = Frame.create(MessageType.REGISTER_REQ, regReq);
        assertGoldenVectorMatchesOrSave("01_register_req_v1_1", regFrame);

        // 4. Vector HEARTBEAT_PING v1.1
        HeartbeatPing ping = new HeartbeatPing("agent-v11", 1700000000000L, 5, 15.5, 1024L);
        ping.setHbSeq(1L);
        ping.setClockMs(1700000000000L);
        ping.setClockOffsetMs(-12L);
        ping.setClockDelayMs(6L);
        ping.setStreams(Collections.singletonList(new StreamProgress(123456789L, 1L, 200L)));
        Frame pingFrame = Frame.create(MessageType.HEARTBEAT_PING, ping);
        assertGoldenVectorMatchesOrSave("03_heartbeat_ping_v1_1", pingFrame);
    }

    private void assertGoldenVectorMatchesOrSave(String name, Frame frame) throws Exception {
        byte[] frameBytes = FrameEncoder.encodeToBytes(frame);
        Path hexFile = GOLDEN_V11_DIR.resolve(name + ".hex");

        String currentHex = bytesToHex(frameBytes);
        if (!Files.exists(hexFile)) {
            Files.writeString(hexFile, currentHex);
        }

        String expectedHex = Files.readString(hexFile).trim().replaceAll("\\s+", "");
        assertEquals(expectedHex, currentHex, "Vector hex không khớp cho: " + name);

        // Giải mã khứ hồi
        Frame decoded = FrameDecoder.readFrame(new ByteArrayInputStream(frameBytes));
        assertNotNull(decoded);
        assertEquals(frame.getMessageType(), decoded.getMessageType());
        assertEquals(frame.getPayloadLength(), decoded.getPayloadLength());
        assertEquals(frame.getChecksum(), decoded.getChecksum());
    }

    @Test
    @DisplayName("T1.4.2 - Đàm phán protoMax: Client v1.1 đàm phán thành công proto=1.1")
    void testProtoMaxNegotiationV11() throws Exception {
        server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setAllowLegacyAuth(true);
        server.start();

        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            RegisterRequest req = new RegisterRequest("agent-proto-test", "host", "os", "127.0.0.1", "00:00:00:00:00:00", "MASTER-TOKEN", Collections.singletonList("TCP"), "1.1.0");
            req.setProtoMax("1.1");
            req.setStreamId(999888777L);
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

            Frame respFrame = FrameDecoder.readFrame(in);
            assertNotNull(respFrame);
            assertEquals(MessageType.REGISTER_RESP, respFrame.getType());
            RegisterResponse resp = respFrame.parsePayload(RegisterResponse.class);
            assertTrue(resp.isAccepted());
            assertEquals("1.1", resp.getProtoVersion(), "Phiên đàm phán phải là 1.1 khi client khai protoMax=1.1");
        }
    }

    @Test
    @DisplayName("T1.4.3 - Đàm phán protoMax: Client legacy v1.0 (không khai protoMax) được đàm phán proto=1.0")
    void testProtoMaxNegotiationLegacyClientDefaultsTo10() throws Exception {
        server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setAllowLegacyAuth(true);
        server.start();

        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            // Gửi REGISTER_REQ chuẩn v1.0 (không chứa trường protoMax)
            RegisterRequest req = new RegisterRequest("agent-legacy", "host", "os", "127.0.0.1", "00:00:00:00:00:00", "MASTER-TOKEN", Collections.singletonList("TCP"), "1.0.0");
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);

            Frame respFrame = FrameDecoder.readFrame(in);
            assertNotNull(respFrame);
            RegisterResponse resp = respFrame.parsePayload(RegisterResponse.class);
            assertTrue(resp.isAccepted());

            // Sau khi đăng ký, gửi HEARTBEAT_PING
            HeartbeatPing ping = new HeartbeatPing("agent-legacy", testClock.millis(), 2, 5.0, 500L);
            FrameEncoder.writeFrame(Frame.create(MessageType.HEARTBEAT_PING, ping), out);

            Frame pongFrame = readNonAssignFrame(in);
            assertNotNull(pongFrame);
            // Với peer v1.0, Server bắt buộc gửi HEARTBEAT_PONG, KHÔNG được gửi ACK (§E3.1)
            assertEquals(MessageType.HEARTBEAT_PONG, pongFrame.getType(), "Server không được gửi ACK cho peer v1.0!");
        }
    }

    @Test
    @DisplayName("T1.4.4 - Client v1.1 gửi HEARTBEAT_PING nhận được phản hồi ACK mang 2 mốc thời gian NTP")
    void testV11HeartbeatPingReceivesAckWithNtpTimestamps() throws Exception {
        server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setAllowLegacyAuth(true);
        server.start();

        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            RegisterRequest req = new RegisterRequest("agent-ntp-test", "host", "os", "127.0.0.1", "00:00:00:00:00:00", "MASTER-TOKEN", Collections.singletonList("TCP"), "1.1.0");
            req.setProtoMax("1.1");
            req.setStreamId(55555L);
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);
            FrameDecoder.readFrame(in); // Read RegisterResponse

            long t0 = 1700000000000L;
            HeartbeatPing ping = new HeartbeatPing("agent-ntp-test", t0, 1, 2.0, 1000L);
            ping.setHbSeq(77L);
            ping.setClockMs(t0);
            FrameEncoder.writeFrame(Frame.create(MessageType.HEARTBEAT_PING, ping), out);

            Frame ackFrame = readNonAssignFrame(in);
            assertNotNull(ackFrame);
            assertEquals(MessageType.ACK, ackFrame.getType(), "Client v1.1 phải nhận được phản hồi ACK cho nhịp tim!");
            AckMessage ack = ackFrame.parsePayload(AckMessage.class);
            assertNotNull(ack);
            assertEquals(77L, ack.getHbSeq());
            assertNotNull(ack.getServerRecvMs());
            assertNotNull(ack.getServerSendMs());
            assertTrue(ack.getServerSendMs() >= ack.getServerRecvMs());
        }
    }

    @Test
    @DisplayName("T1.4.5 - ClockSyncManager: Tính toán chính xác offset, delay và phát hiện clock_skew")
    void testClockSyncManagerMathAndSkewDetection() {
        ClockSyncManager mgr = new ClockSyncManager();

        // Giả lập trao đổi: t0=1000, t1=1020, t2=1022, t3=1046
        // offset = ((1020-1000) + (1022-1046)) / 2 = (20 + (-24)) / 2 = -2 ms
        // delay = (1046-1000) - (1022-1020) = 46 - 2 = 44 ms
        mgr.recordSample(1000, 1020, 1022, 1046);
        assertEquals(-2L, mgr.getClockOffsetMs());
        assertEquals(44L, mgr.getClockDelayMs());
        assertFalse(mgr.isClockSkew());

        // Giả lập lệch đồng hồ > 5 giây (clock skew)
        // t0=1000, t1=7500, t2=7505, t3=1010
        // offset = ((7500-1000) + (7505-1010)) / 2 = (6500 + 6495) / 2 = 6497 ms (> 5000ms)
        mgr.recordSample(1000, 7500, 7505, 1010);
        assertEquals(6497L, mgr.getClockOffsetMs());
        assertTrue(mgr.isClockSkew(), "Độ lệch 6497 ms phải kích hoạt cờ clock_skew!");

        // Chuẩn hóa thời gian đo kẹp không vượt thời điểm nhận
        long adjusted = mgr.adjustTimestamp(1000, 7000);
        assertEquals(7000L, adjusted, "Phải kẹp không vượt serverRecvMs");
    }

    @Test
    @DisplayName("T1.4.6 - StreamTracker: Tiến trình Watermark liên tục và tiếp nhận Seq lệch thứ tự")
    void testStreamTrackerWatermarkAndOutOfOrder() {
        StreamTracker tracker = new StreamTracker(1001L);
        assertEquals(0L, tracker.getWatermark());

        // Seq 1 tới đúng thứ tự -> Watermark lên 1
        assertEquals(StreamTracker.SeqStatus.ACCEPTED_IN_ORDER, tracker.processSeq(1));
        assertEquals(1L, tracker.getWatermark());

        // Seq 3 tới trước (lệch thứ tự) -> Watermark vẫn là 1
        assertEquals(StreamTracker.SeqStatus.ACCEPTED_OUT_OF_ORDER, tracker.processSeq(3));
        assertEquals(1L, tracker.getWatermark());

        // Seq 4 tới (lệch thứ tự) -> Watermark vẫn là 1
        assertEquals(StreamTracker.SeqStatus.ACCEPTED_OUT_OF_ORDER, tracker.processSeq(4));
        assertEquals(1L, tracker.getWatermark());

        // Seq 2 tới -> lấp đầy khoảng trống -> Watermark lập tức nhảy lên 4!
        assertEquals(StreamTracker.SeqStatus.ACCEPTED_IN_ORDER, tracker.processSeq(2));
        assertEquals(4L, tracker.getWatermark(), "Watermark phải tự động kéo tiến qua các bit liên tục trong BitSet!");

        // Seq 2 tới lại (trùng lặp) -> DUPLICATE, watermark không đổi
        assertEquals(StreamTracker.SeqStatus.DUPLICATE, tracker.processSeq(2));
        assertEquals(4L, tracker.getWatermark());
    }

    @Test
    @DisplayName("T1.4.7 - StreamTracker: Khai báo DataGap kéo tiến Watermark không kẹt luồng ACK")
    void testStreamTrackerDataGapAdvancesWatermark() {
        StreamTracker tracker = new StreamTracker(2002L, 10L); // Watermark ban đầu = 10
        assertEquals(10L, tracker.getWatermark());

        // Seq 15 và 16 tới (lệch thứ tự)
        tracker.processSeq(15);
        tracker.processSeq(16);
        assertEquals(10L, tracker.getWatermark());

        // Agent khai báo gap từ 11 đến 14 do bị drop khi spool đầy (§E3.4)
        long droppedCount = tracker.applyGap(11L, 14L);
        assertEquals(4L, droppedCount);

        // Watermark phải vượt qua gap (lên 14) và tự động kéo qua 15, 16 có sẵn trong BitSet -> lên 16!
        assertEquals(16L, tracker.getWatermark(), "Watermark phải vượt qua Gap và nối liền với các seq đã tới sẵn!");
    }

    @Test
    @DisplayName("T1.4.8 - Xử lý REPORT_BATCH: Gửi lô kết quả và Gap, nhận ACK với Watermark mới")
    void testServerHandleReportBatchAndAck() throws Exception {
        server = new NcasServer(0, "127.0.0.1", "MASTER-TOKEN", 30_000L, testClock);
        server.setAllowLegacyAuth(true);
        server.start();

        long testStreamId = 888999L;

        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            RegisterRequest req = new RegisterRequest("agent-batch-test", "host", "os", "127.0.0.1", "00:00:00:00:00:00", "MASTER-TOKEN", Collections.singletonList("TCP"), "1.1.0");
            req.setProtoMax("1.1");
            req.setStreamId(testStreamId);
            FrameEncoder.writeFrame(Frame.create(MessageType.REGISTER_REQ, req), out);
            FrameDecoder.readFrame(in);

            // Gửi REPORT_BATCH chứa seq 1, seq 2, và gap 3-4
            ReportResultMessage r1 = new ReportResultMessage("agent-batch-test", 1, testClock.millis(), true, 5L, 200, null, "OK");
            r1.setStreamId(testStreamId);
            r1.setSeq(1L);

            ReportResultMessage r2 = new ReportResultMessage("agent-batch-test", 1, testClock.millis(), true, 6L, 200, null, "OK");
            r2.setStreamId(testStreamId);
            r2.setSeq(2L);

            DataGap gap = new DataGap(testStreamId, 3L, 4L, "dropped");
            ReportBatchMessage batch = new ReportBatchMessage(testStreamId, 1L, 4L, Arrays.asList(r1, r2), Collections.singletonList(gap));

            FrameEncoder.writeFrame(Frame.create(MessageType.REPORT_BATCH, batch), out);

            Frame ackFrame = readNonAssignFrame(in);
            assertNotNull(ackFrame);
            assertEquals(MessageType.ACK, ackFrame.getType());
            AckMessage ack = ackFrame.parsePayload(AckMessage.class);
            assertNotNull(ack);
            assertFalse(ack.getStreams().isEmpty());
            assertEquals(testStreamId, ack.getStreams().get(0).getStreamId());
            assertEquals(4L, ack.getStreams().get(0).getAckSeq(), "Watermark sau khi xử lý seq 1, 2 và gap 3-4 phải là 4!");
        }
    }

    private Frame readNonAssignFrame(InputStream in) throws Exception {
        Frame f = FrameDecoder.readFrame(in);
        while (f != null && f.getType() == MessageType.ASSIGN_TARGETS) {
            f = FrameDecoder.readFrame(in);
        }
        return f;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

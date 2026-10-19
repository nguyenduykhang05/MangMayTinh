package com.networkalert.protocol;

import com.networkalert.protocol.messages.HeartbeatPing;
import com.networkalert.protocol.messages.HeartbeatPong;
import com.networkalert.protocol.messages.ReportResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử Fuzzing và Giải mã phòng thủ (Defensive Decoding & Fuzz Testing)
 * cho giao thức mạng NCAS-P v1 theo đặc tả TASKS.md §T1.1 và BLUEPRINT-ERRATA §E3.8.
 * <p>
 * Bao gồm đủ 6 nhóm Fuzz bắt buộc:
 * <ol>
 *   <li>Cắt khung tại mọi byte (Byte-sliced Fuzz)</li>
 *   <li>Dán N khung liền nhau (Stream Boundary Fuzz)</li>
 *   <li>Xen khung hợp lệ với byte rác (Garbage Injection Fuzz)</li>
 *   <li>Lật một bit làm sai CRC (Bit-flip Fuzz)</li>
 *   <li>Biên kích thước Length: 0, MAX, MAX+1, 0xFFFFFFFF, trần trước xác thực 4 KiB (Boundary Length Fuzz)</li>
 *   <li>10.000 khung ngẫu nhiên khả hồi với seed cố định (Pseudo-random Fuzz)</li>
 * </ol>
 * </p>
 */
public class ProtocolFuzzTest {

    @BeforeEach
    void setUp() {
        ProtocolMetrics.reset();
        FrameDecoder.resetMaxPayloadSize();
    }

    /**
     * Tạo một Frame mẫu hợp lệ để phục vụ các bài test cắt ghép
     */
    private Frame createSampleFrame() {
        ReportResultMessage report = new ReportResultMessage(
                "agent-fuzz-01", 101, 1700000000000L, true, 28, 200, null, "Fuzz target check OK"
        );
        return Frame.create(MessageType.REPORT_RESULT, report);
    }

    // =========================================================================
    // NHÓM 1: Cắt khung tại mọi byte (Byte-sliced Fuzz)
    // =========================================================================
    @Test
    @DisplayName("Fuzz Nhóm 1: Cắt khung tại từng vị trí byte (0 -> N-1) đều bị từ chối an toàn")
    void testByteSlicedFuzz() throws Exception {
        Frame frame = createSampleFrame();
        byte[] completeFrameBytes = FrameEncoder.encodeToBytes(frame);
        int totalLen = completeFrameBytes.length;

        assertTrue(totalLen > Frame.HEADER_LENGTH + Frame.CHECKSUM_LENGTH, "Frame phải có nội dung");

        for (int cutOffset = 1; cutOffset < totalLen; cutOffset++) {
            byte[] slicedBytes = Arrays.copyOf(completeFrameBytes, cutOffset);
            ByteArrayInputStream in = new ByteArrayInputStream(slicedBytes);

            int finalCut = cutOffset;
            assertThrows(Exception.class, () -> {
                FrameDecoder.readFrame(in);
            }, "Cắt khung tại vị trí " + finalCut + "/" + totalLen + " phải ném ngoại lệ EOF/Protocol");
        }

        // Toàn bộ các lần cắt dở đều được ghi nhận metric slowloris
        assertTrue(ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_SLOWLORIS) > 0,
                "Phải ghi nhận lỗi slowloris cho các khung truyền bị cắt dở");
    }

    // =========================================================================
    // NHÓM 2: Dán N khung liền nhau (Stream Boundary Fuzz)
    // =========================================================================
    @Test
    @DisplayName("Fuzz Nhóm 2: Dán liên tiếp N khung (N=30) trong cùng một luồng dữ liệu")
    void testConcatenatedStreamFuzz() throws Exception {
        int count = 30;
        List<Frame> originalFrames = new ArrayList<>();
        ByteArrayOutputStream streamOut = new ByteArrayOutputStream();

        for (int i = 0; i < count; i++) {
            Frame f;
            if (i % 3 == 0) {
                f = Frame.create(MessageType.HEARTBEAT_PING, new HeartbeatPing("agent-" + i, 1000L + i, i, 10.5, 512));
            } else if (i % 3 == 1) {
                f = Frame.create(MessageType.HEARTBEAT_PONG, HeartbeatPong.ok());
            } else {
                f = Frame.create(MessageType.REPORT_RESULT,
                        new ReportResultMessage("agent-" + i, i + 1, 2000L, true, 15, 200, null, "Check #" + i));
            }
            originalFrames.add(f);
            FrameEncoder.encodeToStream(f, streamOut);
        }

        byte[] allStreamBytes = streamOut.toByteArray();
        List<Frame> decodedFrames = FrameDecoder.decodeAll(allStreamBytes);

        assertEquals(count, decodedFrames.size(), "Số lượng khung giải mã phải chính xác bằng N");

        for (int i = 0; i < count; i++) {
            Frame orig = originalFrames.get(i);
            Frame dec = decodedFrames.get(i);
            assertEquals(orig.getType(), dec.getType(), "Loại thông điệp tại index " + i + " phải khớp");
            assertArrayEquals(orig.getPayload(), dec.getPayload(), "Payload tại index " + i + " phải khớp từng byte");
            assertEquals(orig.getChecksum(), dec.getChecksum(), "Checksum tại index " + i + " phải khớp");
        }
    }

    // =========================================================================
    // NHÓM 3: Xen khung hợp lệ với byte rác (Garbage Injection Fuzz)
    // =========================================================================
    @Test
    @DisplayName("Fuzz Nhóm 3: Xen kẽ byte rác trước, giữa và sau khung hợp lệ")
    void testGarbageInjectionFuzz() throws Exception {
        Frame frame = createSampleFrame();
        byte[] validBytes = FrameEncoder.encodeToBytes(frame);

        // 3a. Rác chèn trước khung hợp lệ (Sai Magic)
        byte[] garbagePrefix = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        out1.write(garbagePrefix);
        out1.write(validBytes);

        ProtocolException pe1 = assertThrows(ProtocolException.class, () -> {
            FrameDecoder.readFrame(new ByteArrayInputStream(out1.toByteArray()));
        });
        assertEquals(ProtocolMetrics.REASON_MAGIC, pe1.getReason());
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_MAGIC));

        // 3b. Khung hợp lệ thứ 1 xong dính rác rồi mới tới khung thứ 2
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        out2.write(validBytes);
        out2.write(new byte[]{0x00, 0x11, 0x22}); // Rác giữa 2 khung
        out2.write(validBytes);

        ByteArrayInputStream in2 = new ByteArrayInputStream(out2.toByteArray());
        Frame f1 = FrameDecoder.readFrame(in2);
        assertNotNull(f1, "Khung đầu tiên hợp lệ phải đọc thành công");
        assertEquals(frame.getType(), f1.getType());

        // Lần đọc kế tiếp chạm vào byte rác -> Phải ném ngoại lệ ngay
        assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(in2));

        // 3c. Phiên bản version dị dạng (0x99)
        byte[] corruptVersion = validBytes.clone();
        corruptVersion[4] = (byte) 0x99;
        ProtocolException peVer = assertThrows(ProtocolException.class, () -> {
            FrameDecoder.readFrame(new ByteArrayInputStream(corruptVersion));
        });
        assertEquals(ProtocolMetrics.REASON_VERSION, peVer.getReason());
    }

    // =========================================================================
    // NHÓM 4: Lật một bit làm sai CRC32 (Bit-flip Fuzz)
    // =========================================================================
    @Test
    @DisplayName("Fuzz Nhóm 4: Lật từng bit trong Payload và Checksum (100% làm sai CRC32)")
    void testBitFlipFuzz() throws Exception {
        Frame frame = createSampleFrame();
        byte[] baseBytes = FrameEncoder.encodeToBytes(frame);

        int payloadStart = Frame.HEADER_LENGTH;
        int totalBytes = baseBytes.length;
        int totalTests = (totalBytes - payloadStart) * 8;
        int rejectedCount = 0;

        for (int byteIdx = payloadStart; byteIdx < totalBytes; byteIdx++) {
            for (int bitIdx = 0; bitIdx < 8; bitIdx++) {
                byte[] mutated = baseBytes.clone();
                mutated[byteIdx] ^= (byte) (1 << bitIdx);

                try {
                    FrameDecoder.readFrame(new ByteArrayInputStream(mutated));
                    fail("Khung tin bị lật bit tại byte " + byteIdx + " bit " + bitIdx + " không được phép giải mã thành công!");
                } catch (ProtocolException expected) {
                    assertEquals(ProtocolMetrics.REASON_CRC, expected.getReason(),
                            "Lật bit trong payload/crc phải báo lỗi sai CRC");
                    rejectedCount++;
                } catch (EOFException expected) {
                    rejectedCount++;
                }
            }
        }

        assertEquals(totalTests, rejectedCount, "100% các đột biến lật bit trong payload/crc đều bị từ chối do sai CRC!");
        assertEquals(totalTests, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_CRC));
    }

    @Test
    @DisplayName("Fuzz Nhóm 4b: Lật từng bit trong Magic và Version (100% bị từ chối an toàn)")
    void testBitFlipHeaderFuzz() throws Exception {
        Frame frame = createSampleFrame();
        byte[] baseBytes = FrameEncoder.encodeToBytes(frame);

        // Magic bytes (0..3)
        for (int byteIdx = 0; byteIdx < 4; byteIdx++) {
            for (int bitIdx = 0; bitIdx < 8; bitIdx++) {
                byte[] mutated = baseBytes.clone();
                mutated[byteIdx] ^= (byte) (1 << bitIdx);
                ProtocolException pe = assertThrows(ProtocolException.class, () -> {
                    FrameDecoder.readFrame(new ByteArrayInputStream(mutated));
                });
                assertEquals(ProtocolMetrics.REASON_MAGIC, pe.getReason());
            }
        }

        // Version byte (4)
        for (int bitIdx = 0; bitIdx < 8; bitIdx++) {
            byte[] mutated = baseBytes.clone();
            mutated[4] ^= (byte) (1 << bitIdx);
            if (mutated[4] != Frame.VERSION && mutated[4] != 0x02) {
                ProtocolException pe = assertThrows(ProtocolException.class, () -> {
                    FrameDecoder.readFrame(new ByteArrayInputStream(mutated));
                });
                assertEquals(ProtocolMetrics.REASON_VERSION, pe.getReason());
            }
        }
    }

    // =========================================================================
    // NHÓM 5: Biên kích thước Length (Boundary Length Fuzz)
    // =========================================================================
    @Test
    @DisplayName("Fuzz Nhóm 5: Biên kích thước Length (0, MAX, MAX+1, 0xFFFFFFFF, trần 4 KiB)")
    void testBoundaryLengthFuzz() throws Exception {
        // 5.1: Length = 0 (Payload rỗng) -> Hợp lệ
        Frame emptyFrame = Frame.empty(MessageType.HEARTBEAT_PONG);
        byte[] emptyBytes = FrameEncoder.encodeToBytes(emptyFrame);
        Frame decodedEmpty = FrameDecoder.readFrame(new ByteArrayInputStream(emptyBytes));
        assertNotNull(decodedEmpty);
        assertEquals(0, decodedEmpty.getPayloadLength());
        assertEquals(0, decodedEmpty.getChecksum());

        // 5.2: Length = MAX_PAYLOAD (1 MiB = 1,048,576 bytes) -> Hợp lệ
        int maxPayload = FrameDecoder.DEFAULT_MAX_PAYLOAD_SIZE;
        byte[] maxBody = new byte[maxPayload];
        Arrays.fill(maxBody, (byte) 'A');
        Frame maxFrame = new Frame(MessageType.REPORT_RESULT, maxBody);
        byte[] maxBytes = FrameEncoder.encodeToBytes(maxFrame);
        Frame decodedMax = FrameDecoder.readFrame(new ByteArrayInputStream(maxBytes));
        assertNotNull(decodedMax);
        assertEquals(maxPayload, decodedMax.getPayloadLength());

        // 5.3: Length = MAX_PAYLOAD + 1 (1,048,577 bytes) -> Bị chặn oversize TRƯỚC KHI cấp phát
        byte[] overHeader = new byte[]{
                'N', 'C', 'A', 'S', // Magic (4B)
                0x01,                // Version (1B)
                0x00, 0x01,          // Type = REGISTER_REQ (2B)
                0x00, 0x10, 0x00, 0x01 // Length = 1,048,577 bytes (4B Big-Endian)
        };
        ProtocolException peOver = assertThrows(ProtocolException.class, () -> {
            FrameDecoder.readFrame(new ByteArrayInputStream(overHeader));
        });
        assertEquals(ProtocolMetrics.REASON_OVERSIZE, peOver.getReason());
        assertTrue(peOver.getMessage().contains("1048577"));

        // 5.4: Length = 0xFFFFFFFF (4,294,967,295 unsigned / -1 signed) -> Bị chặn ngay, không OOM
        byte[] maxUintHeader = new byte[]{
                'N', 'C', 'A', 'S',
                0x01,
                0x00, 0x01,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        ProtocolException peUint = assertThrows(ProtocolException.class, () -> {
            FrameDecoder.readFrame(new ByteArrayInputStream(maxUintHeader));
        });
        assertEquals(ProtocolMetrics.REASON_OVERSIZE, peUint.getReason());

        // 5.5: Giới hạn trước xác thực 4 KiB (PRE_AUTH_MAX_PAYLOAD_SIZE = 4096 bytes)
        byte[] preAuthOverHeader = new byte[]{
                'N', 'C', 'A', 'S',
                0x01,
                0x00, 0x01,
                0x00, 0x00, 0x10, 0x01 // Length = 4097 bytes (vượt 4096)
        };
        ProtocolException pePreAuth = assertThrows(ProtocolException.class, () -> {
            FrameDecoder.readFrame(new ByteArrayInputStream(preAuthOverHeader), FrameDecoder.PRE_AUTH_MAX_PAYLOAD_SIZE);
        });
        assertEquals(ProtocolMetrics.REASON_OVERSIZE, pePreAuth.getReason());

        // Length = 4096 đúng trần trước xác thực -> Không bị chặn ở bước kiểm tra độ dài
        byte[] preAuthExactBody = new byte[4096];
        Arrays.fill(preAuthExactBody, (byte) 'P');
        Frame exactPreAuth = new Frame(MessageType.REGISTER_REQ, preAuthExactBody);
        byte[] exactPreAuthBytes = FrameEncoder.encodeToBytes(exactPreAuth);
        Frame decodedExact = FrameDecoder.readFrame(new ByteArrayInputStream(exactPreAuthBytes), FrameDecoder.PRE_AUTH_MAX_PAYLOAD_SIZE);
        assertNotNull(decodedExact);
        assertEquals(4096, decodedExact.getPayloadLength());
    }

    // =========================================================================
    // NHÓM 6: 10.000 khung ngẫu nhiên khả hồi với seed cố định (Pseudo-random Fuzz)
    // =========================================================================
    @Test
    @DisplayName("Fuzz Nhóm 6: 10.000 khung byte ngẫu nhiên (seed cố định 42) không làm sập JVM")
    void testPseudoRandomFuzz10k() {
        Random rng = new Random(42); // Seed cố định đảm bảo tính tất định khả hồi
        int iterations = 10_000;
        int caughtExceptions = 0;

        for (int i = 0; i < iterations; i++) {
            // Độ dài ngẫu nhiên từ 0 đến 1024 bytes (bao gồm cả trường hợp gói tin siêu ngắn)
            int len = rng.nextInt(1025);
            byte[] fuzzData = new byte[len];
            rng.nextBytes(fuzzData);

            ByteArrayInputStream in = new ByteArrayInputStream(fuzzData);
            try {
                Frame f = FrameDecoder.readFrame(in);
                // Nếu tình cờ sinh ra đúng Magic + Version + Checksum hợp lệ thì chấp nhận
                if (f != null) {
                    assertNotNull(f.getType());
                }
            } catch (ProtocolException | IOException expected) {
                // Toàn bộ ngoại lệ giao thức hoặc EOF là hành vi phòng thủ mong muốn
                caughtExceptions++;
            } catch (Throwable unexpected) {
                fail("Phát hiện ngoại lệ không mong muốn tại vòng lặp #" + i + ": " + unexpected.getClass().getName() + " - " + unexpected.getMessage());
            }
        }

        assertTrue(caughtExceptions > 9900, "Hầu hết các mảng byte ngẫu nhiên phải bị phòng thủ chặn lại an toàn");
    }

    // =========================================================================
    // KIỂM THỬ CHỈ SỐ LỖI GIAO THỨC (Protocol Metrics)
    // =========================================================================
    @Test
    @DisplayName("Kiểm thử ProtocolMetrics: Ghi nhận đầy đủ 6 loại lý do lỗi chuẩn Prometheus")
    void testProtocolMetricsReasons() {
        ProtocolMetrics.recordError(ProtocolMetrics.REASON_OVERSIZE);
        ProtocolMetrics.recordError(ProtocolMetrics.REASON_MAGIC);
        ProtocolMetrics.recordError(ProtocolMetrics.REASON_VERSION);
        ProtocolMetrics.recordError(ProtocolMetrics.REASON_CRC);
        ProtocolMetrics.recordError(ProtocolMetrics.REASON_SLOWLORIS);
        ProtocolMetrics.recordError(ProtocolMetrics.REASON_IDLE);

        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_OVERSIZE));
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_MAGIC));
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_VERSION));
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_CRC));
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_SLOWLORIS));
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_IDLE));
        assertEquals(6, ProtocolMetrics.getTotalErrors());

        ProtocolMetrics.reset();
        assertEquals(0, ProtocolMetrics.getTotalErrors());
    }

    @Test
    @DisplayName("Kiểm thử Slowloris Timeout (khung dở > 10s) và Idle Timeout (> 3 chu kỳ nhịp tim)")
    void testSlowlorisAndIdleTimeouts() {
        // 1. Idle timeout: Stream ném SocketTimeoutException ngay tại byte đầu tiên
        java.io.InputStream idleStream = new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                throw new SocketTimeoutException("Read timed out (Idle)");
            }
        };

        assertThrows(SocketTimeoutException.class, () -> FrameDecoder.readFrame(idleStream));
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_IDLE));

        // 2. Slowloris timeout: Đã gửi Magic 'N', 'C', 'A', 'S' rồi bị nghẽn (timeout) khi đọc dở
        byte[] magicPrefix = Frame.MAGIC;
        java.io.InputStream slowlorisStream = new java.io.InputStream() {
            private int pos = 0;
            @Override
            public int read() throws IOException {
                if (pos < magicPrefix.length) {
                    return magicPrefix[pos++];
                }
                throw new SocketTimeoutException("Read timed out (Slowloris mid-frame)");
            }
        };

        assertThrows(SocketTimeoutException.class, () -> FrameDecoder.readFrame(slowlorisStream));
        assertEquals(1, ProtocolMetrics.getErrorCount(ProtocolMetrics.REASON_SLOWLORIS));
    }

    @Test
    @DisplayName("Fuzz NCAS-P v1.1: Khứ hồi và đột biến byte ngẫu nhiên cho ACK và REPORT_BATCH")
    void testFuzzV11TypesRoundtripAndMutation() throws Exception {
        // 1. Khứ hồi ACK
        com.networkalert.protocol.messages.AckMessage ack = new com.networkalert.protocol.messages.AckMessage(
                java.util.Collections.singletonList(new com.networkalert.protocol.messages.StreamAck(12345L, 6789L)),
                1L, 1700000000000L, 1700000000005L);
        Frame ackFrame = Frame.create(MessageType.ACK, ack);
        byte[] ackBytes = FrameEncoder.encodeToBytes(ackFrame);
        Frame decodedAck = FrameDecoder.readFrame(new ByteArrayInputStream(ackBytes));
        assertNotNull(decodedAck);
        assertEquals(MessageType.ACK, decodedAck.getMessageType());

        // 2. Khứ hồi REPORT_BATCH
        com.networkalert.protocol.messages.ReportBatchMessage batch = new com.networkalert.protocol.messages.ReportBatchMessage(
                12345L, 1L, 2L,
                java.util.Collections.emptyList(),
                java.util.Collections.singletonList(new com.networkalert.protocol.messages.DataGap(12345L, 1L, 2L, "dropped")));
        Frame batchFrame = Frame.create(MessageType.REPORT_BATCH, batch);
        byte[] batchBytes = FrameEncoder.encodeToBytes(batchFrame);
        Frame decodedBatch = FrameDecoder.readFrame(new ByteArrayInputStream(batchBytes));
        assertNotNull(decodedBatch);
        assertEquals(MessageType.REPORT_BATCH, decodedBatch.getMessageType());

        // 3. Đột biến bit làm sai CRC trên khung v1.1
        Random rng = new Random(42);
        for (byte[] b : Arrays.asList(ackBytes, batchBytes)) {
            byte[] corrupted = Arrays.copyOf(b, b.length);
            int flipPos = rng.nextInt(corrupted.length);
            corrupted[flipPos] ^= 0x01;
            assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(new ByteArrayInputStream(corrupted)));
        }
    }
}

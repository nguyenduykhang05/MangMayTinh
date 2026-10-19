package com.networkalert.protocol;

import com.networkalert.protocol.messages.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử toàn diện cho Giao thức mạng phân tán NCAS-P v1:
 * - Mã hóa & Giải mã nhị phân chuẩn Big-Endian
 * - Xử lý dính gói TCP (Packet Sticking)
 * - Xử lý xé gói TCP (Packet Fragmentation)
 * - Kiểm soát toàn vẹn CRC32 Checksum & phát hiện dữ liệu giả mạo
 * - Đồng bộ Magic Bytes & tương thích phiên bản
 */
public class ProtocolFrameTest {

    @Test
    @DisplayName("Kiểm thử Round-trip Encode / Decode Frame nhị phân cơ bản")
    void testNormalFrameEncodeDecodeRoundtrip() throws Exception {
        String json = "{\"agentId\":\"agent-01\",\"cpu\":15.5}";
        Frame original = Frame.ofJson(MessageType.HEARTBEAT_PING, json);

        byte[] encoded = FrameEncoder.encode(original);
        assertNotNull(encoded);
        // Header (11) + Payload (json bytes) + Checksum (4)
        assertEquals(Frame.HEADER_LENGTH + json.getBytes(StandardCharsets.UTF_8).length + Frame.CHECKSUM_LENGTH, encoded.length);

        // Giải mã
        ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
        Frame decoded = FrameDecoder.readFrame(bais);

        assertNotNull(decoded);
        assertEquals(MessageType.HEARTBEAT_PING, decoded.getMessageType());
        assertEquals(json, decoded.getPayloadAsString());
        assertEquals(original.getChecksum(), decoded.getChecksum());
    }

    @Test
    @DisplayName("Kiểm thử Frame rỗng (Payload 0 byte)")
    void testEmptyPayloadRoundtrip() throws Exception {
        Frame original = Frame.empty(MessageType.HEARTBEAT_PONG);
        byte[] encoded = FrameEncoder.encode(original);

        assertEquals(Frame.HEADER_LENGTH + Frame.CHECKSUM_LENGTH, encoded.length);

        ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
        Frame decoded = FrameDecoder.readFrame(bais);

        assertNotNull(decoded);
        assertEquals(MessageType.HEARTBEAT_PONG, decoded.getMessageType());
        assertEquals(0, decoded.getPayloadLength());
        assertEquals(0L, decoded.getChecksum());
    }

    @Test
    @DisplayName("Kiểm thử Tuần tự hóa đối tượng DTO phức tạp qua JSON trong Frame")
    void testJsonDtoRoundtrip() throws Exception {
        // 1. RegisterRequest
        RegisterRequest regReq = new RegisterRequest(
                "agent-hanoi-01", "DESKTOP-HN", "Windows 11", "192.168.1.50",
                "00:11:22:33:44:55", "secret-token-123", Arrays.asList("TCP", "HTTP", "DNS"), "1.0.0"
        );
        Frame regFrame = Frame.ofObject(MessageType.REGISTER_REQ, regReq);
        byte[] regBytes = FrameEncoder.encode(regFrame);

        Frame decodedRegFrame = FrameDecoder.readFrame(new ByteArrayInputStream(regBytes));
        assertNotNull(decodedRegFrame);
        RegisterRequest decodedReq = decodedRegFrame.getPayloadAs(RegisterRequest.class);
        assertEquals("agent-hanoi-01", decodedReq.getAgentId());
        assertEquals("DESKTOP-HN", decodedReq.getHostname());
        assertEquals(3, decodedReq.getSupportedProtocols().size());

        // 2. AssignTargetsMessage
        List<TargetDto> targets = new ArrayList<>();
        targets.add(new TargetDto(1, "Web App", "127.0.0.1", 8080, "HTTP", 5, 1500, 300, true));
        targets.add(new TargetDto(2, "DB Gateway", "192.168.1.1", 1433, "TCP", 3, 1000, 150, true));
        AssignTargetsMessage assignMsg = new AssignTargetsMessage(targets);

        Frame assignFrame = Frame.ofObject(MessageType.ASSIGN_TARGETS, assignMsg);
        byte[] assignBytes = FrameEncoder.encode(assignFrame);

        Frame decodedAssignFrame = FrameDecoder.readFrame(new ByteArrayInputStream(assignBytes));
        assertNotNull(decodedAssignFrame);
        AssignTargetsMessage decodedAssign = decodedAssignFrame.getPayloadAs(AssignTargetsMessage.class);
        assertEquals(2, decodedAssign.getTargets().size());
        assertEquals("Web App", decodedAssign.getTargets().get(0).getName());
        assertEquals(1433, decodedAssign.getTargets().get(1).getPort());
    }

    @Test
    @DisplayName("Kiểm thử Xử lý dính gói TCP (Packet Sticking / Multiple frames in one byte array)")
    void testPacketStickingSimulation() throws Exception {
        // Giả lập 3 Frame liên tiếp bị TCP Socket ghép chung vào 1 mảng byte duy nhất
        Frame f1 = Frame.ofJson(MessageType.REGISTER_REQ, "{\"agentId\":\"agent-1\"}");
        Frame f2 = Frame.ofJson(MessageType.ASSIGN_TARGETS, "{\"targets\":[{\"id\":1}]}");
        Frame f3 = Frame.ofJson(MessageType.HEARTBEAT_PING, "{\"time\":1000}");

        ByteArrayOutputStream combinedStream = new ByteArrayOutputStream();
        FrameEncoder.writeFrame(f1, combinedStream);
        FrameEncoder.writeFrame(f2, combinedStream);
        FrameEncoder.writeFrame(f3, combinedStream);

        byte[] stuckBytes = combinedStream.toByteArray();
        assertTrue(stuckBytes.length > Frame.HEADER_LENGTH * 3);

        // Giải mã stream
        List<Frame> decodedList = FrameDecoder.decodeAll(stuckBytes);
        assertEquals(3, decodedList.size(), "Phải bóc tách chính xác 3 frames từ luồng byte bị dính gói");

        assertEquals(MessageType.REGISTER_REQ, decodedList.get(0).getMessageType());
        assertEquals(MessageType.ASSIGN_TARGETS, decodedList.get(1).getMessageType());
        assertEquals(MessageType.HEARTBEAT_PING, decodedList.get(2).getMessageType());

        assertEquals("{\"agentId\":\"agent-1\"}", decodedList.get(0).getPayloadAsString());
        assertEquals("{\"targets\":[{\"id\":1}]}", decodedList.get(1).getPayloadAsString());
        assertEquals("{\"time\":1000}", decodedList.get(2).getPayloadAsString());
    }

    @Test
    @DisplayName("Kiểm thử Xử lý xé gói TCP (Packet Fragmentation / Chunks as small as 1 byte)")
    void testPacketFragmentationSimulation() throws Exception {
        // Tạo Frame có kích thước lớn
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"key_").append(i).append("\":\"value_data_chunk_").append(i).append("\"");
        }
        sb.append("}");
        String largeJson = sb.toString();

        Frame original = Frame.ofJson(MessageType.REPORT_RESULT, largeJson);
        byte[] encoded = FrameEncoder.encode(original);

        // Luồng FragmentedInputStream: mỗi lần gọi in.read(b, off, len) chỉ trả về tối đa 1 byte!
        InputStream fragmentedStream = new InputStream() {
            private int index = 0;

            @Override
            public int read() {
                if (index >= encoded.length) return -1;
                return encoded[index++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (index >= encoded.length) return -1;
                // Cố tình chỉ đọc đúng 1 byte mỗi lần để ép thuật toán readFully phải lặp
                b[off] = encoded[index++];
                return 1;
            }
        };

        Frame decoded = FrameDecoder.readFrame(fragmentedStream);
        assertNotNull(decoded, "FrameDecoder phải giải mã thành công bất chấp gói bị xé nhỏ 1 byte/lần");
        assertEquals(MessageType.REPORT_RESULT, decoded.getMessageType());
        assertEquals(largeJson, decoded.getPayloadAsString());
        assertEquals(original.getChecksum(), decoded.getChecksum());
    }

    @Test
    @DisplayName("Kiểm thử Phát hiện Magic Bytes không hợp lệ (Dữ liệu rác hoặc port scan)")
    void testInvalidMagicBytes() {
        byte[] badData = new byte[]{
                'H', 'T', 'T', 'P', // Magic sai ("HTTP")
                0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };

        ByteArrayInputStream bais = new ByteArrayInputStream(badData);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(bais));
        assertTrue(ex.getMessage().contains("Magic Bytes không hợp lệ"), "Phải ném ngoại lệ khi Magic Bytes không khớp NCAS");
    }

    @Test
    @DisplayName("Kiểm thử Không tương thích phiên bản giao thức (Version mismatch)")
    void testVersionMismatch() {
        byte[] badVersionData = new byte[]{
                'N', 'C', 'A', 'S',
                0x09, // Version 9 (không hỗ trợ)
                0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };

        ByteArrayInputStream bais = new ByteArrayInputStream(badVersionData);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(bais));
        assertTrue(ex.getMessage().contains("Không tương thích phiên bản"), "Phải từ chối phiên bản giao thức lạ");
    }

    @Test
    @DisplayName("Kiểm thử Kiểm tra toàn vẹn CRC32 Checksum (Phát hiện Payload bị lỗi/giả mạo)")
    void testCrc32Mismatch() {
        Frame original = Frame.ofJson(MessageType.HEARTBEAT_PING, "{\"status\":\"healthy\"}");
        byte[] encoded = FrameEncoder.encode(original);

        // Làm sai lệch 1 byte trong payload (ở vị trí index 15)
        encoded[15] ^= 0xFF;

        ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(bais));
        assertTrue(ex.getMessage().contains("CRC32 Checksum Mismatch"), "Phải phát hiện lỗi toàn vẹn khi payload bị sửa đổi");
    }

    @Test
    @DisplayName("Kiểm thử Payload vượt quá giới hạn tối đa 16MB")
    void testMaxPayloadExceeded() {
        // Gói tin có trường Length khai báo 20 MB (lớn hơn 16 MB)
        byte[] headerWithTooLargeLength = new byte[]{
                'N', 'C', 'A', 'S',
                0x01,
                0x00, 0x01, // Type
                0x01, 0x40, 0x00, 0x00 // Length = 20,971,520 bytes (~20MB)
        };

        ByteArrayInputStream bais = new ByteArrayInputStream(headerWithTooLargeLength);
        ProtocolException ex = assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(bais));
        assertTrue(ex.getMessage().contains("Kích thước Payload không hợp lệ"), "Phải chặn tấn công làm tràn bộ nhớ bằng header kích thước khổng lồ");
    }

    @Test
    @DisplayName("Kiểm thử Ngắt kết nối bình thường (EOF sạch) trả về null")
    void testCleanEofReturnsNull() throws Exception {
        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        Frame frame = FrameDecoder.readFrame(emptyStream);
        assertNull(frame, "Đọc stream rỗng ở đầu frame phải trả về null đại diện cho ngắt kết nối an toàn");
    }

    @Test
    @DisplayName("Kiểm thử Dòng dữ liệu bị cắt ngắn giữa chừng (Truncated stream)")
    void testTruncatedStreamThrowsException() {
        // Chỉ có 3 bytes magic rồi đứt kết nối
        byte[] truncated = new byte[]{'N', 'C', 'A'};
        ByteArrayInputStream bais = new ByteArrayInputStream(truncated);

        assertThrows(EOFException.class, () -> FrameDecoder.readFrame(bais));
    }
}

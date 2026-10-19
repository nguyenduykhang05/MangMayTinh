package com.networkalert.protocol;

import com.google.gson.JsonObject;
import com.networkalert.protocol.messages.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử đóng băng đặc tả giao thức NCAS-P v1 (Task T0.2).
 * <p>
 * Đảm bảo:
 * 1. Toàn bộ 13 MessageType được mã hóa và giải mã chuẩn xác theo từng byte (Byte-for-byte).
 * 2. Đối chiếu với các vector mẫu hex bất biến tại src/test/resources/protocol/golden/.
 * 3. Xác thực hành vi của Decoder v1 đối với các trường JSON lạ (bỏ qua an toàn).
 */
public class ProtocolGoldenTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/protocol/golden");

    @BeforeAll
    static void initGoldenVectors() throws Exception {
        if (!Files.exists(GOLDEN_DIR)) {
            Files.createDirectories(GOLDEN_DIR);
        }
        generateGoldenFilesIfMissing();
    }

    private static void generateGoldenFilesIfMissing() throws IOException {
        Map<String, Frame> canonicalFrames = buildCanonicalFrames();
        for (Map.Entry<String, Frame> entry : canonicalFrames.entrySet()) {
            Path file = GOLDEN_DIR.resolve(entry.getKey() + ".hex");
            if (!Files.exists(file)) {
                byte[] encoded = FrameEncoder.encode(entry.getValue());
                String hex = bytesToHex(encoded);
                Files.writeString(file, hex, StandardCharsets.UTF_8);
            }
        }
    }

    public static Map<String, Frame> buildCanonicalFrames() {
        Map<String, Frame> frames = new LinkedHashMap<>();

        // 1. REGISTER_REQ (0x0001)
        RegisterRequest regReq = new RegisterRequest(
                "agent-hn-01",
                "DESKTOP-HN01",
                "Windows 11 Pro 64-bit",
                "192.168.1.50",
                "00:11:22:33:44:55",
                "test-token-secret-123",
                Arrays.asList("TCP", "HTTP", "DNS", "ICMP"),
                "1.0.0"
        );
        frames.put("01_register_req", Frame.ofObject(MessageType.REGISTER_REQ, regReq));

        // 2. REGISTER_RESP (0x0002)
        RegisterResponse regResp = new RegisterResponse(
                "ACCEPTED",
                "sess-agent-001-xyz",
                15,
                1700000000000L,
                "Agent enrolled successfully",
                "dedicated-secret-key-456"
        );
        frames.put("02_register_resp", Frame.ofObject(MessageType.REGISTER_RESP, regResp));

        // 3. HEARTBEAT_PING (0x0003)
        HeartbeatPing ping = new HeartbeatPing(
                "agent-hn-01",
                1700000015000L,
                4,
                12.5,
                512L
        );
        frames.put("03_heartbeat_ping", Frame.ofObject(MessageType.HEARTBEAT_PING, ping));

        // 4. HEARTBEAT_PONG (0x0004)
        HeartbeatPong pong = new HeartbeatPong(1700000015050L, "OK");
        frames.put("04_heartbeat_pong", Frame.ofObject(MessageType.HEARTBEAT_PONG, pong));

        // 5. ASSIGN_TARGETS (0x0005)
        List<TargetDto> targets = Arrays.asList(
                new TargetDto(1, "Web Gateway", "192.168.1.1", 80, "HTTP", 5, 1000, 200, true, "AGENT", "Infrastructure", "gateway,lan"),
                new TargetDto(2, "DNS Google", "8.8.8.8", 53, "DNS", 10, 1500, 300, true, "MULTI", "Internet", "dns,public")
        );
        AssignTargetsMessage assign = new AssignTargetsMessage(targets);
        assign.setTimestamp(1700000000000L);
        frames.put("05_assign_targets", Frame.ofObject(MessageType.ASSIGN_TARGETS, assign));

        // 6. REPORT_RESULT (0x0006)
        MetricBreakdownDto metrics = new MetricBreakdownDto(10, 8, 0, 10);
        ReportResultMessage report = new ReportResultMessage("agent-hn-01", 1, 1700000020000L, true, 28, 200, metrics, "HTTP 200 OK");
        frames.put("06_report_result", Frame.ofObject(MessageType.REPORT_RESULT, report));

        // 7. FORCE_CHECK_REQ (0x0007)
        TargetDto target = new TargetDto(1, "Web Gateway", "192.168.1.1", 80, "HTTP", 5, 1000, 200, true, "AGENT", "Infrastructure", "gateway,lan");
        ForceCheckRequest forceReq = new ForceCheckRequest(1, target);
        forceReq.setRequestedAt(1700000030000L);
        frames.put("07_force_check_req", Frame.ofObject(MessageType.FORCE_CHECK_REQ, forceReq));

        // 8. FORCE_CHECK_RESP (0x0008)
        MetricBreakdownDto fcMetrics = new MetricBreakdownDto(8, 7, 0, 10);
        ReportResultMessage fcResult = new ReportResultMessage("agent-hn-01", 1, 1700000030050L, true, 25, 200, fcMetrics, "Force check HTTP 200 OK");
        ForceCheckResponse fcResp = new ForceCheckResponse(1, fcResult);
        frames.put("08_force_check_resp", Frame.ofObject(MessageType.FORCE_CHECK_RESP, fcResp));

        // 9. CONSOLE_SUBSCRIBE (0x0009)
        frames.put("09_console_subscribe", Frame.empty(MessageType.CONSOLE_SUBSCRIBE));

        // 10. CONSOLE_EVENT_STREAM (0x000A)
        String streamJson = "{\"eventType\":\"STATE_CHANGE\",\"targetId\":1,\"targetName\":\"Web Gateway\",\"oldStatus\":\"ONLINE\",\"newStatus\":\"OFFLINE\",\"timestamp\":1700000040000,\"message\":\"Target unreachable\"}";
        frames.put("0a_console_event_stream", Frame.ofJson(MessageType.CONSOLE_EVENT_STREAM, streamJson));

        // 11. CONSOLE_COMMAND (0x000B)
        Map<String, String> cmdParams = new LinkedHashMap<>();
        cmdParams.put("filter", "active");
        cmdParams.put("limit", "50");
        ConsoleCommand cmd = new ConsoleCommand("list-agents", cmdParams);
        cmd.setTimestamp(1700000050000L);
        frames.put("0b_console_command", Frame.ofObject(MessageType.CONSOLE_COMMAND, cmd));

        // 12. CONSOLE_COMMAND_RESP (0x000C)
        ConsoleResponse cmdResp = new ConsoleResponse(true, "Found 1 active agent", "[{\"agentId\":\"agent-hn-01\",\"status\":\"ONLINE\"}]", 12);
        frames.put("0c_console_command_resp", Frame.ofObject(MessageType.CONSOLE_COMMAND_RESP, cmdResp));

        // 13. DISCONNECT (0x00FF)
        DisconnectMessage disc = new DisconnectMessage("SHUTDOWN", "Server graceful shutdown initiated");
        disc.setTimestamp(1700000060000L);
        frames.put("ff_disconnect", Frame.ofObject(MessageType.DISCONNECT, disc));

        return frames;
    }

    @Test
    @DisplayName("Kiểm thử giải mã thành công toàn bộ 13 Golden Vectors")
    void testDecodeAll13GoldenVectors() throws Exception {
        Map<String, MessageType> expectedTypes = Map.ofEntries(
                Map.entry("01_register_req", MessageType.REGISTER_REQ),
                Map.entry("02_register_resp", MessageType.REGISTER_RESP),
                Map.entry("03_heartbeat_ping", MessageType.HEARTBEAT_PING),
                Map.entry("04_heartbeat_pong", MessageType.HEARTBEAT_PONG),
                Map.entry("05_assign_targets", MessageType.ASSIGN_TARGETS),
                Map.entry("06_report_result", MessageType.REPORT_RESULT),
                Map.entry("07_force_check_req", MessageType.FORCE_CHECK_REQ),
                Map.entry("08_force_check_resp", MessageType.FORCE_CHECK_RESP),
                Map.entry("09_console_subscribe", MessageType.CONSOLE_SUBSCRIBE),
                Map.entry("0a_console_event_stream", MessageType.CONSOLE_EVENT_STREAM),
                Map.entry("0b_console_command", MessageType.CONSOLE_COMMAND),
                Map.entry("0c_console_command_resp", MessageType.CONSOLE_COMMAND_RESP),
                Map.entry("ff_disconnect", MessageType.DISCONNECT)
        );

        for (Map.Entry<String, MessageType> entry : expectedTypes.entrySet()) {
            String name = entry.getKey();
            MessageType expectedType = entry.getValue();

            byte[] bytes = loadGoldenVectorBytes(name);
            assertNotNull(bytes, "Không tải được vector: " + name);

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            Frame frame = FrameDecoder.readFrame(bais);

            assertNotNull(frame, "Giải mã trả về null cho vector: " + name);
            assertEquals(expectedType, frame.getMessageType(), "Sai MessageType cho " + name);
            assertEquals(bytes.length, Frame.HEADER_LENGTH + frame.getPayloadLength() + Frame.CHECKSUM_LENGTH,
                    "Tổng độ dài byte không khớp cho " + name);
            assertEquals(Frame.calculateCrc32(frame.getPayload()), frame.getChecksum(),
                    "CRC32 không khớp cho " + name);

            // Kiểm tra deserialize DTO cụ thể
            verifyDtoDeserialization(name, frame);
        }
    }

    private void verifyDtoDeserialization(String name, Frame frame) {
        switch (name) {
            case "01_register_req": {
                RegisterRequest req = frame.getPayloadAs(RegisterRequest.class);
                assertEquals("agent-hn-01", req.getAgentId());
                assertEquals("DESKTOP-HN01", req.getHostname());
                assertEquals(4, req.getSupportedProtocols().size());
                break;
            }
            case "02_register_resp": {
                RegisterResponse resp = frame.getPayloadAs(RegisterResponse.class);
                assertTrue(resp.isAccepted());
                assertEquals("sess-agent-001-xyz", resp.getAssignedSessionId());
                assertEquals(15, resp.getHeartbeatIntervalSeconds());
                break;
            }
            case "03_heartbeat_ping": {
                HeartbeatPing ping = frame.getPayloadAs(HeartbeatPing.class);
                assertEquals("agent-hn-01", ping.getAgentId());
                assertEquals(4, ping.getActiveTasks());
                assertEquals(12.5, ping.getCpuUsagePercent());
                break;
            }
            case "04_heartbeat_pong": {
                HeartbeatPong pong = frame.getPayloadAs(HeartbeatPong.class);
                assertEquals("OK", pong.getServerStatus());
                break;
            }
            case "05_assign_targets": {
                AssignTargetsMessage assign = frame.getPayloadAs(AssignTargetsMessage.class);
                assertEquals(2, assign.getTargets().size());
                assertEquals("Web Gateway", assign.getTargets().get(0).getName());
                assertEquals("DNS Google", assign.getTargets().get(1).getName());
                break;
            }
            case "06_report_result": {
                ReportResultMessage rep = frame.getPayloadAs(ReportResultMessage.class);
                assertEquals("agent-hn-01", rep.getAgentId());
                assertEquals(1, rep.getTargetId());
                assertTrue(rep.isSuccess());
                assertEquals(28, rep.getLatencyMs());
                assertEquals(200, rep.getResponseCode());
                assertNotNull(rep.getMetrics());
                assertEquals(10, rep.getMetrics().getDnsLookupMs());
                break;
            }
            case "07_force_check_req": {
                ForceCheckRequest fcr = frame.getPayloadAs(ForceCheckRequest.class);
                assertEquals(1, fcr.getTargetId());
                assertEquals("Web Gateway", fcr.getTarget().getName());
                break;
            }
            case "08_force_check_resp": {
                ForceCheckResponse fresp = frame.getPayloadAs(ForceCheckResponse.class);
                assertEquals(1, fresp.getTargetId());
                assertTrue(fresp.getResult().isSuccess());
                break;
            }
            case "09_console_subscribe": {
                assertEquals(0, frame.getPayloadLength());
                assertEquals(0L, frame.getChecksum());
                break;
            }
            case "0a_console_event_stream": {
                String json = frame.getPayloadAsString();
                assertTrue(json.contains("STATE_CHANGE"));
                assertTrue(json.contains("Web Gateway"));
                break;
            }
            case "0b_console_command": {
                ConsoleCommand cmd = frame.getPayloadAs(ConsoleCommand.class);
                assertEquals("list-agents", cmd.getCommand());
                assertEquals("active", cmd.getParams().get("filter"));
                break;
            }
            case "0c_console_command_resp": {
                ConsoleResponse cresp = frame.getPayloadAs(ConsoleResponse.class);
                assertTrue(cresp.isSuccess());
                assertEquals(12, cresp.getExecutionTimeMs());
                break;
            }
            case "ff_disconnect": {
                DisconnectMessage dm = frame.getPayloadAs(DisconnectMessage.class);
                assertEquals("SHUTDOWN", dm.getReason());
                break;
            }
        }
    }

    @Test
    @DisplayName("Kiểm thử mã hóa Byte-for-byte chuẩn xác tuyệt đối với Golden Vectors")
    void testEncodeByteForByteMatchesGoldenVectors() throws Exception {
        Map<String, Frame> canonicalFrames = buildCanonicalFrames();
        for (Map.Entry<String, Frame> entry : canonicalFrames.entrySet()) {
            String name = entry.getKey();
            Frame frame = entry.getValue();

            byte[] actualBytes = FrameEncoder.encode(frame);
            String actualHex = bytesToHex(actualBytes);

            byte[] expectedBytes = loadGoldenVectorBytes(name);
            String expectedHex = bytesToHex(expectedBytes);

            assertEquals(expectedHex, actualHex, "Sai khác byte-for-byte ở vector " + name);
        }
    }

    @Test
    @DisplayName("Kiểm thử Decoder v1 bỏ qua các trường JSON lạ mà không ném lỗi")
    void testV1DecoderIgnoresUnknownFields() throws Exception {
        // Giả lập v1.1 gửi payload chứa trường mở rộng (protoMax, sequenceNumber, customFields)
        String jsonWithUnknownFields = "{"
                + "\"agentId\":\"agent-extra-01\","
                + "\"hostname\":\"HOST-TEST\","
                + "\"os\":\"Linux\","
                + "\"localIp\":\"10.0.0.5\","
                + "\"macAddress\":\"AA:BB:CC:DD:EE:FF\","
                + "\"authToken\":\"tok-123\","
                + "\"supportedProtocols\":[\"TCP\"],"
                + "\"version\":\"1.0.0\","
                + "\"protoMax\":\"1.1\","          // Trường mới v1.1
                + "\"sequenceNumber\":1001,"       // Trường mới v1.1
                + "\"unknownFlag\":true"           // Trường lạ
                + "}";

        Frame frame = Frame.ofJson(MessageType.REGISTER_REQ, jsonWithUnknownFields);
        byte[] encoded = FrameEncoder.encode(frame);

        Frame decoded = FrameDecoder.readFrame(new ByteArrayInputStream(encoded));
        assertNotNull(decoded);
        RegisterRequest req = decoded.getPayloadAs(RegisterRequest.class);
        assertNotNull(req);
        assertEquals("agent-extra-01", req.getAgentId());
        assertEquals("Linux", req.getOs());
        assertEquals("10.0.0.5", req.getLocalIp());
    }

    @Test
    @DisplayName("Kiểm thử từ chối gói tin bị sửa đổi CRC32")
    void testCorruptedChecksumThrowsProtocolException() throws Exception {
        byte[] bytes = loadGoldenVectorBytes("01_register_req");
        // Sửa đổi 1 byte ở phần checksum (4 byte cuối)
        bytes[bytes.length - 1] ^= 0x55;

        assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(new ByteArrayInputStream(bytes)));
    }

    @Test
    @DisplayName("Kiểm thử từ chối gói tin có Magic Bytes sai")
    void testCorruptedMagicThrowsProtocolException() throws Exception {
        byte[] bytes = loadGoldenVectorBytes("01_register_req");
        bytes[0] = 'X'; // Sai 'N' -> 'X'

        assertThrows(ProtocolException.class, () -> FrameDecoder.readFrame(new ByteArrayInputStream(bytes)));
    }

    @Test
    @DisplayName("Kiểm thử từ chối gói tin bị cắt cụt (Truncated Stream)")
    void testTruncatedHeaderThrowsEOFException() {
        byte[] truncated = new byte[]{'N', 'C', 'A', 'S', 0x01}; // Chỉ có 5 byte
        assertThrows(EOFException.class, () -> FrameDecoder.readFrame(new ByteArrayInputStream(truncated)));
    }

    // --- Utility Methods ---

    private static byte[] loadGoldenVectorBytes(String name) throws IOException {
        Path p = GOLDEN_DIR.resolve(name + ".hex");
        if (Files.exists(p)) {
            String hex = Files.readString(p, StandardCharsets.UTF_8).trim();
            return hexToBytes(hex);
        }
        // Thử tìm trong ClassLoader (target/test-classes)
        try (InputStream in = ProtocolGoldenTest.class.getClassLoader().getResourceAsStream("protocol/golden/" + name + ".hex")) {
            if (in != null) {
                String hex = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return hexToBytes(hex);
            }
        }
        throw new IOException("Không tìm thấy file golden vector: " + name + ".hex");
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Độ dài chuỗi hex phải là số chẵn: " + len);
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}

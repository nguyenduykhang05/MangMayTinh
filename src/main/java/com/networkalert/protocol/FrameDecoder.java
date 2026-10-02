package com.networkalert.protocol;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bộ giải mã khung truyền phòng thủ (Defensive Frame Decoder) giao thức NCAS-P v1
 * <p>
 * Hiện thực cơ chế đọc dòng nhị phân stream từ Socket TCP với các lớp bảo vệ:
 * <ul>
 *   <li><b>Giới hạn kích thước phòng thủ (MAX_PAYLOAD)</b>: Mặc định 1 MiB (cấu hình được), trước xác thực tối đa 4 KiB (ERRATA §E3.8).</li>
 *   <li><b>Kiểm tra trước khi cấp phát (Pre-allocation Length Check)</b>: Ngăn chặn OOM khi bị tấn công với trường Length giả mạo (0x7FFFFFFF, 0xFFFFFFFF, v.v.).</li>
 *   <li><b>Chống Slowloris và treo Socket</b>: Nhận diện khung truyền dở dang quá hạn hoặc ngắt kết nối đột ngột giữa chừng.</li>
 *   <li><b>Đo lường chỉ số lỗi chuẩn Prometheus</b>: Tự động ghi nhận {@code protocol_errors_total{reason}} với các reason: oversize, magic, version, crc, slowloris, idle.</li>
 *   <li><b>Đóng kết nối ngay lập tức (Fail-Fast, no resync)</b>: Không cố gắng phục hồi đồng bộ dòng byte sau khi dữ liệu đã bị hỏng hoặc giả mạo.</li>
 * </ul>
 * </p>
 */
public class FrameDecoder {

    public static final int DEFAULT_MAX_PAYLOAD_SIZE = Frame.DEFAULT_MAX_PAYLOAD_SIZE; // 1 MiB
    public static final int PRE_AUTH_MAX_PAYLOAD_SIZE = Frame.PRE_AUTH_MAX_PAYLOAD_SIZE; // 4 KiB

    private static volatile int configuredMaxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;

    /**
     * Cấu hình trần kích thước Payload tối đa cho toàn hệ thống
     */
    public static void setMaxPayloadSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Max payload size phải lớn hơn 0");
        }
        configuredMaxPayloadSize = size;
    }

    /**
     * Lấy trần kích thước Payload tối đa đang được cấu hình
     */
    public static int getMaxPayloadSize() {
        return configuredMaxPayloadSize;
    }

    /**
     * Khôi phục trần kích thước Payload về mặc định (1 MiB)
     */
    public static void resetMaxPayloadSize() {
        configuredMaxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;
    }

    /**
     * Alias cho readFrame
     */
    public static Frame decodeFromStream(InputStream in) throws IOException, ProtocolException {
        return readFrame(in, configuredMaxPayloadSize);
    }

    /**
     * Giải mã từ InputStream với trần kích thước tùy chọn (ví dụ: PRE_AUTH_MAX_PAYLOAD_SIZE)
     */
    public static Frame decodeFromStream(InputStream in, int maxPayloadSize) throws IOException, ProtocolException {
        return readFrame(in, maxPayloadSize);
    }

    /**
     * Đọc và giải mã một {@link Frame} hoàn chỉnh với trần kích thước mặc định
     */
    public static Frame readFrame(InputStream in) throws IOException, ProtocolException {
        return readFrame(in, configuredMaxPayloadSize);
    }

    /**
     * Đọc và giải mã một {@link Frame} hoàn chỉnh từ luồng InputStream của Socket TCP
     *
     * @param in             Luồng nhập của Socket
     * @param maxPayloadSize Giới hạn kích thước payload tối đa cho phép
     * @return Đối tượng {@link Frame} hợp lệ, hoặc {@code null} nếu đối tác ngắt kết nối an toàn (EOF sạch)
     * @throws IOException       Khi có lỗi I/O đứt cáp mạng hoặc timeout socket
     * @throws ProtocolException Khi dữ liệu vi phạm chuẩn giao thức (Magic sai, CRC32 lỗi, vượt size)
     */
    public static Frame readFrame(InputStream in, int maxPayloadSize) throws IOException, ProtocolException {
        if (in == null) {
            throw new IllegalArgumentException("InputStream không được để null");
        }

        int effectiveMax = maxPayloadSize > 0 ? maxPayloadSize : configuredMaxPayloadSize;

        // 1. Đọc Magic Bytes (4 bytes)
        byte[] magic = new byte[4];
        int firstByte;
        try {
            firstByte = in.read();
        } catch (SocketTimeoutException ste) {
            // Socket timeout trước khi nhận được bất kỳ byte nào -> Lỗi Idle Timeout
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_IDLE);
            throw ste;
        }

        if (firstByte == -1) {
            // Đối phương đã đóng socket an toàn trước khi gửi khung mới
            return null;
        }
        magic[0] = (byte) firstByte;
        readFully(in, magic, 1, 3);

        if (!Arrays.equals(magic, Frame.MAGIC)) {
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_MAGIC);
            String hexMagic = String.format("0x%02X 0x%02X 0x%02X 0x%02X", magic[0], magic[1], magic[2], magic[3]);
            throw new ProtocolException(ProtocolMetrics.REASON_MAGIC, "Magic Bytes không hợp lệ (Dữ liệu rác hoặc không phải NCAS-P): " + hexMagic);
        }

        // 2. Đọc Version (1 byte)
        int version;
        try {
            version = in.read();
        } catch (SocketTimeoutException ste) {
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_SLOWLORIS);
            throw ste;
        }
        if (version == -1) {
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_SLOWLORIS);
            throw new EOFException("Bị ngắt kết nối khi đang đọc Version");
        }
        if (version != Frame.VERSION && version != 0x02) {
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_VERSION);
            throw new ProtocolException(ProtocolMetrics.REASON_VERSION, String.format("Không tương thích phiên bản giao thức: nhận 0x%02X, yêu cầu 0x%02X", version, Frame.VERSION));
        }

        // 3. Đọc Message Type (2 bytes short, Big-Endian)
        byte[] typeBuf = new byte[2];
        readFully(in, typeBuf, 0, 2);
        short typeCode = (short) (((typeBuf[0] & 0xFF) << 8) | (typeBuf[1] & 0xFF));
        MessageType messageType;
        try {
            messageType = MessageType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_MAGIC);
            throw new ProtocolException(ProtocolMetrics.REASON_MAGIC, "Mã thông điệp không hợp lệ: " + e.getMessage(), e);
        }

        // 4. Đọc Payload Length (4 bytes int, Big-Endian)
        byte[] lenBuf = new byte[4];
        readFully(in, lenBuf, 0, 4);
        long rawLength = (((long) lenBuf[0] & 0xFF) << 24) |
                         (((long) lenBuf[1] & 0xFF) << 16) |
                         (((long) lenBuf[2] & 0xFF) << 8)  |
                         ((long) lenBuf[3] & 0xFF);

        // KIỂM TRA LENGTH TRƯỚC KHI CẤP PHÁT BỘ NHỚ! (Defensive allocation guard)
        if (rawLength < 0 || rawLength > effectiveMax) {
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_OVERSIZE);
            throw new ProtocolException(ProtocolMetrics.REASON_OVERSIZE,
                    String.format("Kích thước Payload không hợp lệ: %d bytes (Tối đa cho phép: %d bytes)", rawLength, effectiveMax));
        }

        int payloadLength = (int) rawLength;

        // 5. Cấp phát Payload Body sau khi đã kiểm tra an toàn
        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0) {
            readFully(in, payload, 0, payloadLength);
        }

        // 6. Đọc CRC32 Checksum (4 bytes)
        byte[] crcBuf = new byte[4];
        readFully(in, crcBuf, 0, 4);
        long receivedCrc = (((long) crcBuf[0] & 0xFF) << 24) |
                           (((long) crcBuf[1] & 0xFF) << 16) |
                           (((long) crcBuf[2] & 0xFF) << 8)  |
                           ((long) crcBuf[3] & 0xFF);

        // 7. Kiểm tra tính toàn vẹn CRC32
        long computedCrc = Frame.calculateCrc32(payload);
        if (computedCrc != receivedCrc) {
            ProtocolMetrics.recordError(ProtocolMetrics.REASON_CRC);
            throw new ProtocolException(ProtocolMetrics.REASON_CRC, String.format(
                    "Lỗi toàn vẹn dữ liệu (CRC32 Checksum Mismatch): tính được 0x%08X nhưng nhận 0x%08X trên loại tin %s",
                    computedCrc, receivedCrc, messageType));
        }

        return new Frame(messageType, payload, receivedCrc);
    }

    /**
     * Giải mã toàn bộ các Frame có trong một mảng byte với kích thước mặc định
     *
     * @param data Mảng byte chứa một hoặc nhiều Frame liên tiếp
     * @return Danh sách các Frame đã giải mã thành công
     */
    public static List<Frame> decodeAll(byte[] data) throws IOException, ProtocolException {
        return decodeAll(data, configuredMaxPayloadSize);
    }

    /**
     * Giải mã toàn bộ các Frame có trong một mảng byte với trần kích thước cấu hình riêng
     */
    public static List<Frame> decodeAll(byte[] data, int maxPayloadSize) throws IOException, ProtocolException {
        List<Frame> frames = new ArrayList<>();
        if (data == null || data.length == 0) return frames;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            Frame f;
            while ((f = readFrame(bais, maxPayloadSize)) != null) {
                frames.add(f);
            }
        }
        return frames;
    }

    /**
     * Đọc đầy đủ chính xác {@code len} bytes từ luồng vào buffer.
     * Xử lý trường hợp phân mảnh mạng (gói tin bị chia nhỏ gửi qua nhiều lần read).
     */
    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        if (len < 0) {
            throw new IndexOutOfBoundsException("Độ dài đọc không được âm: " + len);
        }
        int n = 0;
        while (n < len) {
            int count;
            try {
                count = in.read(b, off + n, len - n);
            } catch (SocketTimeoutException ste) {
                // Timeout khi khung dữ liệu đang đọc dở -> Lỗi Slowloris
                ProtocolMetrics.recordError(ProtocolMetrics.REASON_SLOWLORIS);
                throw ste;
            }
            if (count < 0) {
                ProtocolMetrics.recordError(ProtocolMetrics.REASON_SLOWLORIS);
                throw new EOFException("Kết nối bị ngắt bất ngờ khi đang đọc Frame dở dang (Truncated Frame Data)");
            }
            n += count;
        }
    }
}

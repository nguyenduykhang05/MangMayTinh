package com.networkalert.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Bộ mã hóa khung truyền (Frame Encoder) giao thức NCAS-P v1
 * Chuyển đổi đối tượng {@link Frame} thành chuỗi byte nhị phân theo chuẩn Big-Endian để truyền qua Socket TCP.
 */
public class FrameEncoder {

    /**
     * Mã hóa một {@link Frame} thành mảng byte nhị phân hoàn chỉnh
     *
     * @param frame Đối tượng Frame cần mã hóa
     * @return Mảng byte chứa toàn bộ Frame (Header + Payload + Checksum)
     */
    public static byte[] encode(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("Frame không được để null");
        }
        int totalLength = Frame.HEADER_LENGTH + frame.getPayloadLength() + Frame.CHECKSUM_LENGTH;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(totalLength);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            writeFrameToStream(frame, dos);
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream không ném lỗi I/O thực tế
            throw new RuntimeException("Lỗi bất ngờ khi encode Frame: " + e.getMessage(), e);
        }
    }

    /**
     * Ghi trực tiếp một {@link Frame} ra luồng OutputStream của kết nối Socket TCP
     *
     * @param frame Đối tượng Frame cần gửi
     * @param out   Luồng xuất của Socket
     * @throws IOException Khi có lỗi đứt kết nối mạng tầng transport
     */
    public static void writeFrame(Frame frame, OutputStream out) throws IOException {
        if (frame == null || out == null) {
            throw new IllegalArgumentException("Frame và OutputStream không được để null");
        }
        DataOutputStream dos = (out instanceof DataOutputStream) ? (DataOutputStream) out : new DataOutputStream(out);
        writeFrameToStream(frame, dos);
        dos.flush();
    }

    /**
     * Alias cho encode
     */
    public static byte[] encodeToBytes(Frame frame) {
        return encode(frame);
    }

    /**
     * Alias cho writeFrame
     */
    public static void encodeToStream(Frame frame, OutputStream out) throws IOException {
        writeFrame(frame, out);
    }

    private static void writeFrameToStream(Frame frame, DataOutputStream dos) throws IOException {
        // 1. Magic Bytes (4 bytes): 'N', 'C', 'A', 'S'
        dos.write(Frame.MAGIC);

        // 2. Version (1 byte): 0x01
        dos.writeByte(Frame.VERSION);

        // 3. Message Type (2 bytes short)
        dos.writeShort(frame.getMessageType().getCode());

        // 4. Payload Length (4 bytes int)
        dos.writeInt(frame.getPayloadLength());

        // 5. Payload Body (N bytes)
        if (frame.getPayloadLength() > 0) {
            dos.write(frame.getPayload());
        }

        // 6. CRC32 Checksum (4 bytes int)
        dos.writeInt((int) frame.getChecksum());
    }
}

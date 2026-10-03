package com.networkalert.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Khung truyền dữ liệu nhị phân chuẩn hóa trong giao thức NCAS-P v1 (Protocol Data Unit)
 * <p>
 * Cấu trúc nhị phân trên đường truyền TCP (Big-Endian):
 * <pre>
 * +--------------------+---------------+--------------------+---------------------+-----------------------+--------------------+
 * | Magic Bytes (4B)   | Version (1B)  | Message Type (2B)  | Payload Length (4B) | Payload Body (N bytes)| CRC32 Checksum (4B)|
 * | 'N', 'C', 'A', 'S' | 0x01          | short (0x0001..FF) | int (0..16,777,216) | JSON (UTF-8 encoded)  | IEEE 802.3 CRC32   |
 * +--------------------+---------------+--------------------+---------------------+-----------------------+--------------------+
 * </pre>
 */
public class Frame {

    public static final byte[] MAGIC = new byte[]{'N', 'C', 'A', 'S'};
    public static final byte VERSION = 0x01;
    public static final int HEADER_LENGTH = 11;
    public static final int CHECKSUM_LENGTH = 4;
    public static final int DEFAULT_MAX_PAYLOAD_SIZE = 1024 * 1024; // 1 MiB theo đặc tả TASKS.md §T1.1
    public static final int PRE_AUTH_MAX_PAYLOAD_SIZE = 4 * 1024;   // 4 KiB theo đặc tả ERRATA §E3.8
    public static final int MAX_PAYLOAD_SIZE = DEFAULT_MAX_PAYLOAD_SIZE;

    private static final Gson GSON = new GsonBuilder().create();
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final MessageType messageType;
    private final byte[] payload;
    private final long checksum; // CRC32 (32-bit unsigned)

    public Frame(MessageType messageType, byte[] payload) {
        this(messageType, payload, calculateCrc32(payload));
    }

    public Frame(MessageType messageType, byte[] payload, long checksum) {
        this.messageType = Objects.requireNonNull(messageType, "MessageType không được để null");
        this.payload = (payload != null) ? payload : EMPTY_PAYLOAD;
        if (this.payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload vượt quá kích thước tối đa cho phép (1MB): " + this.payload.length);
        }
        this.checksum = checksum;
    }

    /**
     * Tạo Frame từ chuỗi JSON UTF-8
     */
    public static Frame ofJson(MessageType type, String json) {
        byte[] bytes = (json != null) ? json.getBytes(StandardCharsets.UTF_8) : EMPTY_PAYLOAD;
        return new Frame(type, bytes);
    }

    /**
     * Tạo Frame từ một đối tượng Java DTO (Tự động serialize sang JSON qua Gson)
     */
    public static <T> Frame ofObject(MessageType type, T object) {
        String json = GSON.toJson(object);
        return ofJson(type, json);
    }

    /**
     * Tạo Frame từ một đối tượng Java DTO (Alias tiện ích cho ofObject)
     */
    public static <T> Frame create(MessageType type, T object) {
        return ofObject(type, object);
    }

    /**
     * Tạo Frame rỗng (không có payload, dùng cho ACK/PONG)
     */
    public static Frame empty(MessageType type) {
        return new Frame(type, EMPTY_PAYLOAD);
    }

    /**
     * Đọc Payload dưới dạng chuỗi String UTF-8
     */
    public String getPayloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * Deserialize JSON payload thành đối tượng POJO Java
     */
    public <T> T getPayloadAs(Class<T> clazz) {
        String json = getPayloadAsString();
        return GSON.fromJson(json, clazz);
    }

    /**
     * Alias tiện ích cho getPayloadAs
     */
    public <T> T parsePayload(Class<T> clazz) {
        return getPayloadAs(clazz);
    }

    /**
     * Deserialize JSON payload thành Generic Type (Ví dụ: List<TargetDto>)
     */
    public <T> T getPayloadAs(Type typeOfT) {
        String json = getPayloadAsString();
        return GSON.fromJson(json, typeOfT);
    }

    /**
     * Tính mã kiểm tra toàn vẹn CRC32 chuẩn IEEE 802.3
     */
    public static long calculateCrc32(byte[] data) {
        if (data == null || data.length == 0) {
            return 0L;
        }
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    // Getters
    public MessageType getMessageType() {
        return messageType;
    }

    /**
     * Alias ngắn gọn cho getMessageType()
     */
    public MessageType getType() {
        return messageType;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getPayloadLength() {
        return payload.length;
    }

    public long getChecksum() {
        return checksum;
    }

    @Override
    public String toString() {
        return String.format("Frame[Type=%s (0x%04X), Length=%d bytes, CRC32=0x%08X]",
                messageType, messageType.getCode(), payload.length, checksum);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Frame frame = (Frame) o;
        return checksum == frame.checksum &&
                messageType == frame.messageType &&
                Arrays.equals(payload, frame.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(messageType, checksum);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}

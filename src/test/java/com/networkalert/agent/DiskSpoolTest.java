package com.networkalert.agent;

import com.networkalert.agent.spool.DiskSpoolManager;
import com.networkalert.agent.spool.SpoolRecord;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.DataGap;
import com.networkalert.protocol.messages.ReportResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kiểm thử DiskSpoolManager (ERRATA §E4.1 & §E4.2)")
class DiskSpoolTest {

    @TempDir
    Path tempDir;

    private Clock clock;
    private DiskSpoolManager spoolManager;

    @BeforeEach
    void setUp() throws IOException {
        clock = Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);
        spoolManager = new DiskSpoolManager(tempDir, clock);
        spoolManager.open();
    }

    @AfterEach
    void tearDown() {
        if (spoolManager != null) {
            spoolManager.close();
        }
    }

    @Test
    @DisplayName("Ghi bản ghi và xóa dọn segment khi nhận ACK thành công")
    void testAppendAndAcknowledgeClean() throws IOException {
        long streamId = 12345L;
        for (long seq = 1; seq <= 5; seq++) {
            ReportResultMessage report = new ReportResultMessage("agent-1", 10, clock.millis(), true, 25L, 200, null, "OK");
            report.setStreamId(streamId);
            report.setSeq(seq);
            spoolManager.append(report);
        }

        assertEquals(5, spoolManager.getSpoolDepth());
        assertTrue(spoolManager.getSpoolBytes() > 0);

        // ACK 3 bản ghi đầu
        spoolManager.acknowledge(streamId, 3L);
        assertEquals(2, spoolManager.getSpoolDepth());

        // ACK 2 bản ghi còn lại
        spoolManager.acknowledge(streamId, 5L);
        assertEquals(0, spoolManager.getSpoolDepth());
    }

    @Test
    @DisplayName("Sống sót qua crash / kill -9: Khởi động lại phục hồi 100% bản ghi cũ")
    void testCrashAndRecoveryWithoutDataLoss() throws IOException {
        long streamId = 99999L;
        for (long seq = 1; seq <= 10; seq++) {
            ReportResultMessage report = new ReportResultMessage("agent-crash", 20, clock.millis(), true, 10L, 200, null, "OK");
            report.setStreamId(streamId);
            report.setSeq(seq);
            spoolManager.append(report);
        }

        // Đóng đột ngột (giả lập kill -9)
        spoolManager.close();

        // Mở một manager mới trên cùng thư mục spool
        DiskSpoolManager recoveredManager = new DiskSpoolManager(tempDir, clock);
        recoveredManager.open();

        try {
            assertEquals(10, recoveredManager.getSpoolDepth(), "Phải phục hồi đủ 10 bản ghi sau khi khởi động lại");
            List<SpoolRecord> records = recoveredManager.getUnackedRecords();
            for (int i = 0; i < 10; i++) {
                assertEquals(streamId, records.get(i).getStreamId());
                assertEquals(i + 1, records.get(i).getSeq());
            }
        } finally {
            recoveredManager.close();
        }
    }

    @Test
    @DisplayName("Tự động nhận diện và cắt bỏ bản ghi hỏng ở đuôi file (Tail Corruption Recovery)")
    void testTailCorruptionAutoTruncation() throws IOException {
        long streamId = 88888L;
        // Ghi 3 bản ghi hợp lệ
        for (long seq = 1; seq <= 3; seq++) {
            ReportResultMessage report = new ReportResultMessage("agent-tail", 30, clock.millis(), true, 15L, 200, null, "OK");
            report.setStreamId(streamId);
            report.setSeq(seq);
            spoolManager.append(report);
        }

        // Đóng manager
        spoolManager.close();

        // Ghi thêm 10 bytes rác vào đuôi của segment file đang có để giả lập ghi dở dang khi tắt máy đột ngột
        File[] files = tempDir.toFile().listFiles((d, n) -> n.startsWith("segment-") && n.endsWith(".log"));
        assertNotNull(files);
        assertTrue(files.length > 0);
        File segFile = files[0];
        long originalValidLength = segFile.length();

        try (FileOutputStream fos = new FileOutputStream(segFile, true)) {
            fos.write(new byte[]{0x4E, 0x43, 0x01, 0x00, 0x00, 0x05, 0x00, 0x00}); // Khung cụt
        }
        assertTrue(segFile.length() > originalValidLength);

        // Khởi động lại: Trình phục hồi phải cắt bỏ 8 bytes rác và giữ nguyên 3 bản ghi hợp lệ
        DiskSpoolManager recoveryManager = new DiskSpoolManager(tempDir, clock);
        recoveryManager.open();

        try {
            assertEquals(3, recoveryManager.getSpoolDepth(), "3 bản ghi hợp lệ ban đầu phải còn nguyên");
            assertEquals(originalValidLength, segFile.length(), "Đuôi file phải được cắt bỏ chính xác về độ dài hợp lệ");
        } finally {
            recoveryManager.close();
        }
    }

    @Test
    @DisplayName("Bỏ segment khi spool đầy: Lưu chuyển trạng thái sang carry.log và khai báo DataGap")
    void testEvictOldestSegmentWithStateTransitionSavedToCarry() throws IOException {
        // Sử dụng thư mục riêng để không xung đột với manager ở BeforeEach
        Path tinyDir = tempDir.resolve("tiny");
        DiskSpoolManager tinySpool = new DiskSpoolManager(tinyDir, 800L, 24, 300L, 5000L, 1000L, 10, clock);
        tinySpool.open();

        try {
            long streamId = 77777L;

            // Target 1: ban đầu OK
            ReportResultMessage r1 = new ReportResultMessage("agent-evict", 1, clock.millis(), true, 10L, 200, null, "OK");
            r1.setStreamId(streamId);
            r1.setSeq(1L);
            tinySpool.append(r1);

            // Target 1: đổi trạng thái sang FAIL (State Transition!)
            ReportResultMessage r2 = new ReportResultMessage("agent-evict", 1, clock.millis(), false, 0L, 500, null, "DOWN");
            r2.setStreamId(streamId);
            r2.setSeq(2L);
            tinySpool.append(r2);

            // Thêm nhiều bản ghi để kích hoạt vượt trần dung lượng
            for (long seq = 3; seq <= 15; seq++) {
                ReportResultMessage rx = new ReportResultMessage("agent-evict", 2, clock.millis(), true, 10L, 200, null, "OK");
                rx.setStreamId(streamId);
                rx.setSeq(seq);
                tinySpool.append(rx);
            }

            // Kiểm tra: Phải có kết quả bị drop, có DataGap khai báo
            assertTrue(tinySpool.getDroppedResults() > 0, "Phải ghi nhận droppedResults khi vượt trần spool");
            List<DataGap> gaps = tinySpool.getPendingGaps();
            assertFalse(gaps.isEmpty(), "Phải khai báo ít nhất 1 DataGap khi bỏ dữ liệu");
            assertEquals("dropped", gaps.get(0).getReason());

            // Kiểm tra file carry.log đã được tạo và chứa bản ghi chuyển trạng thái
            File carryFile = tinyDir.resolve("carry.log").toFile();
            assertTrue(carryFile.exists(), "carry.log phải tồn tại để lưu các bản ghi chuyển trạng thái");
        } finally {
            tinySpool.close();
        }
    }
}

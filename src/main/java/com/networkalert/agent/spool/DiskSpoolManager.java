package com.networkalert.agent.spool;

import com.networkalert.protocol.Frame;
import com.networkalert.protocol.FrameDecoder;
import com.networkalert.protocol.FrameEncoder;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.messages.DataGap;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.util.LoggerUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bộ quản lý Spool đĩa bền vững cho NCAS Agent theo ERRATA §E4.1 & §E4.2:
 * <ul>
 *   <li>Ghi nối đuôi vào data/spool/segment-*.log dưới dạng các khung NCAS-P hoàn chỉnh.</li>
 *   <li>Ghi và flush mỗi bản ghi; fsync tối đa mỗi 1 giây hoặc 100 bản ghi.</li>
 *   <li>Phục hồi sau sự cố (crash / kill -9): tự động cắt bỏ bản ghi hỏng ở đuôi (tail corruption).</li>
 *   <li>Chính sách bỏ khi đầy: sao bản ghi chuyển trạng thái sang carry segment, khai báo gap, tăng droppedResults.</li>
 *   <li>Tự động xóa segment đã được Server ACK giải phóng tài nguyên đĩa.</li>
 * </ul>
 */
public class DiskSpoolManager implements Closeable {

    public static final long DEFAULT_MAX_SPOOL_BYTES = 50 * 1024 * 1024L; // 50 MB (ERRATA §E4.1)
    public static final int DEFAULT_MAX_SPOOL_HOURS = 24;                 // 24 giờ
    public static final long DEFAULT_MAX_SEGMENT_BYTES = 5 * 1024 * 1024L; // 5 MB mỗi segment
    public static final long DEFAULT_CARRY_MAX_BYTES = 5 * 1024 * 1024L;   // 5 MB carry segment
    public static final long DEFAULT_FSYNC_INTERVAL_MS = 1000L;            // 1 giây
    public static final int DEFAULT_FSYNC_BATCH_SIZE = 100;                // 100 bản ghi

    private final Path spoolDir;
    private final long maxSpoolBytes;
    private final int maxSpoolHours;
    private final long maxSegmentBytes;
    private final long carryMaxBytes;
    private final long fsyncIntervalMs;
    private final int fsyncBatchSize;
    private final Clock clock;

    private final AtomicLong totalSpoolBytes = new AtomicLong(0L);
    private final AtomicLong droppedResults = new AtomicLong(0L);
    private final List<DataGap> pendingGaps = new CopyOnWriteArrayList<>();

    // Danh sách bản ghi chưa ACK trong bộ nhớ
    private final List<SpoolRecord> unackedRecords = new ArrayList<>();
    // Theo dõi trạng thái thành công cuối cùng của từng mục tiêu để phát hiện chuyển trạng thái
    private final Map<Integer, Boolean> lastKnownTargetState = new ConcurrentHashMap<>();
    // Theo dõi các seq còn tồn trong từng file segment để xóa khi rỗng
    private final Map<String, Set<Long>> segmentPendingSeqs = new HashMap<>();

    private File activeSegmentFile;
    private FileOutputStream activeOutputStream;
    private long activeSegmentBytes = 0L;
    private int unflushedFsyncRecords = 0;
    private long lastFsyncMs = 0L;
    private long segmentCounter = 0L;

    public DiskSpoolManager(Path spoolDir, Clock clock) {
        this(spoolDir, DEFAULT_MAX_SPOOL_BYTES, DEFAULT_MAX_SPOOL_HOURS, DEFAULT_MAX_SEGMENT_BYTES,
                DEFAULT_CARRY_MAX_BYTES, DEFAULT_FSYNC_INTERVAL_MS, DEFAULT_FSYNC_BATCH_SIZE, clock);
    }

    public DiskSpoolManager(Path spoolDir, long maxSpoolBytes, int maxSpoolHours, long maxSegmentBytes,
                            long carryMaxBytes, long fsyncIntervalMs, int fsyncBatchSize, Clock clock) {
        this.spoolDir = spoolDir != null ? spoolDir : Path.of("data/spool");
        this.maxSpoolBytes = maxSpoolBytes > 0 ? maxSpoolBytes : DEFAULT_MAX_SPOOL_BYTES;
        this.maxSpoolHours = maxSpoolHours > 0 ? maxSpoolHours : DEFAULT_MAX_SPOOL_HOURS;
        this.maxSegmentBytes = maxSegmentBytes > 0 ? maxSegmentBytes : DEFAULT_MAX_SEGMENT_BYTES;
        this.carryMaxBytes = carryMaxBytes > 0 ? carryMaxBytes : DEFAULT_CARRY_MAX_BYTES;
        this.fsyncIntervalMs = fsyncIntervalMs > 0 ? fsyncIntervalMs : DEFAULT_FSYNC_INTERVAL_MS;
        this.fsyncBatchSize = fsyncBatchSize > 0 ? fsyncBatchSize : DEFAULT_FSYNC_BATCH_SIZE;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Khởi tạo thư mục và thực hiện phục hồi dữ liệu từ các segment cũ trên đĩa
     */
    public synchronized void open() throws IOException {
        Files.createDirectories(spoolDir);
        recover();
        rotateActiveSegment();
    }

    /**
     * Quét và phục hồi toàn bộ segment hiện có trên đĩa, tự động cắt bỏ phần đuôi bị hỏng nếu có
     */
    public synchronized void recover() {
        unackedRecords.clear();
        segmentPendingSeqs.clear();
        totalSpoolBytes.set(0L);

        File dir = spoolDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return;
        }

        // Sắp xếp các file theo thứ tự thời gian tạo/tên
        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            recoverSegmentFile(file);
        }

        LoggerUtil.info(String.format("📁 [DiskSpool] Hoàn tất phục hồi: %d bản ghi chưa ACK | Tổng dung lượng spool: %d bytes",
                unackedRecords.size(), totalSpoolBytes.get()));
    }

    private void recoverSegmentFile(File file) {
        long validOffset = 0L;
        long corruptOffset = -1L;
        Set<Long> pendingSeqs = new HashSet<>();
        long fileLen = file.length();
        totalSpoolBytes.addAndGet(fileLen);

        try (FileInputStream fis = new FileInputStream(file);
             CountingInputStream cin = new CountingInputStream(fis)) {

            while (cin.getCount() < fileLen) {
                long frameStartOffset = cin.getCount();
                Frame frame;
                try {
                    frame = FrameDecoder.readFrame(cin);
                } catch (EOFException eof) {
                    // Đạt cuối file sạch
                    break;
                } catch (Exception ex) {
                    // Phát hiện bản ghi bị hỏng ở đuôi file (tail corruption do crash đột ngột)
                    LoggerUtil.warn(String.format("⚠️ [DiskSpool] Phát hiện đuôi file hỏng tại %s offset %d. Cắt bỏ đuôi file...",
                            file.getName(), frameStartOffset));
                    corruptOffset = frameStartOffset;
                    break;
                }

                if (frame != null && frame.getType() == MessageType.REPORT_RESULT) {
                    try {
                        ReportResultMessage report = frame.parsePayload(ReportResultMessage.class);
                        long frameLen = cin.getCount() - frameStartOffset;
                        SpoolRecord record = new SpoolRecord(frame, report, file.getName(), frameStartOffset, (int) frameLen);
                        unackedRecords.add(record);

                        if (report.getSeq() != null) {
                            pendingSeqs.add(report.getSeq());
                        }
                        if (report.getTargetId() > 0) {
                            lastKnownTargetState.put(report.getTargetId(), report.isSuccess());
                        }
                        validOffset = cin.getCount();
                    } catch (Exception parseEx) {
                        LoggerUtil.warn(String.format("⚠️ [DiskSpool] Payload hỏng tại %s offset %d. Cắt bỏ đuôi file...",
                                file.getName(), frameStartOffset));
                        corruptOffset = frameStartOffset;
                        break;
                    }
                }
            }
        } catch (IOException e) {
            LoggerUtil.warn("[DiskSpool] Lỗi khi đọc file segment " + file.getName() + ": " + e.getMessage());
        }

        // Cắt bỏ đuôi hỏng sau khi đã đóng toàn bộ luồng đọc (Tránh xung đột khóa file trên Windows)
        if (corruptOffset >= 0) {
            truncateCorruptTail(file, corruptOffset);
        }

        if (!pendingSeqs.isEmpty()) {
            segmentPendingSeqs.put(file.getName(), pendingSeqs);
        }
    }

    private void truncateCorruptTail(File file, long validOffset) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long oldLen = raf.length();
            raf.setLength(validOffset);
            long diff = oldLen - validOffset;
            if (diff > 0) {
                totalSpoolBytes.addAndGet(-diff);
            }
            LoggerUtil.info(String.format("✂️ [DiskSpool] Đã cắt bỏ %d bytes hỏng ở đuôi file %s.", diff, file.getName()));
        } catch (IOException e) {
            LoggerUtil.error("[DiskSpool] Không thể cắt đuôi file " + file.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Ghi một kết quả đo kiểm vào đĩa spool (bền vững qua sập nguồn)
     */
    public synchronized SpoolRecord append(ReportResultMessage report) throws IOException {
        if (report == null) return null;

        Frame frame = Frame.create(MessageType.REPORT_RESULT, report);
        byte[] frameBytes = FrameEncoder.encode(frame);

        // Kiểm tra xoay vòng segment nếu vượt ngưỡng kích thước
        if (activeOutputStream == null || activeSegmentBytes + frameBytes.length > maxSegmentBytes) {
            rotateActiveSegment();
        }

        long fileOffset = activeSegmentBytes;
        activeOutputStream.write(frameBytes);
        activeOutputStream.flush(); // Flush userspace buffer ra OS page cache ngay lập tức

        activeSegmentBytes += frameBytes.length;
        totalSpoolBytes.addAndGet(frameBytes.length);

        SpoolRecord record = new SpoolRecord(frame, report, activeSegmentFile.getName(), fileOffset, frameBytes.length);
        unackedRecords.add(record);

        if (report.getSeq() != null) {
            segmentPendingSeqs.computeIfAbsent(activeSegmentFile.getName(), k -> new HashSet<>()).add(report.getSeq());
        }
        if (report.getTargetId() > 0) {
            lastKnownTargetState.put(report.getTargetId(), report.isSuccess());
        }

        // fsync theo lô hoặc định kỳ
        unflushedFsyncRecords++;
        long now = clock.millis();
        if (unflushedFsyncRecords >= fsyncBatchSize || (now - lastFsyncMs >= fsyncIntervalMs)) {
            syncActiveFile();
        }

        // Kiểm tra trần tổng dung lượng spool (50 MB)
        if (totalSpoolBytes.get() > maxSpoolBytes) {
            evictOldestSegment();
        }

        return record;
    }

    /**
     * Đẩy toàn bộ dữ liệu từ OS page cache xuống đĩa vật lý (fsync)
     */
    public synchronized void syncActiveFile() {
        if (activeOutputStream != null) {
            try {
                activeOutputStream.getFD().sync();
                unflushedFsyncRecords = 0;
                lastFsyncMs = clock.millis();
            } catch (IOException e) {
                LoggerUtil.warn("[DiskSpool] fsync thất bại: " + e.getMessage());
            }
        }
    }

    private void rotateActiveSegment() throws IOException {
        closeActiveOutputStream();
        Files.createDirectories(spoolDir);

        String filename = String.format("segment-%08d-%d.log", ++segmentCounter, clock.millis());
        activeSegmentFile = spoolDir.resolve(filename).toFile();
        activeOutputStream = new FileOutputStream(activeSegmentFile, true);
        activeSegmentBytes = activeSegmentFile.length();
        unflushedFsyncRecords = 0;
        lastFsyncMs = clock.millis();
    }

    private void closeActiveOutputStream() {
        if (activeOutputStream != null) {
            try {
                activeOutputStream.flush();
                activeOutputStream.getFD().sync();
                activeOutputStream.close();
            } catch (Exception ignored) {}
            activeOutputStream = null;
        }
    }

    /**
     * Xác nhận các bản ghi đã được Server ACK; xóa segment trên đĩa khi toàn bộ bản ghi trong đó đã được ACK
     */
    public synchronized void acknowledge(long streamId, long ackSeq) {
        Iterator<SpoolRecord> it = unackedRecords.iterator();
        while (it.hasNext()) {
            SpoolRecord r = it.next();
            if (r.getStreamId() == streamId && r.getSeq() <= ackSeq) {
                r.setAcknowledged(true);
                it.remove();

                Set<Long> pending = segmentPendingSeqs.get(r.getSegmentFileName());
                if (pending != null) {
                    pending.remove(r.getSeq());
                }
            }
        }

        // Dọn dẹp các segment file đã hoàn toàn được ACK
        cleanupFullyAckedSegments();
    }

    private void cleanupFullyAckedSegments() {
        List<String> toDelete = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> entry : segmentPendingSeqs.entrySet()) {
            String fname = entry.getKey();
            // Không xóa segment hiện đang mở để ghi
            if (activeSegmentFile != null && activeSegmentFile.getName().equals(fname)) {
                continue;
            }
            if (entry.getValue().isEmpty()) {
                toDelete.add(fname);
            }
        }

        for (String fname : toDelete) {
            File f = spoolDir.resolve(fname).toFile();
            long len = f.length();
            if (f.delete()) {
                totalSpoolBytes.addAndGet(-len);
                segmentPendingSeqs.remove(fname);
                LoggerUtil.info(String.format("🧹 [DiskSpool] Đã xóa segment %s sau khi hoàn tất ACK toàn bộ.", fname));
            }
        }
    }

    /**
     * Chính sách bỏ khi đầy (§E4.2):
     * Bỏ segment cũ nhất, giữ lại bản ghi chuyển trạng thái sang carry.log, khai báo DataGap và tăng droppedResults.
     */
    public synchronized void evictOldestSegment() {
        File dir = spoolDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.startsWith("segment-") && name.endsWith(".log"));
        if (files == null || files.length <= 1) {
            // Không xóa nếu chỉ có 1 segment hoặc rỗng
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        File oldest = files[0];
        // Không xóa active segment
        if (activeSegmentFile != null && activeSegmentFile.getName().equals(oldest.getName())) {
            return;
        }

        LoggerUtil.warn("⚠️ [DiskSpool] Vượt trần dung lượng spool (" + totalSpoolBytes.get() + " bytes). Đang loại bỏ segment cũ nhất: " + oldest.getName());

        // 1. Phân loại các bản ghi trong segment này: giữ bản ghi chuyển trạng thái vào carry.log
        List<SpoolRecord> segmentRecords = new ArrayList<>();
        for (SpoolRecord r : unackedRecords) {
            if (r.getSegmentFileName().equals(oldest.getName())) {
                segmentRecords.add(r);
            }
        }

        Map<Long, List<Long>> droppedSeqsByStream = new HashMap<>();

        for (SpoolRecord r : segmentRecords) {
            ReportResultMessage rep = r.getReport();
            boolean isStateTransition = false;
            if (rep != null && rep.getTargetId() > 0) {
                Boolean prev = lastKnownTargetState.get(rep.getTargetId());
                if (prev != null && prev != rep.isSuccess()) {
                    isStateTransition = true;
                }
            }

            if (isStateTransition) {
                // Sao chép sang carry.log
                saveToCarrySegment(r);
            } else {
                // Ghi nhận seq bị bỏ
                droppedSeqsByStream.computeIfAbsent(r.getStreamId(), k -> new ArrayList<>()).add(r.getSeq());
                droppedResults.incrementAndGet();
            }
            unackedRecords.remove(r);
        }

        // 2. Khai báo DataGap cho các đoạn seq bị bỏ
        for (Map.Entry<Long, List<Long>> entry : droppedSeqsByStream.entrySet()) {
            long stId = entry.getKey();
            List<Long> seqs = entry.getValue();
            if (!seqs.isEmpty()) {
                Collections.sort(seqs);
                long fromSeq = seqs.get(0);
                long toSeq = seqs.get(seqs.size() - 1);
                pendingGaps.add(new DataGap(stId, fromSeq, toSeq, "dropped"));
                LoggerUtil.alert(String.format("⚠️ [DiskSpool] Đã khai báo DataGap: stream=%d, seq=[%d..%d], reason=dropped",
                        stId, fromSeq, toSeq));
            }
        }

        // 3. Xóa file segment cũ nhất
        long len = oldest.length();
        if (oldest.delete()) {
            totalSpoolBytes.addAndGet(-len);
            segmentPendingSeqs.remove(oldest.getName());
            LoggerUtil.info("🗑️ [DiskSpool] Đã giải phóng segment: " + oldest.getName());
        }
    }

    private void saveToCarrySegment(SpoolRecord record) {
        File carryFile = spoolDir.resolve("carry.log").toFile();
        if (carryFile.length() > carryMaxBytes) {
            LoggerUtil.warn("⚠️ [DiskSpool] Carry segment đã đầy (" + carryFile.length() + " bytes). Bỏ qua sao lưu carry.");
            droppedResults.incrementAndGet();
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(carryFile, true)) {
            byte[] bytes = FrameEncoder.encode(record.getFrame());
            fos.write(bytes);
            fos.flush();
            fos.getFD().sync();
            totalSpoolBytes.addAndGet(bytes.length);

            // Cập nhật lại tên segment của bản ghi sang carry.log
            SpoolRecord carryRec = new SpoolRecord(record.getFrame(), record.getReport(), carryFile.getName(),
                    carryFile.length() - bytes.length, bytes.length);
            unackedRecords.add(carryRec);
            segmentPendingSeqs.computeIfAbsent(carryFile.getName(), k -> new HashSet<>()).add(record.getSeq());

            LoggerUtil.info(String.format("📌 [DiskSpool] Đã lưu bản ghi chuyển trạng thái seq=%d vào carry.log", record.getSeq()));
        } catch (IOException e) {
            LoggerUtil.error("[DiskSpool] Không thể ghi carry.log: " + e.getMessage());
        }
    }

    /**
     * Lấy danh sách các bản ghi chưa ACK sẵn sàng để gửi lại (backlog)
     */
    public synchronized List<SpoolRecord> getUnackedRecords() {
        return new ArrayList<>(unackedRecords);
    }

    public synchronized int getSpoolDepth() {
        return unackedRecords.size();
    }

    public long getSpoolBytes() {
        return totalSpoolBytes.get();
    }

    public long getDroppedResults() {
        return droppedResults.get();
    }

    public List<DataGap> getPendingGaps() {
        return new ArrayList<>(pendingGaps);
    }

    public void clearPendingGaps() {
        pendingGaps.clear();
    }

    @Override
    public synchronized void close() {
        syncActiveFile();
        closeActiveOutputStream();
    }

    private static class CountingInputStream extends FilterInputStream {
        private long count = 0;

        public CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int r = in.read();
            if (r != -1) count++;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, len);
            if (r != -1) count += r;
            return r;
        }

        public long getCount() {
            return count;
        }
    }
}

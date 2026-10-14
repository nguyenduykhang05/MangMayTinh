package com.networkalert.agent.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.networkalert.protocol.messages.TargetDto;
import com.networkalert.util.LoggerUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý lưu trữ và nạp bộ nhớ đệm cấu hình phân công mục tiêu (ERRATA §E4.5):
 * <ul>
 *   <li>Lưu bản phân công cuối vào data/assigned.json (ghi tạm .tmp rồi đổi tên nguyên tử).</li>
 *   <li>Khởi động lại khi Server đang sập vẫn nạp danh sách mục tiêu từ file cache tiếp tục đo.</li>
 * </ul>
 */
public class TargetConfigCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path cacheFilePath;

    public static class CachedPayload {
        private int version = 1;
        private long savedAtMs;
        private List<TargetDto> targets = new ArrayList<>();

        public CachedPayload() {}

        public CachedPayload(int version, long savedAtMs, List<TargetDto> targets) {
            this.version = version;
            this.savedAtMs = savedAtMs;
            this.targets = targets != null ? targets : new ArrayList<>();
        }

        public int getVersion() { return version; }
        public long getSavedAtMs() { return savedAtMs; }
        public List<TargetDto> getTargets() { return targets; }
    }

    public TargetConfigCache(Path cacheFilePath) {
        this.cacheFilePath = cacheFilePath != null ? cacheFilePath : Path.of("data/assigned.json");
    }

    public Path getCacheFilePath() {
        return cacheFilePath;
    }

    /**
     * Lưu danh sách mục tiêu đo kiểm vào đĩa an toàn bằng cơ chế Atomic Rename
     */
    public synchronized void saveTargets(List<TargetDto> targets, long nowMs) {
        if (targets == null) return;
        try {
            Path parent = cacheFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmpPath = cacheFilePath.resolveSibling(cacheFilePath.getFileName().toString() + ".tmp");
            CachedPayload payload = new CachedPayload(1, nowMs, targets);
            String json = GSON.toJson(payload);

            Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            try {
                Files.move(tmpPath, cacheFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, cacheFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
            LoggerUtil.info(String.format("💾 [TargetCache] Đã lưu cache %d mục tiêu vào %s", targets.size(), cacheFilePath));
        } catch (Exception e) {
            LoggerUtil.warn("[TargetCache] Không thể lưu cache mục tiêu: " + e.getMessage());
        }
    }

    /**
     * Nạp danh sách mục tiêu từ file cache nếu tồn tại
     */
    public synchronized List<TargetDto> loadTargets() {
        if (!Files.exists(cacheFilePath)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(cacheFilePath, StandardCharsets.UTF_8);
            CachedPayload payload = GSON.fromJson(json, CachedPayload.class);
            if (payload != null && payload.getTargets() != null) {
                LoggerUtil.info(String.format("📂 [TargetCache] Đã nạp %d mục tiêu từ cache %s (Lưu lúc: %d)",
                        payload.getTargets().size(), cacheFilePath, payload.getSavedAtMs()));
                return payload.getTargets();
            }
        } catch (Exception e) {
            LoggerUtil.warn("[TargetCache] Lỗi đọc file cache " + cacheFilePath + ": " + e.getMessage());
        }
        return new ArrayList<>();
    }
}

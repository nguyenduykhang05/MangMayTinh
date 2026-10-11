package com.networkalert.simulator.scenario;

import com.google.gson.Gson;
import com.networkalert.util.LoggerUtil;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mô hình kịch bản diễn tiến tự động theo ERRATA §E14.1.
 */
public class ScenarioModel {
    private String name = "Kịch bản Demo Tự Động NCAS 2.0";
    private String description = "Kịch bản kiểm thử lặp lại theo ERRATA §E14.1";
    private List<ScenarioStep> steps = new ArrayList<>();

    public ScenarioModel() {}

    public ScenarioModel(String name, String description, List<ScenarioStep> steps) {
        this.name = name;
        this.description = description;
        this.steps = steps != null ? steps : new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<ScenarioStep> getSteps() { return steps; }
    public void setSteps(List<ScenarioStep> steps) { this.steps = steps; }

    /**
     * Tải kịch bản từ file JSON. Nếu không tìm thấy hoặc lỗi, tự động nạp kịch bản chuẩn E14.1 mặc định.
     */
    public static ScenarioModel loadFromFileOrDefault(File file) {
        if (file != null && file.exists() && file.isFile()) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                ScenarioModel model = new Gson().fromJson(reader, ScenarioModel.class);
                if (model != null && model.getSteps() != null && !model.getSteps().isEmpty()) {
                    model.getSteps().sort(Comparator.comparingLong(ScenarioStep::getTimeOffsetSeconds));
                    LoggerUtil.info("Đã nạp thành công kịch bản từ file: " + file.getAbsolutePath() + " (" + model.getSteps().size() + " bước)");
                    return model;
                }
            } catch (Exception e) {
                LoggerUtil.warn("Không thể đọc kịch bản từ " + file.getPath() + ": " + e.getMessage() + ". Sử dụng kịch bản mặc định.");
            }
        }
        return createDefault();
    }

    /**
     * Khởi tạo kịch bản chuẩn E14.1 mặc định:
     * t=0 mọi thứ OK, +30s TCP sập, +90s HTTP 500, +150s trễ 500ms, +210s hồi phục
     */
    public static ScenarioModel createDefault() {
        List<ScenarioStep> list = new ArrayList<>();
        list.add(new ScenarioStep(0, "Mọi thứ OK", "Khởi chạy toàn bộ TCP, UDP, HTTP (status 200, trễ 0ms)",
                true, 0, true, 200, 0, true));
        list.add(new ScenarioStep(30, "TCP sập", "Ngắt kết nối Mock TCP Server (Port 9000)",
                false, null, null, null, null, null));
        list.add(new ScenarioStep(90, "HTTP 500", "Mock HTTP Server (Port 9002) trả về lỗi HTTP 500",
                null, null, null, 500, null, null));
        list.add(new ScenarioStep(150, "Thêm trễ 500ms", "Mock HTTP Server phản hồi chậm thêm 500ms (vượt ngưỡng)",
                null, null, null, null, 500, null));
        list.add(new ScenarioStep(210, "Hồi phục hoàn toàn", "Khôi phục TCP Server online, HTTP Server status 200 và độ trễ 0ms",
                true, 0, true, 200, 0, true));

        return new ScenarioModel(
                "Kịch bản Demo Tự Động NCAS 2.0 (ERRATA §E14.1)",
                "Diễn tiến sự cố và tự phục hồi tất định: t=0 mọi thứ OK, +30s TCP sập, +90s HTTP 500, +150s trễ 500ms, +210s hồi phục",
                list
        );
    }
}

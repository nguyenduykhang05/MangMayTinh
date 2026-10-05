package com.networkalert.repository;

import com.networkalert.model.AlertConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Tầng truy xuất cấu hình cảnh báo từ bảng AlertSettings trong SQL Server
 */
public class ConfigRepository {
    private final DatabaseManager dbManager;

    public ConfigRepository() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public AlertConfig loadAlertConfig() {
        AlertConfig config = new AlertConfig();
        if (dbManager.isConnected()) {
            String sql = "SELECT setting_key, setting_value FROM AlertSettings";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                Map<String, String> map = new HashMap<>();
                while (rs.next()) {
                    map.put(rs.getString("setting_key"), rs.getString("setting_value"));
                }

                if (map.containsKey("popup_enabled")) {
                    config.setPopupEnabled(Boolean.parseBoolean(map.get("popup_enabled")));
                }
                if (map.containsKey("sound_enabled")) {
                    config.setSoundEnabled(Boolean.parseBoolean(map.get("sound_enabled")));
                }
                if (map.containsKey("email_enabled")) {
                    config.setEmailEnabled(Boolean.parseBoolean(map.get("email_enabled")));
                }
                if (map.containsKey("telegram_enabled")) {
                    config.setTelegramEnabled(Boolean.parseBoolean(map.get("telegram_enabled")));
                }
                if (map.containsKey("telegram_bot_token")) {
                    config.setTelegramBotToken(map.get("telegram_bot_token"));
                }
                if (map.containsKey("telegram_chat_id")) {
                    config.setTelegramChatId(map.get("telegram_chat_id"));
                }
                if (map.containsKey("minimize_to_tray")) {
                    config.setMinimizeToTrayOnClose(Boolean.parseBoolean(map.get("minimize_to_tray")));
                }
                if (map.containsKey("smtp_host")) {
                    config.setSmtpHost(map.get("smtp_host"));
                }
                if (map.containsKey("smtp_port")) {
                    try {
                        config.setSmtpPort(Integer.parseInt(map.get("smtp_port")));
                    } catch (NumberFormatException ignored) {}
                }
                if (map.containsKey("smtp_username")) {
                    config.setSmtpUsername(map.get("smtp_username"));
                }
                if (map.containsKey("smtp_password")) {
                    config.setSmtpPassword(map.get("smtp_password"));
                }
                if (map.containsKey("smtp_from")) {
                    config.setSmtpFrom(map.get("smtp_from"));
                }
                if (map.containsKey("smtp_to")) {
                    config.setSmtpTo(map.get("smtp_to"));
                }
                if (map.containsKey("latency_threshold_ms")) {
                    try {
                        config.setLatencyThresholdMs(Long.parseLong(map.get("latency_threshold_ms")));
                    } catch (NumberFormatException ignored) {}
                }
                if (map.containsKey("max_retries")) {
                    try {
                        config.setMaxRetries(Integer.parseInt(map.get("max_retries")));
                    } catch (NumberFormatException ignored) {}
                }
                if (map.containsKey("api_token") && map.get("api_token") != null && !map.get("api_token").trim().isEmpty()) {
                    config.setApiToken(map.get("api_token").trim());
                }
                if (map.containsKey("web_localhost_only")) {
                    config.setWebDashboardLocalhostOnly(Boolean.parseBoolean(map.get("web_localhost_only")));
                }
            } catch (SQLException e) {
                System.err.println("[ConfigRepository] Lỗi tải cấu hình từ SQL Server: " + e.getMessage());
            }
        }
        return config;
    }

    public void saveAlertConfig(AlertConfig config) {
        if (dbManager.isConnected()) {
            Map<String, String> map = new HashMap<>();
            map.put("popup_enabled", String.valueOf(config.isPopupEnabled()));
            map.put("sound_enabled", String.valueOf(config.isSoundEnabled()));
            map.put("email_enabled", String.valueOf(config.isEmailEnabled()));
            map.put("telegram_enabled", String.valueOf(config.isTelegramEnabled()));
            map.put("telegram_bot_token", config.getTelegramBotToken());
            map.put("telegram_chat_id", config.getTelegramChatId());
            map.put("minimize_to_tray", String.valueOf(config.isMinimizeToTrayOnClose()));
            map.put("smtp_host", config.getSmtpHost());
            map.put("smtp_port", String.valueOf(config.getSmtpPort()));
            map.put("smtp_username", config.getSmtpUsername());
            map.put("smtp_password", config.getSmtpPassword());
            map.put("smtp_from", config.getSmtpFrom());
            map.put("smtp_to", config.getSmtpTo());
            map.put("latency_threshold_ms", String.valueOf(config.getLatencyThresholdMs()));
            map.put("max_retries", String.valueOf(config.getMaxRetries()));
            map.put("api_token", config.getApiToken());
            map.put("web_localhost_only", String.valueOf(config.isWebDashboardLocalhostOnly()));

            String sql = "IF EXISTS (SELECT 1 FROM AlertSettings WHERE setting_key = ?) " +
                         "UPDATE AlertSettings SET setting_value = ? WHERE setting_key = ? " +
                         "ELSE INSERT INTO AlertSettings (setting_key, setting_value) VALUES (?, ?)";

            try (Connection conn = dbManager.getConnection()) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, entry.getKey());
                        ps.setString(2, entry.getValue());
                        ps.setString(3, entry.getKey());
                        ps.setString(4, entry.getKey());
                        ps.setString(5, entry.getValue());
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                System.err.println("[ConfigRepository] Lỗi lưu cấu hình vào SQL Server: " + e.getMessage());
            }
        }
    }
}

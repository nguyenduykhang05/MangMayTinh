package com.networkalert.model;

/**
 * Cấu hình cảnh báo của hệ thống (Popup, Âm thanh, Email SMTP, Telegram Bot, System Tray)
 */
public class AlertConfig {
    private boolean popupEnabled = true;
    private boolean soundEnabled = true;
    private boolean emailEnabled = false;

    // Cấu hình Telegram Bot (Thực tế)
    private boolean telegramEnabled = false;
    private String telegramBotToken = "";
    private String telegramChatId = "";

    // Cấu hình Tray & Chạy ngầm 24/7
    private boolean minimizeToTrayOnClose = true;

    // Cấu hình SMTP
    private String smtpHost = "smtp.gmail.com";
    private int smtpPort = 587;
    private String smtpUsername = "";
    private String smtpPassword = "";
    private String smtpFrom = "";
    private String smtpTo = "";
    private boolean useTls = true;

    // Ngưỡng cảnh báo độ trễ cao (ms)
    private long latencyThresholdMs = 300;

    // Số lần thất bại liên tiếp trước khi chuyển OFFLINE (chống flapping)
    private int maxRetries = 3;

    // Bảo mật Web Dashboard REST API
    private String apiToken = "";
    private boolean webDashboardLocalhostOnly = true;

    public AlertConfig() {
        if (this.apiToken == null || this.apiToken.isEmpty()) {
            this.apiToken = java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }

    // Getters and Setters
    public boolean isPopupEnabled() { return popupEnabled; }
    public void setPopupEnabled(boolean popupEnabled) { this.popupEnabled = popupEnabled; }

    public boolean isSoundEnabled() { return soundEnabled; }
    public void setSoundEnabled(boolean soundEnabled) { this.soundEnabled = soundEnabled; }

    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean emailEnabled) { this.emailEnabled = emailEnabled; }

    public boolean isTelegramEnabled() { return telegramEnabled; }
    public void setTelegramEnabled(boolean telegramEnabled) { this.telegramEnabled = telegramEnabled; }

    public String getTelegramBotToken() { return telegramBotToken; }
    public void setTelegramBotToken(String telegramBotToken) { this.telegramBotToken = telegramBotToken; }

    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }

    public boolean isMinimizeToTrayOnClose() { return minimizeToTrayOnClose; }
    public void setMinimizeToTrayOnClose(boolean minimizeToTrayOnClose) { this.minimizeToTrayOnClose = minimizeToTrayOnClose; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

    public String getSmtpFrom() { return smtpFrom; }
    public void setSmtpFrom(String smtpFrom) { this.smtpFrom = smtpFrom; }

    public String getSmtpTo() { return smtpTo; }
    public void setSmtpTo(String smtpTo) { this.smtpTo = smtpTo; }

    public boolean isUseTls() { return useTls; }
    public void setUseTls(boolean useTls) { this.useTls = useTls; }

    public long getLatencyThresholdMs() { return latencyThresholdMs; }
    public void setLatencyThresholdMs(long latencyThresholdMs) { this.latencyThresholdMs = latencyThresholdMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = Math.max(1, maxRetries); }

    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }

    public boolean isWebDashboardLocalhostOnly() { return webDashboardLocalhostOnly; }
    public void setWebDashboardLocalhostOnly(boolean webDashboardLocalhostOnly) { this.webDashboardLocalhostOnly = webDashboardLocalhostOnly; }
}

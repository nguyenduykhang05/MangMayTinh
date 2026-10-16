package com.networkalert.service;

import com.networkalert.model.AlertConfig;
import com.networkalert.util.LoggerUtil;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dịch vụ gửi cảnh báo tức thời qua Telegram Bot API (Đặc quyền Thực tế cho SysAdmin / DevOps)
 */
public class TelegramAlertService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void sendTelegramAlertAsync(AlertConfig config, String message) {
        if (!config.isTelegramEnabled() || 
            config.getTelegramBotToken() == null || config.getTelegramBotToken().trim().isEmpty() ||
            config.getTelegramChatId() == null || config.getTelegramChatId().trim().isEmpty()) {
            return;
        }

        executor.submit(() -> {
            try {
                sendTelegramMessage(config.getTelegramBotToken().trim(), config.getTelegramChatId().trim(), message);
                LoggerUtil.info("Đã gửi thông báo Telegram thành công tới Chat ID: " + config.getTelegramChatId());
            } catch (Exception e) {
                LoggerUtil.error("Lỗi gửi thông báo Telegram: " + e.getMessage());
            }
        });
    }

    public boolean testTelegramConnection(String botToken, String chatId) throws Exception {
        String testMsg = "🤖 <b>[NCAS System] Kiểm Tra Kết Nối Telegram Bot</b>\n\n" +
                         "✔ Kết nối thành công! Kênh thông báo Telegram đã sẵn sàng nhận cảnh báo sự cố mạng theo thời gian thực.";
        return sendTelegramMessage(botToken, chatId, testMsg);
    }

    private boolean sendTelegramMessage(String botToken, String chatId, String htmlMessage) throws Exception {
        String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        String postData = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8) +
                          "&text=" + URLEncoder.encode(htmlMessage, StandardCharsets.UTF_8) +
                          "&parse_mode=HTML";

        byte[] postBytes = postData.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(postBytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code == 200) {
            return true;
        } else {
            throw new RuntimeException("Telegram API trả về HTTP " + code);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}

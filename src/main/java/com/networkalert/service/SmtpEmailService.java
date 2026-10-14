package com.networkalert.service;

import com.networkalert.model.AlertConfig;
import com.networkalert.util.LoggerUtil;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dịch vụ gửi email cảnh báo qua giao thức SMTP (Tầng Ứng dụng - Application Layer)
 */
public class SmtpEmailService {
    private final ExecutorService emailExecutor = Executors.newSingleThreadExecutor();

    public void sendAlertEmailAsync(AlertConfig config, String subject, String body) {
        if (!config.isEmailEnabled() || config.getSmtpTo() == null || config.getSmtpTo().trim().isEmpty()) {
            return;
        }

        emailExecutor.submit(() -> {
            try {
                sendEmail(config, subject, body);
                LoggerUtil.info("Đã gửi email cảnh báo tới " + config.getSmtpTo() + " thành công qua SMTP.");
            } catch (Exception e) {
                LoggerUtil.error("Lỗi gửi email cảnh báo qua SMTP: " + e.getMessage());
            }
        });
    }

    public boolean testSmtpConnection(AlertConfig config) throws Exception {
        return sendEmail(config, "[Network Monitor] Thử nghiệm cấu hình SMTP thành công",
                "Xin chào,\n\nĐây là email kiểm tra kết nối từ Hệ thống Giám sát & Cảnh báo Mạng.\nGiao thức SMTP đang hoạt động chính xác.");
    }

    private boolean sendEmail(AlertConfig config, String subject, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");

        if (config.getSmtpPort() == 465) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", "465");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        Authenticator auth = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getSmtpUsername(), config.getSmtpPassword());
            }
        };

        Session session = Session.getInstance(props, auth);

        Message message = new MimeMessage(session);
        String fromAddress = (config.getSmtpFrom() != null && !config.getSmtpFrom().trim().isEmpty())
                ? config.getSmtpFrom()
                : config.getSmtpUsername();
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.getSmtpTo()));
        message.setSubject(subject);
        message.setText(body);

        Transport.send(message);
        return true;
    }

    public void shutdown() {
        emailExecutor.shutdown();
    }
}

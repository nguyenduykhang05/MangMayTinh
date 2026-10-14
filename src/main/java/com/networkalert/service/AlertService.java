package com.networkalert.service;

import com.networkalert.model.AlertConfig;
import com.networkalert.model.IncidentLog;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.ConfigRepository;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.SoundHelper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Điều phối các hình thức cảnh báo (Popup Toast, Âm thanh, Ghi Incident SQL, Gửi Email SMTP, Telegram Bot API)
 */
public class AlertService {
    private final AlertConfig config;
    private final ConfigRepository configRepository;
    private final IncidentRepository incidentRepository;
    private final SmtpEmailService emailService;
    private final TelegramAlertService telegramService;
    private final List<BiConsumer<String, String>> toastListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<java.util.function.Consumer<IncidentLog>> incidentListeners = Collections.synchronizedList(new ArrayList<>());
    private final Clock clock;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    public AlertService(IncidentRepository incidentRepository, ConfigRepository configRepository, SmtpEmailService emailService, TelegramAlertService telegramService, Clock clock) {
        this.incidentRepository = incidentRepository;
        this.configRepository = configRepository;
        this.config = configRepository != null ? configRepository.loadAlertConfig() : new AlertConfig();
        this.emailService = emailService != null ? emailService : new SmtpEmailService();
        this.telegramService = telegramService != null ? telegramService : new TelegramAlertService();
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    public AlertService(IncidentRepository incidentRepository, ConfigRepository configRepository, Clock clock) {
        this(incidentRepository, configRepository, new SmtpEmailService(), new TelegramAlertService(), clock);
    }

    public AlertService(IncidentRepository incidentRepository, ConfigRepository configRepository) {
        this(incidentRepository, configRepository, Clock.systemDefaultZone());
    }

    public Clock getClock() {
        return clock;
    }

    public void addToastListener(BiConsumer<String, String> listener) {
        toastListeners.add(listener);
    }

    public void addIncidentListener(java.util.function.Consumer<IncidentLog> listener) {
        incidentListeners.add(listener);
    }

    private void fireIncidentUpdated(IncidentLog log) {
        synchronized (incidentListeners) {
            for (java.util.function.Consumer<IncidentLog> l : incidentListeners) {
                try {
                    l.accept(log);
                } catch (Exception ignored) {}
            }
        }
    }

    public void notifyOffline(ServerTarget target, String reason) {
        String title = "CẢNH BÁO: Mất kết nối!";
        String diagSummary = target.getLastDiagnosisSummary();
        String content = String.format("Máy chủ '%s' (%s:%d) đã bị OFFLINE! Lý do: %s%s",
                target.getName(), target.getHost(), target.getPort(), reason,
                (diagSummary != null && !diagSummary.isEmpty()) ? ("\n🔍 Chẩn đoán: " + diagSummary) : "");

        LoggerUtil.alert("[SỰ CỐ] " + content);

        // Mở hoặc cập nhật Problem với thông tin chẩn đoán (§E9.9)
        com.networkalert.repository.ProblemRepository.getInstance().createOrGetActiveProblem(
                target.getId(), target.getName(),
                (target.getAssignedAgent() != null ? target.getAssignedAgent() : "Server"),
                com.networkalert.model.ProblemSeverity.HIGH, title, reason, target.getLastDiagnosis()
        );

        // Lưu vào IncidentLog trong SQL Server
        IncidentLog log = new IncidentLog(
                target.getId(), target.getName(), target.getHost(), target.getPort(),
                target.getProtocol(), "OFFLINE", reason, clock
        );
        incidentRepository.saveIncident(log);

        // Phát âm thanh
        if (config.isSoundEnabled()) {
            SoundHelper.playAlertBeep();
        }

        // Kích hoạt Toast Popup
        if (config.isPopupEnabled()) {
            fireToast(title, content);
        }

        // Gửi Telegram Bot Alert (Tức thời cho SysAdmin/DevOps)
        if (config.isTelegramEnabled()) {
            String diagLine = (diagSummary != null && !diagSummary.isEmpty())
                    ? ("\n<b>Chẩn đoán:</b> " + diagSummary)
                    : "";
            String tgMsg = String.format(
                "🚨 <b>[CẢNH BÁO SỰ CỐ MẠNG]</b>\n\n" +
                "<b>Máy chủ:</b> %s\n" +
                "<b>Địa chỉ:</b> %s:%d (%s)\n" +
                "<b>Trạng thái:</b> 🔴 OFFLINE\n" +
                "<b>Thời gian:</b> %s\n" +
                "<b>Nguyên nhân:</b> %s%s",
                target.getName(), target.getHost(), target.getPort(), target.getProtocol(),
                LocalDateTime.now(clock).format(TIME_FMT), reason, diagLine
            );
            telegramService.sendTelegramAlertAsync(config, tgMsg);
        }

        // Gửi Email cảnh báo
        if (config.isEmailEnabled()) {
            String subject = "[ALERT] Mất kết nối máy chủ: " + target.getName();
            String body = "HỆ THỐNG CẢNH BÁO MẠNG (NCAS)\n\n" +
                          "Phát hiện máy chủ ngừng hoạt động:\n" +
                          "- Tên: " + target.getName() + "\n" +
                          "- Địa chỉ: " + target.getHost() + ":" + target.getPort() + "\n" +
                          "- Giao thức: " + target.getProtocol() + "\n" +
                          "- Thời điểm: " + LocalDateTime.now(clock).format(TIME_FMT) + "\n" +
                          "- Nguyên nhân: " + reason + "\n\n" +
                          "Vui lòng kiểm tra lại thiết bị hoặc dịch vụ!";
            emailService.sendAlertEmailAsync(config, subject, body);
        }

        fireIncidentUpdated(log);
    }

    public void notifyRecovered(ServerTarget target, long latencyMs) {
        String title = "THÔNG BÁO: Đã khôi phục kết nối!";
        String content = String.format("Máy chủ '%s' (%s:%d) đã ONLINE trở lại. Độ trễ: %d ms",
                target.getName(), target.getHost(), target.getPort(), latencyMs);

        LoggerUtil.info("[KHÔI PHỤC] " + content);

        incidentRepository.resolveActiveIncident(target.getId());
        com.networkalert.repository.ProblemRepository.getInstance().resolveActiveProblemsForTarget(target.getId(), "Mục tiêu đã khôi phục kết nối");

        IncidentLog log = new IncidentLog(
                target.getId(), target.getName(), target.getHost(), target.getPort(),
                target.getProtocol(), "RECOVERED", "Đã hoạt động trở lại. Latency: " + latencyMs + "ms", clock
        );
        incidentRepository.saveIncident(log);

        if (config.isSoundEnabled()) {
            SoundHelper.playRecoveryBeep();
        }

        if (config.isPopupEnabled()) {
            fireToast(title, content);
        }

        if (config.isTelegramEnabled()) {
            String tgMsg = String.format(
                "✅ <b>[KHÔI PHỤC KẾT NỐI MẠNG]</b>\n\n" +
                "<b>Máy chủ:</b> %s\n" +
                "<b>Địa chỉ:</b> %s:%d (%s)\n" +
                "<b>Trạng thái:</b> 🟢 ONLINE\n" +
                "<b>Độ trễ:</b> %d ms\n" +
                "<b>Thời gian:</b> %s",
                target.getName(), target.getHost(), target.getPort(), target.getProtocol(),
                latencyMs, LocalDateTime.now(clock).format(TIME_FMT)
            );
            telegramService.sendTelegramAlertAsync(config, tgMsg);
        }

        if (config.isEmailEnabled()) {
            String subject = "[RESOLVED] Máy chủ đã hoạt động trở lại: " + target.getName();
            String body = "HỆ THỐNG CẢNH BÁO MẠNG (NCAS)\n\n" +
                          "Máy chủ đã khôi phục kết nối thành công:\n" +
                          "- Tên: " + target.getName() + "\n" +
                          "- Địa chỉ: " + target.getHost() + ":" + target.getPort() + "\n" +
                          "- Độ trễ hiện tại: " + latencyMs + " ms\n" +
                          "- Thời điểm: " + LocalDateTime.now(clock).format(TIME_FMT);
            emailService.sendAlertEmailAsync(config, subject, body);
        }

        fireIncidentUpdated(log);
    }

    public void notifyHighLatency(ServerTarget target, long latencyMs) {
        long effectiveThreshold = (target.getWarningThresholdMs() > 0) ? target.getWarningThresholdMs() : config.getLatencyThresholdMs();
        String title = "CẢNH BÁO: Độ trễ cao!";
        String content = String.format("Máy chủ '%s' phản hồi chậm (%d ms > %d ms ngưỡng)",
                target.getName(), latencyMs, effectiveThreshold);

        LoggerUtil.warn("[CẢNH BÁO ĐỘ TRỄ] " + content);

        // Lưu vào IncidentLog trong SQL Server / Memory
        IncidentLog log = new IncidentLog(
                target.getId(), target.getName(), target.getHost(), target.getPort(),
                target.getProtocol(), "HIGH_LATENCY",
                String.format("Phản hồi chậm: %d ms (Vượt ngưỡng %d ms)", latencyMs, effectiveThreshold), clock
        );
        incidentRepository.saveIncident(log);

        // Phát âm thanh cảnh báo độ trễ
        if (config.isSoundEnabled()) {
            SoundHelper.playWarningBeep();
        }

        if (config.isPopupEnabled()) {
            fireToast(title, content);
        }

        if (config.isTelegramEnabled()) {
            String tgMsg = String.format(
                "⚠️ <b>[CẢNH BÁO ĐỘ TRỄ MẠNG CAO]</b>\n\n" +
                "<b>Máy chủ:</b> %s\n" +
                "<b>Địa chỉ:</b> %s:%d\n" +
                "<b>Độ trễ:</b> %d ms (Vượt ngưỡng %d ms)\n" +
                "<b>Thời gian:</b> %s",
                target.getName(), target.getHost(), target.getPort(),
                latencyMs, effectiveThreshold, LocalDateTime.now(clock).format(TIME_FMT)
            );
            telegramService.sendTelegramAlertAsync(config, tgMsg);
        }

        if (config.isEmailEnabled()) {
            String subject = "[WARNING] Máy chủ phản hồi chậm: " + target.getName();
            String body = "HỆ THỐNG CẢNH BÁO MẠNG (NCAS)\n\n" +
                          "Phát hiện độ trễ máy chủ tăng cao vượt ngưỡng:\n" +
                          "- Tên: " + target.getName() + "\n" +
                          "- Địa chỉ: " + target.getHost() + ":" + target.getPort() + "\n" +
                          "- Giao thức: " + target.getProtocol() + "\n" +
                          "- Độ trễ: " + latencyMs + " ms (Ngưỡng: " + effectiveThreshold + " ms)\n" +
                          "- Thời điểm: " + LocalDateTime.now(clock).format(TIME_FMT);
            emailService.sendAlertEmailAsync(config, subject, body);
        }

        fireIncidentUpdated(log);
    }

    public void notifyLatencyRecovered(ServerTarget target, long latencyMs) {
        String title = "THÔNG BÁO: Độ trễ đã ổn định!";
        String content = String.format("Máy chủ '%s' đã phản hồi bình thường (%d ms)", target.getName(), latencyMs);

        LoggerUtil.info("[ĐỘ TRỄ ỔN ĐỊNH] " + content);

        incidentRepository.resolveActiveIncident(target.getId());

        IncidentLog log = new IncidentLog(
                target.getId(), target.getName(), target.getHost(), target.getPort(),
                target.getProtocol(), "RECOVERED", "Độ trễ đã ổn định trở lại: " + latencyMs + " ms", clock
        );
        incidentRepository.saveIncident(log);

        if (config.isSoundEnabled()) {
            SoundHelper.playRecoveryBeep();
        }

        if (config.isPopupEnabled()) {
            fireToast(title, content);
        }

        fireIncidentUpdated(log);
    }

    public void notifyAgentConnected(String agentId, String host) {
        String title = "THÔNG BÁO: Agent trực tuyến!";
        String content = String.format("Agent trạm '%s' (%s) đã kết nối thành công vào máy chủ.", agentId, host != null ? host : "N/A");

        LoggerUtil.info("[AGENT KẾT NỐI] " + content);

        IncidentLog log = new IncidentLog(
                0, "Agent: " + agentId, host != null ? host : "0.0.0.0", 0,
                com.networkalert.model.ProtocolType.TCP, "AGENT_ONLINE", content, clock
        );
        incidentRepository.saveIncident(log);

        if (config.isPopupEnabled()) {
            fireToast(title, content);
        }

        if (config.isTelegramEnabled()) {
            String tgMsg = String.format(
                "🟢 <b>[AGENT KẾT NỐI TRỰC TUYẾN]</b>\n\n" +
                "<b>Agent ID:</b> %s\n" +
                "<b>Địa chỉ:</b> %s\n" +
                "<b>Thời gian:</b> %s",
                agentId, host, LocalDateTime.now(clock).format(TIME_FMT)
            );
            telegramService.sendTelegramAlertAsync(config, tgMsg);
        }

        fireIncidentUpdated(log);
    }

    public void notifyAgentDisconnected(String agentId, String host, String reason) {
        String title = "CẢNH BÁO: Agent ngắt kết nối!";
        String content = String.format("Agent trạm '%s' (%s) đã mất kết nối! Lý do: %s",
                agentId, host != null ? host : "N/A", reason);

        LoggerUtil.alert("[AGENT MẤT KẾT NỐI] " + content);

        IncidentLog log = new IncidentLog(
                0, "Agent: " + agentId, host != null ? host : "0.0.0.0", 0,
                com.networkalert.model.ProtocolType.TCP, "AGENT_OFFLINE", reason, clock
        );
        incidentRepository.saveIncident(log);

        if (config.isSoundEnabled()) {
            SoundHelper.playAlertBeep();
        }

        if (config.isPopupEnabled()) {
            fireToast(title, content);
        }

        if (config.isTelegramEnabled()) {
            String tgMsg = String.format(
                "🚨 <b>[AGENT MẤT KẾT NỐI]</b>\n\n" +
                "<b>Agent ID:</b> %s\n" +
                "<b>Địa chỉ:</b> %s\n" +
                "<b>Thời gian:</b> %s\n" +
                "<b>Nguyên nhân:</b> %s",
                agentId, host, LocalDateTime.now(clock).format(TIME_FMT), reason
            );
            telegramService.sendTelegramAlertAsync(config, tgMsg);
        }

        if (config.isEmailEnabled()) {
            String subject = "[ALERT] Mất kết nối Agent: " + agentId;
            String body = "HỆ THỐNG CẢNH BÁO MẠNG (NCAS)\n\n" +
                          "Phát hiện Agent trạm giám sát ngừng hoạt động:\n" +
                          "- Agent ID: " + agentId + "\n" +
                          "- Địa chỉ: " + host + "\n" +
                          "- Thời điểm: " + LocalDateTime.now(clock).format(TIME_FMT) + "\n" +
                          "- Lý do: " + reason + "\n\n" +
                          "Các mục tiêu giám sát của Agent này đang được tự động phân công lại!";
            emailService.sendAlertEmailAsync(config, subject, body);
        }

        fireIncidentUpdated(log);
    }

    private void fireToast(String title, String message) {
        synchronized (toastListeners) {
            for (BiConsumer<String, String> l : toastListeners) {
                try {
                    l.accept(title, message);
                } catch (Exception ignored) {}
            }
        }
    }

    public AlertConfig getConfig() {
        return config;
    }

    public void saveConfig(AlertConfig newConfig) {
        config.setPopupEnabled(newConfig.isPopupEnabled());
        config.setSoundEnabled(newConfig.isSoundEnabled());
        config.setEmailEnabled(newConfig.isEmailEnabled());
        config.setTelegramEnabled(newConfig.isTelegramEnabled());
        config.setTelegramBotToken(newConfig.getTelegramBotToken());
        config.setTelegramChatId(newConfig.getTelegramChatId());
        config.setMinimizeToTrayOnClose(newConfig.isMinimizeToTrayOnClose());
        config.setSmtpHost(newConfig.getSmtpHost());
        config.setSmtpPort(newConfig.getSmtpPort());
        config.setSmtpUsername(newConfig.getSmtpUsername());
        config.setSmtpPassword(newConfig.getSmtpPassword());
        config.setSmtpFrom(newConfig.getSmtpFrom());
        config.setSmtpTo(newConfig.getSmtpTo());
        config.setLatencyThresholdMs(newConfig.getLatencyThresholdMs());

        configRepository.saveAlertConfig(config);
    }

    public SmtpEmailService getEmailService() {
        return emailService;
    }

    public TelegramAlertService getTelegramService() {
        return telegramService;
    }
}

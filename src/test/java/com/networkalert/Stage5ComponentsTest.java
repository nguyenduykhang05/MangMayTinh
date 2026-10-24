package com.networkalert;

import com.networkalert.repository.ConfigRepository;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.service.AlertService;
import com.networkalert.ui.NotificationToast;
import com.networkalert.ui.SettingsDialog;
import com.networkalert.ui.components.LiveLogPanel;
import com.networkalert.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

public class Stage5ComponentsTest {

    @Test
    public void testLiveLogPanel() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.setup();
            LiveLogPanel logPanel = new LiveLogPanel(() -> {});
            assertNotNull(logPanel);

            // Test chèn các dòng log cấp độ khác nhau
            logPanel.appendRawLog("[INFO ] Máy chủ 'Web Server' hoạt động bình thường.");
            logPanel.appendRawLog("[WARN ] Độ trễ máy chủ 'Database' vượt ngưỡng 350ms.");
            logPanel.appendRawLog("[ERROR] Mất kết nối tới máy chủ 'Gateway 192.168.1.1'.");
            logPanel.appendRawLog("[INFO ] Máy chủ 'Database' đã phục hồi kết nối.");

            // Test xóa log
            logPanel.clearLogs();
        });
    }

    @Test
    public void testNotificationToast() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.setup();
            // Test phát thông báo Toast không gây lỗi
            assertDoesNotThrow(() -> {
                NotificationToast.show("CẢNH BÁO MẤT KẾT NỐI", "Máy chủ Google DNS không phản hồi", true);
                NotificationToast.show("ĐỘ TRỄ CAO", "Máy chủ Gateway trễ 520ms", false);
                NotificationToast.show("ĐÃ PHỤC HỒI", "Máy chủ Database đã kết nối lại", false);
            });
        });
    }

    @Test
    public void testSettingsDialog() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.setup();
            IncidentRepository incidentRepo = new IncidentRepository();
            ConfigRepository configRepo = new ConfigRepository();
            AlertService alertService = new AlertService(incidentRepo, configRepo);

            JFrame parent = new JFrame();
            SettingsDialog dialog = new SettingsDialog(parent, alertService);
            assertNotNull(dialog);
            assertEquals(com.networkalert.ui.theme.I18n.get("dialog.settings.title"), dialog.getTitle());
            dialog.dispose();
            parent.dispose();
        });
    }

    @Test
    public void testServerTargetDialog() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.setup();
            JFrame parent = new JFrame();

            // Test 1: Tạo mới máy chủ
            com.networkalert.ui.components.ServerTargetDialog addDialog =
                    new com.networkalert.ui.components.ServerTargetDialog(parent, null);
            assertNotNull(addDialog);
            assertEquals(com.networkalert.ui.theme.I18n.get("dialog.target.title_add"), addDialog.getTitle());
            addDialog.dispose();

            // Test 2: Chỉnh sửa máy chủ ICMP (trường hợp trong ảnh người dùng)
            com.networkalert.model.ServerTarget icmpTarget = new com.networkalert.model.ServerTarget(
                    1, "Wi-Fi Router / Default Gateway", "10.12.0.254", 0,
                    com.networkalert.model.ProtocolType.ICMP, 5, 1500, true
            );
            icmpTarget.setWarningThresholdMs(300);

            com.networkalert.ui.components.ServerTargetDialog editDialog =
                    new com.networkalert.ui.components.ServerTargetDialog(parent, icmpTarget);
            assertNotNull(editDialog);
            assertEquals(com.networkalert.ui.theme.I18n.get("dialog.target.title_edit"), editDialog.getTitle());

            com.networkalert.model.ServerTarget result = editDialog.getTarget();
            assertEquals("Wi-Fi Router / Default Gateway", result.getName());
            assertEquals("10.12.0.254", result.getHost());
            assertEquals(com.networkalert.model.ProtocolType.ICMP, result.getProtocol());
            assertEquals(0, result.getPort());

            editDialog.dispose();
            parent.dispose();
        });
    }

    @Test
    public void testHighLatencyIncidentLifecycle() {
        IncidentRepository incidentRepo = new IncidentRepository();
        incidentRepo.clearAllIncidents();
        ConfigRepository configRepo = new ConfigRepository();
        AlertService alertService = new AlertService(incidentRepo, configRepo);

        com.networkalert.model.ServerTarget googleTarget = new com.networkalert.model.ServerTarget(
                7, "Google Web HTTPS", "google.com", 443,
                com.networkalert.model.ProtocolType.HTTPS, 5, 1500, true
        );

        // 1. Kích hoạt cảnh báo độ trễ cao (546 ms)
        alertService.notifyHighLatency(googleTarget, 546);

        var incidents = incidentRepo.getRecentIncidents(10);
        assertEquals(1, incidents.size(), "Phải có 1 sự cố độ trễ cao được ghi nhận");
        assertEquals("HIGH_LATENCY", incidents.get(0).getEventType());
        assertNull(incidents.get(0).getDurationSeconds(), "Sự cố đang diễn ra thì durationSeconds phải là null");

        // 2. Kiểm tra IncidentHistoryPanel đếm đúng 1 sự cố active
        if (!GraphicsEnvironment.isHeadless()) {
            com.networkalert.ui.components.IncidentHistoryPanel panel =
                    new com.networkalert.ui.components.IncidentHistoryPanel(incidentRepo);
            panel.refreshData();
            assertEquals(1, panel.getActiveIncidentsCount());
            assertFalse(panel.hasActiveOfflineIncidents(), "Đây là sự cố trễ cao, không phải OFFLINE");
        }

        // 3. Khôi phục độ trễ bình thường
        alertService.notifyLatencyRecovered(googleTarget, 45);
        var updatedIncidents = incidentRepo.getRecentIncidents(10);
        assertEquals(2, updatedIncidents.size(), "Phải có 2 bản ghi: 1 HIGH_LATENCY đã đóng và 1 RECOVERED");
        assertEquals("RECOVERED", updatedIncidents.get(0).getEventType());
        assertNotNull(updatedIncidents.get(1).getDurationSeconds(), "Sự cố trước đó phải được tính thời lượng");

        if (!GraphicsEnvironment.isHeadless()) {
            com.networkalert.ui.components.IncidentHistoryPanel panel =
                    new com.networkalert.ui.components.IncidentHistoryPanel(incidentRepo);
            panel.refreshData();
            assertEquals(0, panel.getActiveIncidentsCount(), "Sau khi phục hồi, số sự cố active phải là 0");
        }
    }
}

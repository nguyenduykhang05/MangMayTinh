package com.networkalert.ui.components;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;
import com.networkalert.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

public class AgentClusterPanelTest {

    @Test
    public void testAssignedAgentOnTarget() {
        ServerTarget target = new ServerTarget(
                1, "Test Gateway", "192.168.1.1", 80,
                ProtocolType.TCP, 5, 1000, true
        );
        assertNull(target.getAssignedAgent(), "Ban đầu target chưa được gán cho Agent nào");

        target.setAssignedAgent("agent-danang-01");
        assertEquals("agent-danang-01", target.getAssignedAgent());

        target.setAssignedAgent(null);
        assertNull(target.getAssignedAgent());
    }

    @Test
    public void testAgentClusterPanelInitializationAndTable() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.setup();
            AgentClusterPanel panel = new AgentClusterPanel(null);

            assertNotNull(panel);
            assertNotNull(panel.getAgentTable());
            assertEquals(9, panel.getAgentTable().getColumnCount(), "Bảng Agent phải có 9 cột thông tin");

            // Kiểm tra trạng thái ban đầu của máy chủ
            assertFalse(panel.isServerRunning(), "Ban đầu embedded server chưa chạy");
            assertEquals("0 Agent trực tuyến", panel.getLblAgentCountBadge().getText());

            // Thử thêm hàng mẫu vào bảng
            panel.getTableModel().addRow(new Object[]{
                    1, "agent-hcm-01", "192.168.1.55", "DESKTOP-HCM", "Windows 11",
                    "5 mục tiêu", "12.5%", "4096 MB", "🟢 Vừa xong"
            });

            assertEquals(1, panel.getAgentTable().getRowCount(), "Bảng phải hiển thị 1 Agent");
            assertEquals("agent-hcm-01", panel.getAgentTable().getValueAt(0, 1));
            assertEquals("192.168.1.55", panel.getAgentTable().getValueAt(0, 2));

            // Xóa dữ liệu bảng
            panel.getTableModel().setRowCount(0);
            assertEquals(0, panel.getAgentTable().getRowCount(), "Bảng phải rỗng sau khi xóa");
        });
    }

    @Test
    public void testServerTableModelHasAgentColumn() {
        ServerTableModel model = new ServerTableModel();
        assertEquals(10, model.getColumnCount(), "ServerTableModel phải có 10 cột chuẩn theo giao diện giám sát");
        assertEquals("Trạm đo", model.getColumnName(4), "Cột thứ 4 phải là 'Trạm đo'");

        ServerTarget target = new ServerTarget(
                1, "Test Gateway", "192.168.1.1", 80,
                ProtocolType.TCP, 5, 1000, true
        );
        model.addTarget(target);

        // Ban đầu chưa gán agent
        assertEquals("", model.getValueAt(0, 4));

        // Khi gán agent
        target.setAssignedAgent("agent-hanoi-01");
        assertEquals("agent-hanoi-01", model.getValueAt(0, 4));
    }
}

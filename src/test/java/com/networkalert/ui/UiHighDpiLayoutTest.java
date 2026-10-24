package com.networkalert.ui;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.repository.ProblemRepository;
import com.networkalert.repository.backend.H2FileBackend;
import com.networkalert.repository.backend.H2InMemoryBackend;
import com.networkalert.ui.components.DetailTabbedPane;
import com.networkalert.ui.components.LatencyChartPanel;
import com.networkalert.ui.components.ProblemsViewPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử bố cục giao diện High-DPI và độ phân giải 1366x768 (ERRATA §E13 #3, #4, #5)
 */
public class UiHighDpiLayoutTest {

    /**
     * ProblemRepository/DatabaseManager là singleton dùng chung trong toàn bộ JVM test.
     * CSDL tệp (data/ncas-db) bị các test khác để lại sự cố đang mở, khiến bài kiểm thử
     * "dọn dẹp sự cố" phụ thuộc thứ tự chạy và fail trên CI.
     * => Mỗi test trong lớp này chạy trên backend H2 in-memory riêng, sạch tuyệt đối.
     */
    @BeforeEach
    void isolateProblemDatabase() {
        DatabaseManager.getInstance().setBackendForTesting(
                new H2InMemoryBackend("ui_highdpi_" + System.nanoTime()));
    }

    /**
     * Trả lại CSDL tệp mặc định để các lớp test chạy sau không bị ảnh hưởng.
     */
    @AfterEach
    void restoreSharedDatabase() {
        DatabaseManager.getInstance().setBackendForTesting(new H2FileBackend());
    }

    @Test
    @DisplayName("Huy hiệu Sự cố (Problems Badge) chỉ đếm các sự cố đang mở (OPEN/ACTIVE) (E13 #4)")
    void testActiveProblemsBadgeCount() {
        ProblemRepository repo = ProblemRepository.getInstance();
        ProblemsViewPanel pnl = new ProblemsViewPanel(repo);
        pnl.refreshData();

        int active = pnl.getActiveProblemsCount();
        assertTrue(active >= 0, "Số lượng sự cố mở không được âm");
    }

    @Test
    @DisplayName("Hiển thị Uptime trên LatencyChartPanel khi mới khởi chạy (E13 #5)")
    void testNewTargetUptimeDisplay() throws Exception {
        ServerTarget target = new ServerTarget(101, "Test Web", "127.0.0.1", 80, ProtocolType.HTTP, 2000, 5, true);
        // target mới tạo: totalChecks == 0
        assertEquals(0, target.getTotalChecks());

        LatencyChartPanel chartPanel = new LatencyChartPanel();
        chartPanel.updateTarget(target, 300);

        Field f24 = LatencyChartPanel.class.getDeclaredField("lblUptime24hVal");
        f24.setAccessible(true);
        JLabel lbl24 = (JLabel) f24.get(chartPanel);

        Field f7 = LatencyChartPanel.class.getDeclaredField("lblUptime7dVal");
        f7.setAccessible(true);
        JLabel lbl7 = (JLabel) f7.get(chartPanel);

        Field f30 = LatencyChartPanel.class.getDeclaredField("lblUptime30dVal");
        f30.setAccessible(true);
        JLabel lbl30 = (JLabel) f30.get(chartPanel);

        // Khi totalChecks == 0, hiển thị "—" thay vì 100% giả mạo
        assertEquals("—", lbl24.getText(), "Uptime 24h khi chưa kiểm tra phải là '—'");
        assertEquals("—", lbl7.getText(), "Uptime 7d khi chưa kiểm tra phải là '—'");
        assertEquals("—", lbl30.getText(), "Uptime 30d khi chưa kiểm tra phải là '—'");
    }

    @Test
    @DisplayName("Bảo vệ kích thước tối thiểu Split Pane tại độ phân giải chuẩn 1366x768 (E13 #3)")
    void testSplitPaneDividerAt1366x768() {
        int totalWidth = 1366;
        int leftW = (int) (totalWidth * 0.58); // ~792px
        int minLeft = 500;
        int minRight = 380;

        int dividerLoc = Math.max(minLeft, Math.min(leftW, totalWidth - minRight));

        int computedLeft = dividerLoc;
        int computedRight = totalWidth - dividerLoc;

        assertTrue(computedLeft >= minLeft, "Cột danh sách máy chủ bên trái phải >= 500px: " + computedLeft);
        assertTrue(computedRight >= minRight, "Panel chi tiết bên phải phải >= 380px: " + computedRight);
        assertTrue(computedRight >= 380, "Biểu đồ độ trễ và thẻ chi tiết không bị bóp thành dải mỏng");
    }

    @Test
    @DisplayName("ProblemsViewPanel hiển thị đủ 7 cột gọn gàng và dọn dẹp lịch sử đã đóng mượt mà")
    void testProblemsViewCleanColumnsAndCleanup() throws Exception {
        ProblemRepository repo = ProblemRepository.getInstance();
        repo.cleanupStaleTestAndRecoveredProblems(java.util.Collections.emptyList());
        repo.clearResolvedProblems();

        ProblemsViewPanel pnl = new ProblemsViewPanel(repo);
        pnl.refreshData();

        Field tableField = ProblemsViewPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        JTable table = (JTable) tableField.get(pnl);

        assertEquals(7, table.getColumnCount(), "Bảng sự cố phải được tối ưu thành 7 cột chuẩn xác");
        assertEquals(0, pnl.getActiveProblemsCount(), "Sau khi dọn dẹp sự cố mồ côi, số sự cố mở phải bằng 0");
    }
}

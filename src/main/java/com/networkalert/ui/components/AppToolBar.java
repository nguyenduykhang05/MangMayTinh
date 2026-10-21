package com.networkalert.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;

/**
 * Thanh công cụ Toolbar chuyên nghiệp (cao 42 px, các nút bo góc đều đặn, phân nhóm rõ ràng)
 */
public class AppToolBar extends JPanel {
    private final JButton btnAddServer;
    private final JButton btnCheckNow;
    private final JButton btnPauseResume;
    private final JButton btnSettings;
    private final JButton btnExportCsv;
    private final JButton btnWebDashboard;
    private final JButton btnAgentCluster;

    private boolean isPaused = false;
    private boolean isCheckingNow = false;

    public AppToolBar(Runnable onAddServer, Runnable onCheckNow, Runnable onTogglePause,
                      Runnable onSettings, Runnable onExportCsv) {
        this(onAddServer, onCheckNow, onTogglePause, onSettings, onExportCsv, null);
    }

    public AppToolBar(Runnable onAddServer, Runnable onCheckNow, Runnable onTogglePause,
                      Runnable onSettings, Runnable onExportCsv, Runnable onWebDashboard) {
        this(onAddServer, onCheckNow, onTogglePause, onSettings, onExportCsv, onWebDashboard, null);
    }

    public AppToolBar(Runnable onAddServer, Runnable onCheckNow, Runnable onTogglePause,
                      Runnable onSettings, Runnable onExportCsv, Runnable onWebDashboard,
                      Runnable onAgentCluster) {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(800, UIScale.scale(42)));
        setOpaque(false);

        JPanel leftFlow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), UIScale.scale(4)));
        leftFlow.setOpaque(false);

        JPanel rightFlow = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), UIScale.scale(4)));
        rightFlow.setOpaque(false);

        // Nút chính: Thêm máy chủ (Primary Button nổi bật)
        String addText = I18n.get("toolbar.add_server");
        if (addText == null || addText.trim().isEmpty() || addText.equals("toolbar.add_server")) {
            addText = "Thêm máy chủ";
        }
        btnAddServer = new JButton(addText, ThemeManager.getIcon("plus", 16));
        btnAddServer.putClientProperty("FlatLaf.style", "background: #2563EB; foreground: #FFFFFF; focusWidth: 0; font: bold; arc: 8");
        btnAddServer.setMargin(new Insets(UIScale.scale(5), UIScale.scale(14), UIScale.scale(5), UIScale.scale(14)));
        btnAddServer.setToolTipText("Thêm máy chủ mới (Ctrl+N)");
        btnAddServer.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnAddServer.addActionListener(e -> {
            if (onAddServer != null) onAddServer.run();
        });

        // Nút phụ: Kiểm tra ngay
        String checkText = I18n.get("toolbar.check_now");
        if (checkText == null || checkText.trim().isEmpty() || checkText.equals("toolbar.check_now")) {
            checkText = "Kiểm tra";
        }
        btnCheckNow = new JButton(checkText, ThemeManager.getIcon("zap", 16));
        btnCheckNow.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnCheckNow.setMargin(new Insets(UIScale.scale(5), UIScale.scale(10), UIScale.scale(5), UIScale.scale(10)));
        btnCheckNow.putClientProperty("FlatLaf.style", "arc: 8; focusWidth: 0");
        btnCheckNow.setToolTipText("Kiểm tra ngay máy chủ đang chọn (F5)");
        btnCheckNow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCheckNow.addActionListener(e -> {
            if (onCheckNow != null && !isCheckingNow) {
                onCheckNow.run();
            }
        });

        // Nút phụ: Tạm dừng / Tiếp tục
        String pauseText = I18n.get("toolbar.pause");
        if (pauseText == null || pauseText.trim().isEmpty() || pauseText.equals("toolbar.pause")) {
            pauseText = "Tạm dừng";
        }
        btnPauseResume = new JButton(pauseText, ThemeManager.getIcon("pause", 16));
        btnPauseResume.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnPauseResume.setMargin(new Insets(UIScale.scale(5), UIScale.scale(10), UIScale.scale(5), UIScale.scale(10)));
        btnPauseResume.putClientProperty("FlatLaf.style", "arc: 8; focusWidth: 0");
        btnPauseResume.setToolTipText("Tạm dừng hoặc tiếp tục giám sát (Ctrl+P)");
        btnPauseResume.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnPauseResume.addActionListener(e -> {
            if (onTogglePause != null) onTogglePause.run();
        });

        // Nút công cụ: Cấu hình
        String settingsText = I18n.get("toolbar.settings");
        if (settingsText == null || settingsText.trim().isEmpty() || settingsText.equals("toolbar.settings")) {
            settingsText = "Cấu hình";
        }
        btnSettings = new JButton(settingsText, ThemeManager.getIcon("sliders", 16));
        btnSettings.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnSettings.setMargin(new Insets(UIScale.scale(5), UIScale.scale(10), UIScale.scale(5), UIScale.scale(10)));
        btnSettings.putClientProperty("FlatLaf.style", "arc: 8; focusWidth: 0");
        btnSettings.setToolTipText("Cấu hình cảnh báo, email & CSDL (Ctrl+,)");
        btnSettings.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnSettings.addActionListener(e -> {
            if (onSettings != null) onSettings.run();
        });

        // Nút công cụ: Xuất CSV
        String exportText = I18n.get("toolbar.export_csv");
        if (exportText == null || exportText.trim().isEmpty() || exportText.equals("toolbar.export_csv")) {
            exportText = "Xuất CSV";
        }
        btnExportCsv = new JButton(exportText, ThemeManager.getIcon("download", 16));
        btnExportCsv.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnExportCsv.setMargin(new Insets(UIScale.scale(5), UIScale.scale(10), UIScale.scale(5), UIScale.scale(10)));
        btnExportCsv.putClientProperty("FlatLaf.style", "arc: 8; focusWidth: 0");
        btnExportCsv.setToolTipText("Xuất báo cáo máy chủ và sự cố ra CSV (Ctrl+E)");
        btnExportCsv.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnExportCsv.addActionListener(e -> {
            if (onExportCsv != null) onExportCsv.run();
        });

        // Nút công cụ: Web Dashboard (NOC Mạng Thật)
        btnWebDashboard = new JButton("Web NOC", ThemeManager.getIcon("globe", 16));
        btnWebDashboard.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnWebDashboard.setMargin(new Insets(UIScale.scale(5), UIScale.scale(10), UIScale.scale(5), UIScale.scale(10)));
        btnWebDashboard.putClientProperty("FlatLaf.style", "arc: 8; focusWidth: 0");
        btnWebDashboard.setToolTipText("Mở Trung Tâm Giám Sát Web NOC trên trình duyệt (http://localhost:8080)");
        btnWebDashboard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnWebDashboard.addActionListener(e -> {
            if (onWebDashboard != null) onWebDashboard.run();
        });

        // Nút công cụ: Cụm Agent phân tán
        btnAgentCluster = new JButton("Trạm Agent", ThemeManager.getIcon("radio", 16));
        btnAgentCluster.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnAgentCluster.setMargin(new Insets(UIScale.scale(5), UIScale.scale(10), UIScale.scale(5), UIScale.scale(10)));
        btnAgentCluster.putClientProperty("FlatLaf.style", "arc: 8; focusWidth: 0");
        btnAgentCluster.setToolTipText("Mở bảng quản lý cụm trạm đo phân tán NCAS Agent (F4)");
        btnAgentCluster.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnAgentCluster.addActionListener(e -> {
            if (onAgentCluster != null) onAgentCluster.run();
        });

        leftFlow.add(btnAddServer);
        leftFlow.add(btnCheckNow);
        leftFlow.add(btnPauseResume);
        leftFlow.add(createDivider());
        leftFlow.add(btnSettings);
        leftFlow.add(btnExportCsv);

        rightFlow.add(btnWebDashboard);
        rightFlow.add(btnAgentCluster);

        add(leftFlow, BorderLayout.WEST);
        add(rightFlow, BorderLayout.EAST);
    }

    private JComponent createDivider() {
        return new JComponent() {
            {
                setPreferredSize(new Dimension(UIScale.scale(8), UIScale.scale(22)));
            }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(ThemeManager.border());
                int x = getWidth() / 2;
                g2.drawLine(x, UIScale.scale(2), x, getHeight() - UIScale.scale(2));
                g2.dispose();
            }
        };
    }

    public void setMonitoringPaused(boolean paused) {
        this.isPaused = paused;
        if (paused) {
            btnPauseResume.setText(I18n.get("toolbar.resume", "Tiếp tục"));
            btnPauseResume.setIcon(ThemeManager.getIcon("play", 16));
            btnPauseResume.setForeground(ThemeManager.statusOnline());
        } else {
            btnPauseResume.setText(I18n.get("toolbar.pause", "Tạm dừng"));
            btnPauseResume.setIcon(ThemeManager.getIcon("pause", 16));
            btnPauseResume.setForeground(ThemeManager.textPrimary());
        }
    }

    public void setCheckNowRunning(boolean running) {
        this.isCheckingNow = running;
        btnCheckNow.setEnabled(!running);
        if (running) {
            btnCheckNow.setText("Đang kiểm tra...");
            btnCheckNow.setIcon(ThemeManager.getIcon("refresh-cw", 16));
        } else {
            btnCheckNow.setText(I18n.get("toolbar.check_now", "Kiểm tra ngay"));
            btnCheckNow.setIcon(ThemeManager.getIcon("zap", 16));
        }
    }
}

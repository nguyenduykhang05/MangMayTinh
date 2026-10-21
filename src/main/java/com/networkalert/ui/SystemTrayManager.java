package com.networkalert.ui;

import com.networkalert.service.MonitorService;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * Quản lý tích hợp Khay hệ thống Windows (System Tray) cho phép hệ thống chạy ngầm 24/7
 */
public class SystemTrayManager {
    private final JFrame mainFrame;
    private final MonitorService monitorService;
    private TrayIcon trayIcon;
    private boolean isTraySupported = false;

    public SystemTrayManager(JFrame mainFrame, MonitorService monitorService) {
        this.mainFrame = mainFrame;
        this.monitorService = monitorService;
        initTray();
    }

    private void initTray() {
        if (!SystemTray.isSupported()) {
            LoggerUtil.warn("Hệ điều hành không hỗ trợ System Tray.");
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image iconImage = createTrayIconImage(new Color(46, 204, 113));

            PopupMenu popup = new PopupMenu();

            MenuItem itemOpen = new MenuItem("Mở Giao Diện Giám Sát");
            itemOpen.addActionListener(e -> restoreWindow());
            popup.add(itemOpen);

            MenuItem itemToggle = new MenuItem("Tạm Dừng / Tiếp Tục Giám Sát");
            itemToggle.addActionListener(e -> {
                if (monitorService.isRunning()) {
                    monitorService.stopAll();
                } else {
                    monitorService.startAll();
                }
            });
            popup.add(itemToggle);

            popup.addSeparator();

            MenuItem itemExit = new MenuItem("Thoát Hoàn Toàn Ứng Dụng");
            itemExit.addActionListener(e -> {
                int confirm = JOptionPane.showConfirmDialog(mainFrame,
                        "Bạn có chắc muốn tắt hoàn toàn hệ thống giám sát?",
                        "Xác nhận thoát", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    tray.remove(trayIcon);
                    System.exit(0);
                }
            });
            popup.add(itemExit);

            trayIcon = new TrayIcon(iconImage, "Network Alert System (NCAS) - Đang chạy ngầm", popup);
            trayIcon.setImageAutoSize(true);

            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        restoreWindow();
                    }
                }
            });

            tray.add(trayIcon);
            isTraySupported = true;
            LoggerUtil.info("Đã tích hợp thành công System Tray (Khay hệ thống Windows 24/7).");
        } catch (Exception e) {
            LoggerUtil.error("Lỗi khởi tạo System Tray: " + e.getMessage());
        }
    }

    public void restoreWindow() {
        SwingUtilities.invokeLater(() -> {
            mainFrame.setVisible(true);
            mainFrame.setExtendedState(JFrame.NORMAL);
            mainFrame.toFront();
            mainFrame.requestFocus();
        });
    }

    public void minimizeToTray() {
        if (isTraySupported) {
            mainFrame.setVisible(false);
            showNotification("NCAS đang chạy ngầm", "Hệ thống vẫn đang giám sát mạng liên tục 24/7 từ khay hệ thống.", TrayIcon.MessageType.INFO);
        } else {
            mainFrame.setExtendedState(JFrame.ICONIFIED);
        }
    }

    public void showNotification(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, type);
        }
    }

    public void updateStatusIcon(boolean hasOfflineServers) {
        if (trayIcon != null) {
            Color color = hasOfflineServers ? new Color(231, 76, 60) : new Color(46, 204, 113);
            trayIcon.setImage(createTrayIconImage(color));
            trayIcon.setToolTip("NCAS: " + (hasOfflineServers ? "CÓ SỰ CỐ MẤT KẾT NỐI!" : "Tất cả máy chủ hoạt động tốt"));
        }
    }

    private Image createTrayIconImage(Color color) {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Nền tròn
        g2.setColor(color);
        g2.fillOval(1, 1, 14, 14);

        // Viền trắng
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawOval(1, 1, 13, 13);

        // Biểu tượng sóng mạng
        g2.setColor(Color.WHITE);
        g2.fillOval(6, 6, 4, 4);

        g2.dispose();
        return img;
    }

    public boolean isTraySupported() {
        return isTraySupported;
    }
}

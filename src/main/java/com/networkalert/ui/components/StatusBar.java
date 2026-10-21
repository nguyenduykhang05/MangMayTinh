package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.RealNetworkInfo;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.repository.backend.DatabaseBackendType;
import com.networkalert.service.RealNetworkService;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Status bar (cao 26 px, các mục ngăn nhau bởi vạch dọc 1 px)
 */
public class StatusBar extends JPanel {
    private final JLabel lblServers;
    private final JLabel lblLastCheck;
    private final JLabel lblLatencyThreshold;
    private final JLabel lblRealNet;
    private final JPanel pnlDbStatus;
    private final JLabel lblDbDot;
    private final JLabel lblDbText;
    private final JLabel lblWeb;
    private final JLabel lblMode;

    private boolean dbConnected = true;

    public StatusBar(Runnable onOpenSettings) {
        this(onOpenSettings, null);
    }

    public StatusBar(Runnable onOpenSettings, Runnable onOpenWeb) {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(800, 26));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        lblServers = createItemLabel(I18n.get("statusbar.servers", 0));
        lblLastCheck = createItemLabel(I18n.get("statusbar.last_check", "—"));
        lblLatencyThreshold = createItemLabel(I18n.get("statusbar.latency_threshold", 300));

        // DB Status Item
        pnlDbStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pnlDbStatus.setOpaque(false);
        pnlDbStatus.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pnlDbStatus.setToolTipText("Trạng thái CSDL (Bấm để mở Cấu hình CSDL)");

        lblDbDot = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (!dbConnected) {
                    g2.setColor(ThemeManager.statusOffline());
                } else {
                    DatabaseBackendType type = DatabaseManager.getInstance().getBackendType();
                    if (type == DatabaseBackendType.MSSQL) {
                        g2.setColor(ThemeManager.statusOnline());
                    } else if (type == DatabaseBackendType.H2_FILE) {
                        g2.setColor(new Color(2, 132, 199)); // Xanh dương H2 (#0284c7) theo ERRATA §E8.1
                    } else {
                        g2.setColor(ThemeManager.statusOffline()); // Đỏ nếu In-Memory
                    }
                }
                int s = UIScale.scale(7);
                g2.fillOval(0, (getHeight() - s) / 2, s, s);
                g2.dispose();
            }
        };
        lblDbDot.setPreferredSize(new Dimension(UIScale.scale(8), UIScale.scale(16)));

        lblDbText = createItemLabel(I18n.get("statusbar.db_connected"));
        pnlDbStatus.add(lblDbDot);
        pnlDbStatus.add(lblDbText);

        pnlDbStatus.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (onOpenSettings != null) onOpenSettings.run();
            }
        });

        lblWeb = createItemLabel("Web Dashboard: 8080");
        lblWeb.setIcon(ThemeManager.getIcon("globe", 14));
        lblWeb.setForeground(ThemeManager.accent());
        lblWeb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblWeb.setToolTipText("Bấm để mở Web Dashboard trên trình duyệt");
        lblWeb.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (onOpenWeb != null) onOpenWeb.run();
            }
        });

        lblMode = createItemLabel("Chế độ độc lập, kiểm tra mỗi 3 giây");

        lblRealNet = createItemLabel("LAN: 127.0.0.1");
        lblRealNet.setIcon(ThemeManager.getIcon("wifi", 13));
        refreshRealNetworkBadge();

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        leftPanel.setOpaque(false);
        leftPanel.add(lblServers);
        leftPanel.add(createDivider());
        leftPanel.add(lblLastCheck);
        leftPanel.add(createDivider());
        leftPanel.add(lblLatencyThreshold);
        leftPanel.add(createDivider());
        leftPanel.add(lblRealNet);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 3));
        rightPanel.setOpaque(false);
        rightPanel.add(pnlDbStatus);
        rightPanel.add(createDivider());
        rightPanel.add(lblWeb);
        rightPanel.add(createDivider());
        rightPanel.add(lblMode);

        add(leftPanel, BorderLayout.WEST);
        add(rightPanel, BorderLayout.EAST);
    }

    private void refreshRealNetworkBadge() {
        RealNetworkInfo info = RealNetworkService.getInstance().getNetworkInfo();
        if (info != null && info.getLocalIp() != null) {
            lblRealNet.setText("LAN: " + info.getLocalIp());
            lblRealNet.setToolTipText(String.format(
                    "Card mạng thật: %s (%s) | IP LAN: %s | MAC: %s | Gateway: %s | Public IP: %s",
                    info.getInterfaceType(), info.getInterfaceName(),
                    info.getLocalIp(), info.getMacAddress(),
                    info.getGatewayIp(), info.getPublicIp()
            ));
        }
    }

    private JLabel createItemLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lbl.setForeground(ThemeManager.textSecondary());
        return lbl;
    }

    private JComponent createDivider() {
        return new JComponent() {
            {
                setPreferredSize(new Dimension(UIScale.scale(8), UIScale.scale(14)));
            }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(ThemeManager.border());
                int x = getWidth() / 2;
                g2.drawLine(x, 1, x, getHeight() - 1);
                g2.dispose();
            }
        };
    }

    public void updateStatus(int serverCount, String lastCheckTime, int thresholdMs, boolean isDbOk) {
        lblServers.setText(I18n.get("statusbar.servers", serverCount));
        lblLastCheck.setText(I18n.get("statusbar.last_check", lastCheckTime != null ? lastCheckTime : "—"));
        lblLatencyThreshold.setText(I18n.get("statusbar.latency_threshold", thresholdMs));
        refreshRealNetworkBadge();

        this.dbConnected = isDbOk;
        String backendStatus = DatabaseManager.getInstance().getBackendStatusText();
        lblDbText.setText("CSDL: " + backendStatus);
        int queueDepth = DatabaseManager.getInstance().getPendingWriteCount();
        String tip = "Trạng thái CSDL: " + backendStatus;
        if (queueDepth > 0) {
            tip += String.format(" (Hàng đợi ghi: %d/%d)", queueDepth, DatabaseManager.MAX_WRITE_QUEUE_CAPACITY);
        }
        pnlDbStatus.setToolTipText(tip + " (Bấm để mở Cấu hình CSDL)");
        lblDbDot.repaint();
    }

    public void setWebPort(int port) {
        if (lblWeb != null) {
            lblWeb.setText("Web Dashboard: " + port);
        }
    }

    public void setModeText(String mode) {
        if (lblMode != null && mode != null) {
            lblMode.setText(mode);
        }
    }
}

package com.networkalert.ui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Header chính của ứng dụng: Logo, Chip trạng thái giám sát (nhịp 1.2s), Nút Sáng/Tối
 */
public class HeaderPanel extends JPanel {
    private final JLabel lblTitle;
    private final JPanel pnlStatusChip;
    private final JLabel lblStatusText;
    private final JButton btnThemeToggle;

    private boolean isMonitoring = true;
    private int intervalSeconds = 5;
    private boolean serverRunning = false;
    private int onlineAgents = 0;
    private int lostAgents = 0;
    private Runnable onToggleMonitoringCallback;

    private final Timer pulseTimer;
    private float pulseAlpha = 1.0f;
    private boolean pulseDecreasing = true;

    public HeaderPanel(Runnable onToggleMonitoring, Runnable onThemeToggle) {
        this.onToggleMonitoringCallback = onToggleMonitoring;

        setLayout(new BorderLayout(12, 0));
        setPreferredSize(new Dimension(800, 52));
        setOpaque(false);

        // --- BÊN TRÁI: Logo + Tên ứng dụng ---
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        leftPanel.setOpaque(false);

        // Logo bo góc 28x28
        JPanel logoBox = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ThemeManager.accentFill());
                int arc = UIScale.scale(8);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        logoBox.setOpaque(false);
        int logoSize = UIScale.scale(28);
        logoBox.setPreferredSize(new Dimension(logoSize, logoSize));

        JLabel logoIcon = new JLabel(ThemeManager.getIcon("activity", 18));
        logoBox.add(logoIcon);

        lblTitle = new JLabel(I18n.get("app.title"));
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 15));
        lblTitle.setForeground(ThemeManager.textPrimary());

        JLabel lblSub = new JLabel("/ Bảng điều khiển");
        lblSub.setFont(ThemeManager.sans(Font.PLAIN, 13));
        lblSub.setForeground(ThemeManager.textMuted());

        leftPanel.add(logoBox);
        leftPanel.add(lblTitle);
        leftPanel.add(lblSub);

        // --- Ở GIỮA: Chip trạng thái giám sát (có nhịp đập 1.2s) ---
        pnlStatusChip = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 5)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int arc = getHeight();
                g2.setColor(ThemeManager.bgSurface());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

                g2.setColor(ThemeManager.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);

                // Vẽ chấm tròn trạng thái (nhấp nháy nếu đang giám sát)
                int dotSize = UIScale.scale(8);
                int dotX = UIScale.scale(12);
                int dotY = (getHeight() - dotSize) / 2;

                if (isMonitoring) {
                    Composite old = g2.getComposite();
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pulseAlpha));
                    if (lostAgents > 0) {
                        g2.setColor(ThemeManager.statusWarning());
                    } else {
                        g2.setColor(ThemeManager.statusOnline());
                    }
                    g2.fillOval(dotX, dotY, dotSize, dotSize);
                    g2.setComposite(old);
                } else {
                    g2.setColor(ThemeManager.statusWarning());
                    g2.fillOval(dotX, dotY, dotSize, dotSize);
                }

                g2.dispose();
                super.paintComponent(g);
            }
        };
        pnlStatusChip.setOpaque(false);
        pnlStatusChip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pnlStatusChip.setBorder(BorderFactory.createEmptyBorder(2, 24, 2, 14));

        lblStatusText = new JLabel();
        lblStatusText.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblStatusText.setForeground(ThemeManager.textSecondary());
        pnlStatusChip.add(lblStatusText);

        pnlStatusChip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (onToggleMonitoringCallback != null) {
                    onToggleMonitoringCallback.run();
                }
            }
        });

        updateStatusText();

        // --- BÊN PHẢI: Chip trạng thái + Nút chuyển đổi Sáng/Tối ---
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        rightPanel.setOpaque(false);

        btnThemeToggle = new JButton();
        btnThemeToggle.setToolTipText(I18n.get("app.theme.toggle.tooltip"));
        btnThemeToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        btnThemeToggle.setFocusable(false);
        btnThemeToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateThemeIcon();

        btnThemeToggle.addActionListener(e -> {
            if (onThemeToggle != null) {
                onThemeToggle.run();
                updateThemeIcon();
                lblTitle.setForeground(ThemeManager.textPrimary());
                lblStatusText.setForeground(ThemeManager.textSecondary());
                repaint();
            }
        });

        rightPanel.add(pnlStatusChip);
        rightPanel.add(btnThemeToggle);

        add(leftPanel, BorderLayout.WEST);
        add(rightPanel, BorderLayout.EAST);

        // Timer nhịp đập 1.2s (600ms mờ dần, 600ms sáng dần)
        pulseTimer = new Timer(50, e -> {
            if (!isMonitoring) {
                pulseAlpha = 1.0f;
                return;
            }
            if (pulseDecreasing) {
                pulseAlpha -= 0.05f;
                if (pulseAlpha <= 0.35f) {
                    pulseAlpha = 0.35f;
                    pulseDecreasing = false;
                }
            } else {
                pulseAlpha += 0.05f;
                if (pulseAlpha >= 1.0f) {
                    pulseAlpha = 1.0f;
                    pulseDecreasing = true;
                }
            }
            pnlStatusChip.repaint();
        });
        pulseTimer.start();
    }

    public void setMonitoringState(boolean monitoring, int intervalSec) {
        this.isMonitoring = monitoring;
        this.intervalSeconds = intervalSec;
        updateStatusText();
        pnlStatusChip.repaint();
    }

    public void setClusterState(boolean serverRunning, int onlineAgents, int lostAgents) {
        this.serverRunning = serverRunning;
        this.onlineAgents = onlineAgents;
        this.lostAgents = lostAgents;
        updateStatusText();
        pnlStatusChip.repaint();
    }

    private void updateStatusText() {
        if (!isMonitoring) {
            lblStatusText.setText(I18n.get("app.paused"));
            pnlStatusChip.setToolTipText("Đang tạm dừng (Bấm để tiếp tục)");
            return;
        }

        if (serverRunning) {
            if (onlineAgents > 0) {
                if (lostAgents > 0) {
                    lblStatusText.setText(String.format("Server • %d Agent online (%d mất kết nối)", onlineAgents, lostAgents));
                } else {
                    lblStatusText.setText(String.format("Server • %d Agent online", onlineAgents));
                }
            } else {
                lblStatusText.setText("Server • Đang chờ Agent...");
            }
            pnlStatusChip.setToolTipText(String.format("Chế độ Server Cluster (%d online, %d mất kết nối, chu kỳ %ds)", onlineAgents, lostAgents, intervalSeconds));
        } else {
            lblStatusText.setText(String.format("Độc lập • mỗi %d giây", intervalSeconds));
            pnlStatusChip.setToolTipText(String.format("Chế độ Standalone độc lập (Kiểm tra mỗi %d giây)", intervalSeconds));
        }
    }

    public void updateThemeIcon() {
        boolean dark = ThemeManager.isDarkMode();
        FlatSVGIcon icon = ThemeManager.getIcon(dark ? "sun" : "moon", 18);
        btnThemeToggle.setIcon(icon);
    }
}

package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Dải sức khỏe hệ thống (Bo góc 10 px, nền bgSurface, vạch ngăn cách đều đẹp)
 * Cột trái: Biểu tượng trạng thái, text kết luận, thanh tỉ lệ màu sắc
 * Cột phải: 6 chỉ số đo lường cân đối, phân chia đều đặn với vạch ngăn
 */
public class HealthBarPanel extends JPanel {
    private final JLabel lblConclusionIcon;
    private final JLabel lblConclusionText;
    private final HealthRatioBar ratioBar;

    private final JLabel lblTotalVal;
    private final JLabel lblOnlineVal;
    private final JLabel lblWarningVal;
    private final JLabel lblOfflineVal;
    private final JLabel lblAgentVal;
    private final JLabel lblAvgLatencyVal;

    private final JLabel lblConclusionSubtext;

    private String currentActiveFilter = null;
    private Consumer<String> filterCallback;

    public HealthBarPanel(Consumer<String> filterCallback) {
        this.filterCallback = filterCallback;

        setLayout(new BorderLayout(16, 0));
        setPreferredSize(new Dimension(800, UIScale.scale(74)));
        setMinimumSize(new Dimension(600, UIScale.scale(74)));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(16), UIScale.scale(8), UIScale.scale(16)));

        // --- CỘT TRÁI: Trạng thái sức khỏe + Thanh tỉ lệ màu ---
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setOpaque(false);

        JPanel conclusionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        conclusionRow.setOpaque(false);
        conclusionRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblConclusionIcon = new JLabel(ThemeManager.getIcon("check-circle", 18));
        lblConclusionText = new JLabel(I18n.get("health.all_normal"));
        lblConclusionText.setFont(ThemeManager.sans(Font.BOLD, 14));
        lblConclusionText.setForeground(ThemeManager.statusOnline());

        conclusionRow.add(lblConclusionIcon);
        conclusionRow.add(lblConclusionText);

        lblConclusionSubtext = new JLabel("Tất cả 7 dịch vụ đang hoạt động ổn định và phản hồi nhanh chóng.");
        lblConclusionSubtext.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblConclusionSubtext.setForeground(ThemeManager.textSecondary());
        lblConclusionSubtext.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblConclusionSubtext.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(24), UIScale.scale(4), 0));

        ratioBar = new HealthRatioBar();
        ratioBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        ratioBar.setPreferredSize(new Dimension(UIScale.scale(320), UIScale.scale(5)));
        ratioBar.setMaximumSize(new Dimension(UIScale.scale(420), UIScale.scale(5)));
        ratioBar.setVisible(true);

        leftPanel.add(conclusionRow);
        leftPanel.add(lblConclusionSubtext);
        leftPanel.add(ratioBar);

        // --- CỘT PHẢI: 6 số liệu thẳng hàng, vạch ngăn tinh tế, phân chia đều đặn ---
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightPanel.setOpaque(false);

        lblTotalVal = createMetricValLabel("0", null);
        lblOnlineVal = createMetricValLabel("0", ThemeManager.statusOnline());
        lblWarningVal = createMetricValLabel("0", ThemeManager.statusWarning());
        lblOfflineVal = createMetricValLabel("0", ThemeManager.statusOffline());
        lblAgentVal = createMetricValLabel("—", null);
        lblAvgLatencyVal = createMetricValLabel("—", null);

        rightPanel.add(createMetricItem(I18n.get("health.total"), lblTotalVal, "ALL", true, 78));
        rightPanel.add(createVerticalDivider());
        rightPanel.add(createMetricItem(I18n.get("health.online"), lblOnlineVal, "ONLINE", true, 75));
        rightPanel.add(createVerticalDivider());
        rightPanel.add(createMetricItem(I18n.get("health.warning_stat"), lblWarningVal, "WARNING", true, 72));
        rightPanel.add(createVerticalDivider());
        rightPanel.add(createMetricItem(I18n.get("health.offline_stat"), lblOfflineVal, "OFFLINE", true, 75));
        rightPanel.add(createVerticalDivider());
        rightPanel.add(createMetricItem("Agent", lblAgentVal, null, false, 58));
        rightPanel.add(createVerticalDivider());
        rightPanel.add(createMetricItem("Độ trễ TB", lblAvgLatencyVal, null, false, 86));

        add(leftPanel, BorderLayout.CENTER);
        add(rightPanel, BorderLayout.EAST);
    }

    private JLabel createMetricValLabel(String initialText, Color color) {
        JLabel lbl = new JLabel(initialText, SwingConstants.CENTER);
        lbl.setFont(ThemeManager.sans(Font.BOLD, 18));
        lbl.setForeground(color != null ? color : ThemeManager.textPrimary());
        return lbl;
    }

    private JPanel createMetricItem(String labelText, JLabel valueLabel, String filterKey, boolean clickable, int width) {
        JPanel item = new JPanel(new BorderLayout(0, 2));
        item.setOpaque(false);
        item.setPreferredSize(new Dimension(UIScale.scale(width), UIScale.scale(50)));

        JLabel title = new JLabel(labelText, SwingConstants.CENTER);
        title.setFont(ThemeManager.sans(Font.PLAIN, 11));
        title.setForeground(ThemeManager.textSecondary());

        item.add(title, BorderLayout.NORTH);
        item.add(valueLabel, BorderLayout.CENTER);

        if (clickable) {
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            item.setToolTipText("Bấm để lọc (" + labelText + ")");
            item.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    handleFilterClick(filterKey);
                }
            });
        }
        return item;
    }

    private JComponent createVerticalDivider() {
        return new JComponent() {
            {
                setPreferredSize(new Dimension(UIScale.scale(12), UIScale.scale(36)));
            }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(ThemeManager.border());
                int x = getWidth() / 2;
                int pad = UIScale.scale(5);
                g2.drawLine(x, pad, x, getHeight() - pad);
                g2.dispose();
            }
        };
    }

    private void handleFilterClick(String filterKey) {
        if (filterCallback == null) return;
        if (filterKey.equals(currentActiveFilter)) {
            currentActiveFilter = null;
        } else {
            currentActiveFilter = filterKey;
        }
        filterCallback.accept(currentActiveFilter);
    }

    public void updateHealth(int total, int online, int warning, int offline, int paused, long avgLatency, boolean isMonitoringRunning) {
        updateHealth(total, online, warning, offline, paused, avgLatency, isMonitoringRunning, false, 0, 0);
    }

    public void updateHealth(int total, int online, int warning, int offline, int paused, long avgLatency,
                             boolean isMonitoringRunning, boolean serverRunning, int onlineAgents, int lostAgents) {
        // Cập nhật thanh tỉ lệ màu sắc
        ratioBar.setCounts(online, warning, offline, paused);

        // Cập nhật các giá trị số
        lblTotalVal.setText(String.valueOf(total));
        lblOnlineVal.setText(String.valueOf(online));
        lblWarningVal.setText(String.valueOf(warning));
        lblOfflineVal.setText(String.valueOf(offline));

        if (avgLatency > 0) {
            lblAvgLatencyVal.setText(avgLatency + " ms");
            lblAvgLatencyVal.setForeground(ThemeManager.textPrimary());
        } else {
            lblAvgLatencyVal.setText("—");
            lblAvgLatencyVal.setForeground(ThemeManager.textMuted());
        }

        // Cập nhật ô Agent
        if (serverRunning) {
            if (lostAgents > 0) {
                lblAgentVal.setText(onlineAgents + "/" + lostAgents);
                lblAgentVal.setForeground(ThemeManager.statusWarning());
                lblAgentVal.setToolTipText(onlineAgents + " Agent trực tuyến, " + lostAgents + " Agent mất kết nối");
            } else {
                lblAgentVal.setText(onlineAgents + " on");
                lblAgentVal.setForeground(onlineAgents > 0 ? ThemeManager.statusOnline() : ThemeManager.textMuted());
                lblAgentVal.setToolTipText(onlineAgents + " Agent đang hoạt động bình thường");
            }
        } else {
            lblAgentVal.setText("—");
            lblAgentVal.setForeground(ThemeManager.textMuted());
            lblAgentVal.setToolTipText("Chế độ Standalone độc lập");
        }

        // Tự đổi text.muted khi giá trị = 0
        lblOnlineVal.setForeground(online > 0 ? ThemeManager.statusOnline() : ThemeManager.textMuted());
        lblWarningVal.setForeground(warning > 0 ? ThemeManager.statusWarning() : ThemeManager.textMuted());
        lblOfflineVal.setForeground(offline > 0 ? ThemeManager.statusOffline() : ThemeManager.textMuted());

        // Cập nhật câu kết luận & banner cảnh báo theo thứ tự ưu tiên
        if (!isMonitoringRunning) {
            lblConclusionText.setText(I18n.get("health.paused"));
            lblConclusionText.setForeground(ThemeManager.statusWarning());
            lblConclusionIcon.setIcon(ThemeManager.getIcon("pause", 18));
            lblConclusionSubtext.setText("Quá trình giám sát chu kỳ đang tạm dừng. Bấm Tiếp tục để chạy lại.");
        } else if (serverRunning && lostAgents > 0 && offline > 0) {
            lblConclusionText.setText(String.format("%d máy chủ sập • %d Agent mất kết nối!", offline, lostAgents));
            lblConclusionText.setForeground(ThemeManager.statusOffline());
            lblConclusionIcon.setIcon(ThemeManager.getIcon("alert-circle", 18));
            lblConclusionSubtext.setText("Phát hiện sự cố mạng nghiêm trọng trên cả máy chủ dịch vụ và trạm đo Agent.");
        } else if (serverRunning && lostAgents > 0) {
            lblConclusionText.setText(String.format("Cảnh báo: %d trạm Agent mất kết nối!", lostAgents));
            lblConclusionText.setForeground(ThemeManager.statusWarning());
            lblConclusionIcon.setIcon(ThemeManager.getIcon("alert-triangle", 18));
            lblConclusionSubtext.setText("Một hoặc nhiều trạm đo từ xa bị ngắt tín hiệu. Vui lòng kiểm tra tiến trình Agent.");
        } else if (total == 0) {
            lblConclusionText.setText(I18n.get("health.empty"));
            lblConclusionText.setForeground(ThemeManager.statusNeutral());
            lblConclusionIcon.setIcon(ThemeManager.getIcon("server", 18));
            lblConclusionSubtext.setText("Chưa có máy chủ trong danh sách giám sát. Hãy bấm '+ Thêm máy chủ'.");
        } else if (offline > 0) {
            lblConclusionText.setText(I18n.get("health.offline", offline));
            lblConclusionText.setForeground(ThemeManager.statusOffline());
            lblConclusionIcon.setIcon(ThemeManager.getIcon("alert-circle", 18));
            lblConclusionSubtext.setText("Phát hiện dịch vụ mất phản hồi mạng. Vui lòng kiểm tra trạng thái máy chủ.");
        } else if (warning > 0) {
            lblConclusionText.setText(I18n.get("health.warning", warning));
            lblConclusionText.setForeground(ThemeManager.statusWarning());
            lblConclusionIcon.setIcon(ThemeManager.getIcon("alert-triangle", 18));
            lblConclusionSubtext.setText("Một số máy chủ có độ trễ vượt ngưỡng cảnh báo quy định.");
        } else {
            lblConclusionText.setText(I18n.get("health.all_normal"));
            lblConclusionText.setForeground(ThemeManager.statusOnline());
            lblConclusionIcon.setIcon(ThemeManager.getIcon("check-circle", 18));
            if (serverRunning && onlineAgents > 0) {
                lblConclusionSubtext.setText(String.format("Tất cả %d dịch vụ và %d trạm Agent đang hoạt động ổn định.", total, onlineAgents));
            } else {
                lblConclusionSubtext.setText("Tất cả " + total + " dịch vụ đang hoạt động ổn định và phản hồi nhanh chóng.");
            }
        }

        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int arc = UIScale.scale(10);
        g2.setColor(ThemeManager.bgSurface());
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

        g2.setColor(ThemeManager.border());
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);

        g2.dispose();
        super.paintComponent(g);
    }
}

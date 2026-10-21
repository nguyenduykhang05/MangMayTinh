package com.networkalert.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tab Biểu đồ độ trễ và Chi tiết máy chủ kiểu Uptime Kuma / Zabbix.
 * <p>
 * Tính năng chính:
 * - Khối trạng thái Hero cỡ lớn: Nhãn to, màu theo trạng thái, thời gian duy trì, Quorum trạm đo.
 * - Bộ 4 chỉ số SLA: Độ trễ hiện tại, Uptime 24h, Uptime 7d, Uptime 30d (theo bucket phút).
 * - Biểu đồ đa trạm đo (Multi-Station Chart): Mỗi trạm một đường màu riêng biệt.
 * - Chú thích (Legend) tương tác: Bấm vào tên trạm để ẩn/hiện đường đồ thị.
 * - Vạch ngưỡng cảnh báo riêng của từng mục tiêu (Target-specific threshold).
 * - Hỗ trợ đầy đủ Dark/Light và HiDPI tự động co giãn.
 * </p>
 */
public class LatencyChartPanel extends JPanel {
    private ServerTarget currentTarget;
    private List<ServerTarget> allTargets = new ArrayList<>();
    private long globalWarningThreshold = 300;

    // --- Hero Status Block UI Components ---
    private final JPanel pnlHeroCard = new JPanel(new BorderLayout(14, 0));
    private final JLabel lblHeroStatusBadge = new JLabel("—", SwingConstants.CENTER);
    private final JLabel lblHeroStatusDuration = new JLabel("—", SwingConstants.CENTER);
    private final JLabel lblTargetTitle = new JLabel("—");
    private final JLabel lblTargetSubtitle = new JLabel("—");
    private final JLabel lblTargetGroupTags = new JLabel("—");

    // --- 4 Metric Cards: Hiện tại, Uptime 24h, 7d, 30d ---
    private final JLabel lblCurrentVal = new JLabel("—", SwingConstants.CENTER);
    private final JLabel lblUptime24hVal = new JLabel("—", SwingConstants.CENTER);
    private final JLabel lblUptime7dVal = new JLabel("—", SwingConstants.CENTER);
    private final JLabel lblUptime30dVal = new JLabel("—", SwingConstants.CENTER);

    // --- Controls Bar ---
    private final JComboBox<String> cmbViewMode = new JComboBox<>(new String[]{"Máy chủ đang chọn", "Tất cả máy chủ"});
    private final ButtonGroup timeRangeGroup = new ButtonGroup();
    private final JToggleButton btn1m = new JToggleButton("1p");
    private final JToggleButton btn5m = new JToggleButton("5p");
    private final JToggleButton btn15m = new JToggleButton("15p");
    private final JToggleButton btn1h = new JToggleButton("1g");

    // --- Interactive Multi-Station Canvas ---
    private final ChartCanvas canvas;

    public LatencyChartPanel() {
        setLayout(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // 1. KHỐI TRẠNG THÁI LỚN & CHỈ SỐ SLA (HERO STATUS BLOCK)
        JPanel topDetailsPanel = createHeroDetailsPanel();
        add(topDetailsPanel, BorderLayout.NORTH);

        // 2. BIỂU ĐỒ ĐA TRẠM & THANH ĐIỀU KHIỂN
        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.setOpaque(false);

        JPanel controlsBar = createControlsBar();
        centerPanel.add(controlsBar, BorderLayout.NORTH);

        canvas = new ChartCanvas();
        centerPanel.add(canvas, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);
    }

    private JPanel createHeroDetailsPanel() {
        JPanel container = new JPanel(new BorderLayout(0, 10));
        container.setOpaque(false);

        // Thẻ Hero chính bo góc 10px
        pnlHeroCard.setOpaque(false);
        pnlHeroCard.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        // Bên trái thẻ Hero: Khối trạng thái lớn (Chữ to, màu trạng thái, thời gian duy trì)
        JPanel heroLeftBox = new JPanel(new BorderLayout(0, 4)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = UIScale.scale(8);
                Color stColor = getStatusColor();
                g2.setColor(new Color(stColor.getRed(), stColor.getGreen(), stColor.getBlue(), 28));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.setColor(new Color(stColor.getRed(), stColor.getGreen(), stColor.getBlue(), 120));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        heroLeftBox.setOpaque(false);
        heroLeftBox.setPreferredSize(new Dimension(UIScale.scale(160), UIScale.scale(62)));
        heroLeftBox.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        lblHeroStatusBadge.setFont(ThemeManager.sans(Font.BOLD, 16));
        lblHeroStatusBadge.setForeground(ThemeManager.textPrimary());

        lblHeroStatusDuration.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblHeroStatusDuration.setForeground(ThemeManager.textSecondary());

        heroLeftBox.add(lblHeroStatusBadge, BorderLayout.CENTER);
        heroLeftBox.add(lblHeroStatusDuration, BorderLayout.SOUTH);

        // Ở giữa thẻ Hero: Tên máy chủ, Thông tin kết nối, Quorum trạm đo, Nhóm và Tag
        JPanel heroInfoBox = new JPanel(new GridLayout(3, 1, 0, 3));
        heroInfoBox.setOpaque(false);

        lblTargetTitle.setFont(ThemeManager.sans(Font.BOLD, 15));
        lblTargetTitle.setForeground(ThemeManager.textPrimary());

        lblTargetSubtitle.setFont(ThemeManager.mono(Font.PLAIN, 12));
        lblTargetSubtitle.setForeground(ThemeManager.textSecondary());

        lblTargetGroupTags.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblTargetGroupTags.setForeground(ThemeManager.textMuted());

        heroInfoBox.add(lblTargetTitle);
        heroInfoBox.add(lblTargetSubtitle);
        heroInfoBox.add(lblTargetGroupTags);

        pnlHeroCard.add(heroLeftBox, BorderLayout.WEST);
        pnlHeroCard.add(heroInfoBox, BorderLayout.CENTER);

        // Bọc pnlHeroCard trong một background card tổng
        JPanel heroWrapper = new JPanel(new BorderLayout()) {
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
        };
        heroWrapper.setOpaque(false);
        heroWrapper.add(pnlHeroCard, BorderLayout.CENTER);

        // Dưới thẻ Hero: Hàng 4 thẻ số liệu SLA (Hiện tại · Uptime 24h · Uptime 7d · Uptime 30d)
        JPanel statsRow = new JPanel(new GridLayout(1, 4, 8, 0));
        statsRow.setOpaque(false);

        statsRow.add(createStatCard(I18n.get("chart.current"), lblCurrentVal));
        statsRow.add(createStatCard(I18n.get("chart.uptime_24h", "Uptime 24h"), lblUptime24hVal));
        statsRow.add(createStatCard(I18n.get("chart.uptime_7d", "Uptime 7d"), lblUptime7dVal));
        statsRow.add(createStatCard(I18n.get("chart.uptime_30d", "Uptime 30d"), lblUptime30dVal));

        container.add(heroWrapper, BorderLayout.NORTH);
        container.add(statsRow, BorderLayout.SOUTH);

        return container;
    }

    private Color getStatusColor() {
        if (cmbViewMode.getSelectedIndex() == 1) {
            boolean hasOff = allTargets.stream().anyMatch(t -> t.getStatus() == ServerStatus.OFFLINE);
            if (hasOff) return ThemeManager.statusOffline();
            boolean hasWarn = allTargets.stream().anyMatch(t -> t.getStatus() == ServerStatus.WARNING);
            if (hasWarn) return ThemeManager.statusWarning();
            return ThemeManager.statusOnline();
        }
        if (currentTarget == null) return ThemeManager.textMuted();
        ServerStatus st = currentTarget.getStatus();
        if (st == ServerStatus.ONLINE) return ThemeManager.statusOnline();
        if (st == ServerStatus.WARNING) return ThemeManager.statusWarning();
        if (st == ServerStatus.OFFLINE) return ThemeManager.statusOffline();
        return ThemeManager.textMuted();
    }

    private JPanel createStatCard(String titleText, JLabel valLabel) {
        JPanel p = new JPanel(new BorderLayout(0, 3)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = UIScale.scale(8);
                g2.setColor(ThemeManager.bgSurface());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.setColor(ThemeManager.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        valLabel.setFont(ThemeManager.sans(Font.BOLD, 17));
        valLabel.setForeground(ThemeManager.textPrimary());

        JLabel lbl = new JLabel(titleText, SwingConstants.CENTER);
        lbl.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lbl.setForeground(ThemeManager.textSecondary());

        p.add(valLabel, BorderLayout.CENTER);
        p.add(lbl, BorderLayout.SOUTH);
        return p;
    }

    private JPanel createControlsBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);

        // Bên trái: Phân đoạn thời gian (1p, 5p, 15p, 1g)
        JPanel leftSegment = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftSegment.setOpaque(false);

        setupTimeBtn(btn1m);
        setupTimeBtn(btn5m);
        setupTimeBtn(btn15m);
        setupTimeBtn(btn1h);
        btn5m.setSelected(true);

        timeRangeGroup.add(btn1m);
        timeRangeGroup.add(btn5m);
        timeRangeGroup.add(btn15m);
        timeRangeGroup.add(btn1h);

        leftSegment.add(btn1m);
        leftSegment.add(btn5m);
        leftSegment.add(btn15m);
        leftSegment.add(btn1h);

        // Bên phải: Dropdown chọn máy chủ & Chip Trực tiếp
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightControls.setOpaque(false);

        cmbViewMode.setFont(ThemeManager.sans(Font.PLAIN, 12));
        cmbViewMode.setPreferredSize(new Dimension(UIScale.scale(155), UIScale.scale(28)));
        cmbViewMode.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        cmbViewMode.addActionListener(e -> {
            updateCardDetails();
            canvas.resetHidden();
            canvas.repaint();
        });

        JPanel liveChip = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = getHeight();
                g2.setColor(ThemeManager.bgSurfaceAlt());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

                // Chấm xanh
                g2.setColor(ThemeManager.statusOnline());
                int s = UIScale.scale(6);
                g2.fillOval(UIScale.scale(8), (getHeight() - s) / 2, s, s);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        liveChip.setOpaque(false);
        liveChip.setBorder(BorderFactory.createEmptyBorder(2, UIScale.scale(16), 2, UIScale.scale(8)));

        JLabel lblLive = new JLabel(I18n.get("chart.live"));
        lblLive.setFont(ThemeManager.sans(Font.BOLD, 11));
        lblLive.setForeground(ThemeManager.textSecondary());
        liveChip.add(lblLive);

        rightControls.add(cmbViewMode);
        rightControls.add(liveChip);

        bar.add(leftSegment, BorderLayout.WEST);
        bar.add(rightControls, BorderLayout.EAST);
        return bar;
    }

    private void setupTimeBtn(JToggleButton btn) {
        btn.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btn.setMargin(new Insets(4, 10, 4, 10));
        btn.setPreferredSize(new Dimension(UIScale.scale(54), UIScale.scale(28)));
        btn.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> {
            updateCardDetails();
            canvas.repaint();
        });
    }

    private int getSelectedPointCount() {
        if (btn1m.isSelected()) return 12;
        if (btn5m.isSelected()) return 60;
        if (btn15m.isSelected()) return 180;
        if (btn1h.isSelected()) return 720;
        return 60;
    }

    // Bảng màu phân biệt cho các đường đồ thị trạm đo (Station Palette)
    private static final Color[] STATION_COLORS = new Color[]{
            new Color(56, 189, 248),  // Sky Blue
            new Color(168, 85, 247),  // Purple
            new Color(249, 115, 22),  // Orange
            new Color(16, 185, 129),  // Emerald
            new Color(236, 72, 153),  // Pink
            new Color(234, 179, 8),   // Yellow
            new Color(99, 102, 241),  // Indigo
            new Color(20, 184, 166)   // Teal
    };

    public void updateTarget(ServerTarget target, long threshold) {
        this.currentTarget = target;
        this.globalWarningThreshold = threshold;
        updateCardDetails();
        pnlHeroCard.repaint();
        canvas.repaint();
    }

    public void setAllTargets(List<ServerTarget> targets) {
        this.allTargets = targets != null ? targets : Collections.emptyList();
        if (cmbViewMode.getSelectedIndex() == 1) {
            updateCardDetails();
        }
        pnlHeroCard.repaint();
        canvas.repaint();
    }

    private void updateCardDetails() {
        if (cmbViewMode.getSelectedIndex() == 1) {
            // Chế độ: Toàn bộ mạng giám sát
            int total = allTargets.size();
            long sumCurrent = 0;
            int activeCount = 0;
            double sumU24 = 0, sumU7 = 0, sumU30 = 0;
            int online = 0, warn = 0, off = 0;

            for (ServerTarget t : allTargets) {
                if (t.getStatus() == ServerStatus.ONLINE) online++;
                else if (t.getStatus() == ServerStatus.WARNING) warn++;
                else if (t.getStatus() == ServerStatus.OFFLINE) off++;

                if (t.getCurrentLatency() > 0) {
                    sumCurrent += t.getCurrentLatency();
                    activeCount++;
                }
                sumU24 += t.getUptime24h();
                sumU7 += t.getUptime7d();
                sumU30 += t.getUptime30d();
            }

            if (off > 0) {
                lblHeroStatusBadge.setText("SỰ CỐ MẠNG");
                lblHeroStatusBadge.setForeground(ThemeManager.statusOffline());
                lblHeroStatusDuration.setText(off + " máy chủ mất mạng");
            } else if (warn > 0) {
                lblHeroStatusBadge.setText("CẢNH BÁO");
                lblHeroStatusBadge.setForeground(ThemeManager.statusWarning());
                lblHeroStatusDuration.setText(warn + " máy chủ trễ cao");
            } else if (online > 0) {
                lblHeroStatusBadge.setText("ỔN ĐỊNH");
                lblHeroStatusBadge.setForeground(ThemeManager.statusOnline());
                lblHeroStatusDuration.setText(online + "/" + total + " hoạt động tốt");
            } else {
                lblHeroStatusBadge.setText("CHỜ DỮ LIỆU");
                lblHeroStatusBadge.setForeground(ThemeManager.textMuted());
                lblHeroStatusDuration.setText("—");
            }

            lblTargetTitle.setText("Toàn bộ mạng giám sát (" + total + " mục tiêu)");
            lblTargetSubtitle.setText(String.format("%d Online · %d Cảnh báo · %d Mất kết nối", online, warn, off));
            lblTargetGroupTags.setText("Phạm vi: Đa trạm đo (Server & Agents)");

            long avgCurrent = activeCount > 0 ? (sumCurrent / activeCount) : 0;
            lblCurrentVal.setText(avgCurrent + " ms");
            lblCurrentVal.setForeground(avgCurrent > globalWarningThreshold ? ThemeManager.statusWarning() : ThemeManager.textPrimary());

            long totalAllChecks = allTargets.stream().mapToLong(ServerTarget::getTotalChecks).sum();
            if (totalAllChecks == 0) {
                lblUptime24hVal.setText("—");
                lblUptime7dVal.setText("—");
                lblUptime30dVal.setText("—");
            } else if (totalAllChecks < 10) {
                double avg24 = total > 0 ? (sumU24 / total) : 100.0;
                lblUptime24hVal.setText(String.format("%.1f%%*", avg24));
                lblUptime24hVal.setToolTipText(String.format("Dữ liệu mới khởi chạy (%d lượt kiểm tra)", totalAllChecks));
                lblUptime7dVal.setText("—");
                lblUptime7dVal.setToolTipText("Chưa đủ dữ liệu lịch sử 7 ngày");
                lblUptime30dVal.setText("—");
                lblUptime30dVal.setToolTipText("Chưa đủ dữ liệu lịch sử 30 ngày");
            } else {
                double avg24 = total > 0 ? (sumU24 / total) : 100.0;
                double avg7 = total > 0 ? (sumU7 / total) : 100.0;
                double avg30 = total > 0 ? (sumU30 / total) : 100.0;
                lblUptime24hVal.setText(String.format("%.2f%%", avg24));
                lblUptime24hVal.setToolTipText(null);
                lblUptime7dVal.setText(String.format("%.2f%%", avg7));
                lblUptime7dVal.setToolTipText(null);
                lblUptime30dVal.setText(String.format("%.2f%%", avg30));
                lblUptime30dVal.setToolTipText(null);
            }
            return;
        }

        if (currentTarget == null) {
            lblHeroStatusBadge.setText("CHƯA CHỌN");
            lblHeroStatusBadge.setForeground(ThemeManager.textMuted());
            lblHeroStatusDuration.setText("—");
            lblTargetTitle.setText("Vui lòng chọn máy chủ để xem chi tiết");
            lblTargetSubtitle.setText("—");
            lblTargetGroupTags.setText("—");
            lblCurrentVal.setText("—");
            lblUptime24hVal.setText("—");
            lblUptime7dVal.setText("—");
            lblUptime30dVal.setText("—");
            return;
        }

        // Khối trạng thái Hero cỡ lớn
        ServerStatus st = currentTarget.getStatus();
        if (st == ServerStatus.ONLINE) {
            lblHeroStatusBadge.setText("TRỰC TUYẾN");
            lblHeroStatusBadge.setForeground(ThemeManager.statusOnline());
        } else if (st == ServerStatus.WARNING) {
            lblHeroStatusBadge.setText("TRỄ CAO");
            lblHeroStatusBadge.setForeground(ThemeManager.statusWarning());
        } else if (st == ServerStatus.OFFLINE) {
            lblHeroStatusBadge.setText("MẤT KẾT NỐI");
            lblHeroStatusBadge.setForeground(ThemeManager.statusOffline());
        } else {
            lblHeroStatusBadge.setText("TẠM DỪNG");
            lblHeroStatusBadge.setForeground(ThemeManager.textMuted());
        }
        lblHeroStatusDuration.setText(I18n.get("chart.duration", currentTarget.getStatusDurationFormatted()));

        // Thông tin mục tiêu
        lblTargetTitle.setText(currentTarget.getName());
        String addr = currentTarget.getProtocol() == ProtocolType.ICMP
                ? currentTarget.getHost()
                : (currentTarget.getHost() + ":" + currentTarget.getPort());
        lblTargetSubtitle.setText(String.format("%s (%s) • %s • %s",
                addr, currentTarget.getProtocol(),
                currentTarget.getScope() != null ? currentTarget.getScope().getDisplayName() : "Đa trạm",
                currentTarget.getQuorumSummary()));

        String group = currentTarget.getGroup() != null ? currentTarget.getGroup() : "Default";
        String tags = currentTarget.getTags() != null && !currentTarget.getTags().trim().isEmpty()
                ? (" • " + currentTarget.getTags()) : "";

        String tlsInfo = "";
        if (currentTarget.getTlsExpiryDays() >= 0) {
            tlsInfo = currentTarget.getTlsExpiryDays() < 14
                    ? String.format(" • ⚠️ TLS còn: %d ngày", currentTarget.getTlsExpiryDays())
                    : String.format(" • 🔒 TLS: %d ngày", currentTarget.getTlsExpiryDays());
        }
        String pushInfo = (currentTarget.getPushToken() != null && !currentTarget.getPushToken().isEmpty())
                ? (" • Push: " + currentTarget.getPushToken()) : "";

        lblTargetGroupTags.setText("Nhóm: " + group + tags + tlsInfo + pushInfo);

        // 4 Chỉ số SLA
        long effectiveThreshold = currentTarget.getWarningThresholdMs() > 0
                ? currentTarget.getWarningThresholdMs()
                : globalWarningThreshold;

        if (currentTarget.getCurrentLatency() >= 0) {
            lblCurrentVal.setText(currentTarget.getCurrentLatency() + " ms");
            lblCurrentVal.setForeground(currentTarget.getCurrentLatency() > effectiveThreshold
                    ? ThemeManager.statusWarning()
                    : ThemeManager.textPrimary());
        } else {
            lblCurrentVal.setText("Mất kết nối");
            lblCurrentVal.setForeground(ThemeManager.statusOffline());
        }

        long totalChecks = currentTarget.getTotalChecks();
        if (totalChecks == 0) {
            lblUptime24hVal.setText("—");
            lblUptime24hVal.setToolTipText("Chưa có dữ liệu kiểm tra");
            lblUptime7dVal.setText("—");
            lblUptime7dVal.setToolTipText("Chưa có dữ liệu kiểm tra");
            lblUptime30dVal.setText("—");
            lblUptime30dVal.setToolTipText("Chưa có dữ liệu kiểm tra");
        } else if (totalChecks < 5) {
            // Mới khởi chạy: hiển thị độ phủ kèm cờ * hoặc - (ERRATA §E13 #5 & §E8.6)
            lblUptime24hVal.setText(String.format("%.1f%%*", currentTarget.getUptime24h()));
            lblUptime24hVal.setToolTipText(String.format("Dữ liệu mới khởi chạy (%d lượt kiểm tra)", totalChecks));
            lblUptime7dVal.setText("—");
            lblUptime7dVal.setToolTipText("Chưa đủ dữ liệu lịch sử 7 ngày");
            lblUptime30dVal.setText("—");
            lblUptime30dVal.setToolTipText("Chưa đủ dữ liệu lịch sử 30 ngày");
        } else {
            lblUptime24hVal.setText(String.format("%.2f%%", currentTarget.getUptime24h()));
            lblUptime24hVal.setToolTipText(null);
            lblUptime7dVal.setText(String.format("%.2f%%", currentTarget.getUptime7d()));
            lblUptime7dVal.setToolTipText(null);
            lblUptime30dVal.setText(String.format("%.2f%%", currentTarget.getUptime30d()));
            lblUptime30dVal.setToolTipText(null);
        }
    }

    // =========================================================================
    // CANVAS VẼ BIỂU ĐỒ ĐA TRẠM ĐO (MULTI-STATION GRAPHICS2D)
    // =========================================================================
    private class ChartCanvas extends JPanel {
        private Point mousePoint = null;
        private final Set<String> hiddenSeries = new HashSet<>();
        private final Map<Rectangle, String> legendHitBoxes = new HashMap<>();

        public ChartCanvas() {
            setOpaque(true);
            setBackground(ThemeManager.bgSurface());
            setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    mousePoint = e.getPoint();
                    boolean overLegend = isOverLegend(e.getPoint());
                    setCursor(Cursor.getPredefinedCursor(overLegend ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    mousePoint = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    for (Map.Entry<Rectangle, String> entry : legendHitBoxes.entrySet()) {
                        if (entry.getKey().contains(e.getPoint())) {
                            String series = entry.getValue();
                            if (hiddenSeries.contains(series)) {
                                hiddenSeries.remove(series);
                            } else {
                                hiddenSeries.add(series);
                            }
                            repaint();
                            return;
                        }
                    }
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        public void resetHidden() {
            hiddenSeries.clear();
        }

        private boolean isOverLegend(Point p) {
            for (Rectangle r : legendHitBoxes.keySet()) {
                if (r.contains(p)) return true;
            }
            return false;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            g2.setColor(ThemeManager.bgSurface());
            g2.fillRect(0, 0, w, h);

            int pointsCount = getSelectedPointCount();
            legendHitBoxes.clear();

            if (cmbViewMode.getSelectedIndex() == 1) {
                drawAllTargetsChart(g2, w, h, pointsCount);
            } else {
                drawMultiStationChart(g2, w, h, pointsCount);
            }

            g2.dispose();
        }

        /**
         * Vẽ biểu đồ Đa trạm đo (Mỗi trạm kiểm tra một đường màu) cho ServerTarget đang chọn
         */
        private void drawMultiStationChart(Graphics2D g2, int w, int h, int pointsCount) {
            if (currentTarget == null) {
                drawEmpty(g2, w, h, I18n.get("chart.no_data"));
                return;
            }

            Map<String, List<Long>> stationLats = currentTarget.getStationLatencies();
            // Nếu chưa có kết quả theo trạm, dự phòng dùng recentLatencies chung
            if (stationLats.isEmpty()) {
                List<Long> fallback = currentTarget.getRecentLatencies(pointsCount);
                if (fallback.isEmpty()) {
                    drawEmpty(g2, w, h, I18n.get("chart.no_data"));
                    return;
                }
                String defStation = (currentTarget.getAssignedAgent() != null && !currentTarget.getAssignedAgent().isEmpty())
                        ? currentTarget.getAssignedAgent() : "Server";
                stationLats = new HashMap<>();
                stationLats.put(defStation, fallback);
            }

            int padL = UIScale.scale(46);
            int padR = UIScale.scale(16);
            int padT = UIScale.scale(36); // Chừa chỗ cho Legend tương tác
            int padB = UIScale.scale(30);

            int plotX = padL;
            int plotY = padT;
            int plotW = w - padL - padR;
            int plotH = h - padT - padB;

            int padBottomVal = UIScale.scale(12);
            int usableH = Math.max(10, plotH - padBottomVal);

            if (plotW <= 10 || plotH <= 10) return;

            long effectiveThreshold = currentTarget.getWarningThresholdMs() > 0
                    ? currentTarget.getWarningThresholdMs()
                    : globalWarningThreshold;

            // Tìm giá trị độ trễ lớn nhất để scale trục Y
            long maxVal = 0;
            for (List<Long> lats : stationLats.values()) {
                for (long val : lats) {
                    if (val > maxVal) maxVal = val;
                }
            }
            long maxY = Math.max(effectiveThreshold + 40, Math.max(maxVal + 20, 100));

            // 1. VẼ LEGEND TRẠM ĐO (Tương tác bấm ẩn/hiện)
            List<String> stations = new ArrayList<>(stationLats.keySet());
            Collections.sort(stations);

            int legX = plotX;
            int legY = padT - UIScale.scale(14);
            g2.setFont(ThemeManager.sans(Font.BOLD, 10));
            FontMetrics legFm = g2.getFontMetrics();

            for (int i = 0; i < stations.size(); i++) {
                String stName = stations.get(i);
                Color c = STATION_COLORS[i % STATION_COLORS.length];
                boolean hidden = hiddenSeries.contains(stName);

                int dotSz = UIScale.scale(8);
                int textW = legFm.stringWidth(stName);
                int itemW = dotSz + 6 + textW + UIScale.scale(10);

                if (legX + itemW > plotX + plotW) break;

                Rectangle box = new Rectangle(legX, legY - dotSz, itemW, dotSz + 6);
                legendHitBoxes.put(box, stName);

                // Chấm màu trạm đo
                if (hidden) {
                    g2.setColor(ThemeManager.textMuted());
                    g2.drawOval(legX, legY - dotSz + 2, dotSz, dotSz);
                } else {
                    g2.setColor(c);
                    g2.fillOval(legX, legY - dotSz + 2, dotSz, dotSz);
                }

                // Tên trạm đo
                g2.setColor(hidden ? ThemeManager.textMuted() : ThemeManager.textSecondary());
                g2.drawString(stName, legX + dotSz + 4, legY);

                if (hidden) {
                    // Gạch ngang nếu đang bị ẩn
                    int lineY = legY - 3;
                    g2.drawLine(legX + dotSz + 4, lineY, legX + dotSz + 4 + textW, lineY);
                }

                legX += itemW + UIScale.scale(10);
            }

            // Ghi chú click legend
            g2.setFont(ThemeManager.sans(Font.PLAIN, 9));
            g2.setColor(ThemeManager.textMuted());
            String hint = I18n.get("chart.legend_hint", "(Bấm để ẩn/hiện)");
            g2.drawString(hint, plotX + plotW - legFm.stringWidth(hint), legY);

            // 2. VẼ LƯỚI NGANG (Grid lines)
            int gridSteps = 4;
            g2.setFont(ThemeManager.mono(Font.PLAIN, 10));

            for (int i = 0; i <= gridSteps; i++) {
                int y = plotY + plotH - padBottomVal - (i * usableH / gridSteps);
                long labelVal = (i * maxY / gridSteps);
                g2.setColor(ThemeManager.border());
                g2.drawLine(plotX, y, plotX + plotW, y);

                g2.setColor(ThemeManager.textMuted());
                g2.drawString(labelVal + "ms", plotX - UIScale.scale(42), y + 4);
            }

            // 3. VẼ VẠCH NGƯỠNG RIÊNG CỦA TARGET (Dashed Line)
            if (effectiveThreshold <= maxY) {
                int threshY = (int) (plotY + plotH - padBottomVal - (effectiveThreshold * usableH / maxY));
                Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f);
                g2.setStroke(dashed);
                g2.setColor(ThemeManager.statusWarning());
                g2.drawLine(plotX, threshY, plotX + plotW, threshY);

                g2.setFont(ThemeManager.sans(Font.BOLD, 10));
                String threshText = currentTarget.getWarningThresholdMs() > 0
                        ? I18n.get("chart.threshold_custom", effectiveThreshold)
                        : I18n.get("chart.threshold", effectiveThreshold);
                g2.drawString(threshText, plotX + plotW - UIScale.scale(105), threshY - 3);
            }

            // 4. VẼ TỪNG ĐƯỜNG ĐỘ TRỄ CỦA TỪNG TRẠM ĐO
            Map<String, Point[]> stationPoints = new HashMap<>();
            Map<String, List<Long>> stationSlicedLats = new HashMap<>();

            for (int stIdx = 0; stIdx < stations.size(); stIdx++) {
                String stName = stations.get(stIdx);
                if (hiddenSeries.contains(stName)) continue;

                List<Long> rawLats = stationLats.get(stName);
                if (rawLats == null || rawLats.isEmpty()) continue;

                List<Long> latencies;
                if (pointsCount <= 0 || rawLats.size() <= pointsCount) {
                    latencies = new ArrayList<>(rawLats);
                } else {
                    latencies = new ArrayList<>(rawLats.subList(rawLats.size() - pointsCount, rawLats.size()));
                }
                stationSlicedLats.put(stName, latencies);

                int n = latencies.size();
                Point[] pts = new Point[n];
                for (int i = 0; i < n; i++) {
                    long l = latencies.get(i);
                    int px = n > 1 ? plotX + (i * plotW / (n - 1)) : plotX + plotW / 2;
                    int py = (int) (plotY + plotH - padBottomVal - (Math.max(0, l) * usableH / maxY));
                    pts[i] = new Point(px, py);
                }
                stationPoints.put(stName, pts);

                Color stColor = STATION_COLORS[stIdx % STATION_COLORS.length];

                // Nếu chỉ có đúng 1 trạm, vẽ diện tích Gradient mờ bên dưới
                if (stations.size() == 1 || (stationPoints.size() == 1 && stationLats.size() <= 2)) {
                    GeneralPath fillPath = new GeneralPath();
                    fillPath.moveTo(pts[0].x, plotY + plotH - padBottomVal);
                    for (Point p : pts) fillPath.lineTo(p.x, p.y);
                    fillPath.lineTo(pts[n - 1].x, plotY + plotH - padBottomVal);
                    fillPath.closePath();

                    LinearGradientPaint grad = new LinearGradientPaint(
                            0, plotY,
                            0, plotY + plotH - padBottomVal,
                            new float[]{0.0f, 1.0f},
                            new Color[]{
                                    new Color(stColor.getRed(), stColor.getGreen(), stColor.getBlue(), 45),
                                    new Color(stColor.getRed(), stColor.getGreen(), stColor.getBlue(), 5)
                            }
                    );
                    g2.setPaint(grad);
                    g2.fill(fillPath);
                }

                // Vẽ đường nối các điểm
                g2.setColor(stColor);
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                GeneralPath linePath = new GeneralPath();
                boolean inSegment = false;

                for (int i = 0; i < n; i++) {
                    long l = latencies.get(i);
                    if (l <= 0) {
                        inSegment = false;
                        int xX = pts[i].x;
                        int xY = plotY + plotH - padBottomVal;
                        g2.setColor(ThemeManager.statusOffline());
                        int sz = UIScale.scale(4);
                        g2.fillOval(xX - sz / 2, xY - sz / 2, sz, sz);
                        g2.setColor(stColor);
                    } else {
                        if (!inSegment) {
                            linePath.moveTo(pts[i].x, pts[i].y);
                            inSegment = true;
                        } else {
                            linePath.lineTo(pts[i].x, pts[i].y);
                        }
                    }
                }
                g2.draw(linePath);

                // Chấm điểm dữ liệu
                for (int i = 0; i < n; i++) {
                    if (latencies.get(i) > 0) {
                        int s = UIScale.scale(5);
                        g2.fillOval(pts[i].x - s / 2, pts[i].y - s / 2, s, s);
                    }
                }
            }

            // 5. CROSSHAIR VÀ TOOLTIP ĐA TRẠM KHI RÊ CHUỘT
            if (mousePoint != null && mousePoint.x >= plotX && mousePoint.x <= plotX + plotW && !isOverLegend(mousePoint)) {
                g2.setColor(new Color(150, 150, 150, 100));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{2, 2}, 0));
                g2.drawLine(mousePoint.x, plotY, mousePoint.x, plotY + plotH);

                List<String> tipLines = new ArrayList<>();
                List<Color> tipColors = new ArrayList<>();

                for (int stIdx = 0; stIdx < stations.size(); stIdx++) {
                    String stName = stations.get(stIdx);
                    Point[] pts = stationPoints.get(stName);
                    List<Long> lats = stationSlicedLats.get(stName);
                    if (pts == null || lats == null || pts.length == 0) continue;

                    int n = pts.length;
                    int idx = (int) Math.round((double) (mousePoint.x - plotX) / plotW * (n - 1));
                    if (idx >= 0 && idx < n) {
                        long l = lats.get(idx);
                        String val = l > 0 ? (l + " ms") : "Mất mạng";
                        tipLines.add(stName + ": " + val);
                        tipColors.add(STATION_COLORS[stIdx % STATION_COLORS.length]);
                    }
                }

                if (!tipLines.isEmpty()) {
                    g2.setFont(ThemeManager.mono(Font.PLAIN, 11));
                    FontMetrics fm = g2.getFontMetrics();
                    int maxStrW = 0;
                    for (String str : tipLines) {
                        int sw = fm.stringWidth(str);
                        if (sw > maxStrW) maxStrW = sw;
                    }

                    int tw = maxStrW + UIScale.scale(26);
                    int th = tipLines.size() * UIScale.scale(18) + UIScale.scale(8);
                    int tx = Math.min(plotX + plotW - tw, Math.max(plotX, mousePoint.x + 10));
                    int ty = Math.min(plotY + plotH - th, Math.max(plotY, mousePoint.y - th / 2));

                    g2.setColor(ThemeManager.bgSurfaceAlt());
                    g2.fillRoundRect(tx, ty, tw, th, 6, 6);
                    g2.setColor(ThemeManager.border());
                    g2.drawRoundRect(tx, ty, tw, th, 6, 6);

                    int curY = ty + UIScale.scale(15);
                    for (int k = 0; k < tipLines.size(); k++) {
                        g2.setColor(tipColors.get(k));
                        int ds = UIScale.scale(6);
                        g2.fillOval(tx + 6, curY - ds + 1, ds, ds);

                        g2.setColor(ThemeManager.textPrimary());
                        g2.drawString(tipLines.get(k), tx + 16, curY);
                        curY += UIScale.scale(18);
                    }
                }
            }
        }

        /**
         * Vẽ biểu đồ Tổng quan tất cả máy chủ trong hệ thống
         */
        private void drawAllTargetsChart(Graphics2D g2, int w, int h, int pointsCount) {
            if (allTargets == null || allTargets.isEmpty()) {
                drawEmpty(g2, w, h, I18n.get("chart.no_data"));
                return;
            }

            int padL = UIScale.scale(46);
            int padR = UIScale.scale(16);
            int padT = UIScale.scale(36);
            int padB = UIScale.scale(30);

            int plotX = padL;
            int plotY = padT;
            int plotW = w - padL - padR;
            int plotH = h - padT - padB;

            int padBottomVal = UIScale.scale(12);
            int usableH = Math.max(10, plotH - padBottomVal);

            if (plotW <= 10 || plotH <= 10) return;

            long maxVal = 0;
            boolean hasData = false;
            for (ServerTarget t : allTargets) {
                List<Long> lats = t.getRecentLatencies(pointsCount);
                for (long val : lats) {
                    if (val > 0) hasData = true;
                    if (val > maxVal) maxVal = val;
                }
            }

            if (!hasData && maxVal == 0) {
                drawEmpty(g2, w, h, I18n.get("chart.no_data"));
                return;
            }

            long maxY = Math.max(globalWarningThreshold + 40, Math.max(maxVal + 20, 100));

            // Chú thích các máy chủ (Legend)
            int legX = plotX;
            int legY = padT - UIScale.scale(14);
            g2.setFont(ThemeManager.sans(Font.BOLD, 10));
            FontMetrics legFm = g2.getFontMetrics();

            for (int i = 0; i < allTargets.size(); i++) {
                ServerTarget t = allTargets.get(i);
                String tName = t.getName();
                Color sColor = STATION_COLORS[i % STATION_COLORS.length];
                boolean hidden = hiddenSeries.contains(tName);

                int dotSz = UIScale.scale(7);
                String shortName = tName.length() > 14 ? tName.substring(0, 12) + "…" : tName;
                int itemW = dotSz + 6 + legFm.stringWidth(shortName) + UIScale.scale(8);

                if (legX + itemW > plotX + plotW - UIScale.scale(70)) break;

                Rectangle box = new Rectangle(legX, legY - dotSz, itemW, dotSz + 6);
                legendHitBoxes.put(box, tName);

                if (hidden) {
                    g2.setColor(ThemeManager.textMuted());
                    g2.drawOval(legX, legY - dotSz + 2, dotSz, dotSz);
                } else {
                    g2.setColor(sColor);
                    g2.fillOval(legX, legY - dotSz + 2, dotSz, dotSz);
                }

                g2.setColor(hidden ? ThemeManager.textMuted() : ThemeManager.textSecondary());
                g2.drawString(shortName, legX + dotSz + 4, legY);

                if (hidden) {
                    int lineY = legY - 3;
                    g2.drawLine(legX + dotSz + 4, lineY, legX + dotSz + 4 + legFm.stringWidth(shortName), lineY);
                }

                legX += itemW + UIScale.scale(10);
            }

            // Lưới ngang
            int gridSteps = 4;
            g2.setFont(ThemeManager.mono(Font.PLAIN, 10));
            for (int i = 0; i <= gridSteps; i++) {
                int y = plotY + plotH - padBottomVal - (i * usableH / gridSteps);
                long labelVal = (i * maxY / gridSteps);
                g2.setColor(ThemeManager.border());
                g2.drawLine(plotX, y, plotX + plotW, y);

                g2.setColor(ThemeManager.textMuted());
                g2.drawString(labelVal + "ms", plotX - UIScale.scale(42), y + 4);
            }

            // Vạch ngưỡng cảnh báo chung
            if (globalWarningThreshold <= maxY) {
                int threshY = (int) (plotY + plotH - padBottomVal - (globalWarningThreshold * usableH / maxY));
                Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f);
                g2.setStroke(dashed);
                g2.setColor(ThemeManager.statusWarning());
                g2.drawLine(plotX, threshY, plotX + plotW, threshY);

                g2.setFont(ThemeManager.sans(Font.BOLD, 10));
                g2.drawString(I18n.get("chart.threshold", globalWarningThreshold), plotX + plotW - UIScale.scale(85), threshY - 3);
            }

            // Vẽ từng đường cho mỗi ServerTarget
            List<Point[]> allPtsList = new ArrayList<>();
            List<List<Long>> allLatsList = new ArrayList<>();
            List<String> visibleTargetNames = new ArrayList<>();
            List<Color> visibleTargetColors = new ArrayList<>();

            for (int tIdx = 0; tIdx < allTargets.size(); tIdx++) {
                ServerTarget t = allTargets.get(tIdx);
                if (hiddenSeries.contains(t.getName())) continue;

                List<Long> latencies = t.getRecentLatencies(pointsCount);
                int n = latencies.size();
                if (n == 0) continue;

                allLatsList.add(latencies);
                visibleTargetNames.add(t.getName());
                Color sColor = STATION_COLORS[tIdx % STATION_COLORS.length];
                visibleTargetColors.add(sColor);

                Point[] pts = new Point[n];
                for (int i = 0; i < n; i++) {
                    long l = latencies.get(i);
                    int px = n > 1 ? plotX + (i * plotW / (n - 1)) : plotX + plotW / 2;
                    int py = (int) (plotY + plotH - padBottomVal - (Math.max(0, l) * usableH / maxY));
                    pts[i] = new Point(px, py);
                }
                allPtsList.add(pts);

                g2.setColor(sColor);
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                GeneralPath linePath = new GeneralPath();
                boolean inSegment = false;
                for (int i = 0; i < n; i++) {
                    long l = latencies.get(i);
                    if (l <= 0) {
                        inSegment = false;
                    } else {
                        if (!inSegment) {
                            linePath.moveTo(pts[i].x, pts[i].y);
                            inSegment = true;
                        } else {
                            linePath.lineTo(pts[i].x, pts[i].y);
                        }
                    }
                }
                g2.draw(linePath);
            }

            // Crosshair & Tooltip
            if (mousePoint != null && mousePoint.x >= plotX && mousePoint.x <= plotX + plotW && !isOverLegend(mousePoint)) {
                g2.setColor(new Color(150, 150, 150, 100));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{2, 2}, 0));
                g2.drawLine(mousePoint.x, plotY, mousePoint.x, plotY + plotH);

                List<String> tipLines = new ArrayList<>();
                List<Color> tipColors = new ArrayList<>();

                for (int k = 0; k < allPtsList.size(); k++) {
                    Point[] pts = allPtsList.get(k);
                    List<Long> lats = allLatsList.get(k);
                    int n = pts.length;
                    if (n == 0) continue;

                    int idx = (int) Math.round((double) (mousePoint.x - plotX) / plotW * (n - 1));
                    if (idx >= 0 && idx < n) {
                        long l = lats.get(idx);
                        String line = visibleTargetNames.get(k) + ": " + (l > 0 ? (l + " ms") : "Mất mạng");
                        tipLines.add(line);
                        tipColors.add(visibleTargetColors.get(k));
                    }
                }

                if (!tipLines.isEmpty()) {
                    g2.setFont(ThemeManager.mono(Font.PLAIN, 11));
                    FontMetrics fm = g2.getFontMetrics();
                    int maxStrW = 0;
                    for (String str : tipLines) {
                        int sw = fm.stringWidth(str);
                        if (sw > maxStrW) maxStrW = sw;
                    }

                    int tw = maxStrW + UIScale.scale(26);
                    int th = tipLines.size() * UIScale.scale(18) + UIScale.scale(8);
                    int tx = Math.min(plotX + plotW - tw, Math.max(plotX, mousePoint.x + 10));
                    int ty = Math.min(plotY + plotH - th, Math.max(plotY, mousePoint.y - th / 2));

                    g2.setColor(ThemeManager.bgSurfaceAlt());
                    g2.fillRoundRect(tx, ty, tw, th, 6, 6);
                    g2.setColor(ThemeManager.border());
                    g2.drawRoundRect(tx, ty, tw, th, 6, 6);

                    int curY = ty + UIScale.scale(15);
                    for (int k = 0; k < tipLines.size(); k++) {
                        g2.setColor(tipColors.get(k));
                        int ds = UIScale.scale(6);
                        g2.fillOval(tx + 6, curY - ds + 1, ds, ds);

                        g2.setColor(ThemeManager.textPrimary());
                        g2.drawString(tipLines.get(k), tx + 16, curY);
                        curY += UIScale.scale(18);
                    }
                }
            }
        }

        private void drawEmpty(Graphics2D g2, int w, int h, String msg) {
            g2.setFont(ThemeManager.sans(Font.PLAIN, 13));
            g2.setColor(ThemeManager.textMuted());
            FontMetrics fm = g2.getFontMetrics();
            int sx = (w - fm.stringWidth(msg)) / 2;
            int sy = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(msg, sx, sy);
        }
    }
}

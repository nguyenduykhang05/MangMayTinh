package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renderer toàn diện cho Bảng Máy chủ theo đúng đặc tả UI_REDESIGN.md
 */
public class ServerTableRenderer extends DefaultTableCellRenderer {
    private static final DateTimeFormatter FULL_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    // Quản lý hiệu ứng nháy nền 600 ms khi đổi trạng thái
    private final Map<Integer, Long> flashTimestamps = new ConcurrentHashMap<>();
    private final HeartbeatBarRenderer heartbeatRenderer = new HeartbeatBarRenderer();
    private long warningLatencyThreshold = 300;

    public void setWarningLatencyThreshold(long threshold) {
        this.warningLatencyThreshold = threshold;
    }

    public void triggerRowFlash(int targetId) {
        flashTimestamps.put(targetId, System.currentTimeMillis());
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
        JLabel c = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        c.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(8), 0, UIScale.scale(8)));
        c.setIcon(null);
        c.setText("");
        c.setIconTextGap(UIScale.scale(4));
        c.setToolTipText(null);

        int modelRow = table.convertRowIndexToModel(row);
        ServerTarget target = null;
        if (table.getModel() instanceof ServerTableModel) {
            target = ((ServerTableModel) table.getModel()).getTargetAt(modelRow);
        }

        boolean isOffline = (target != null && target.getStatus() == ServerStatus.OFFLINE);
        boolean isPaused = (target != null && (!target.isEnabled() || target.getStatus() == ServerStatus.PAUSED));

        // Màu nền hàng theo trạng thái khi không được chọn (!isSelected)
        Color rowBg;
        if (isSelected) {
            rowBg = ThemeManager.bgSelected();
        } else if (target != null && target.getStatus() == ServerStatus.WARNING) {
            rowBg = ThemeManager.isDarkMode() ? new Color(0x33, 0x27, 0x14) : new Color(0xFF, 0xFB, 0xEB);
        } else if (target != null && target.getStatus() == ServerStatus.OFFLINE) {
            rowBg = ThemeManager.isDarkMode() ? new Color(0x3B, 0x1A, 0x1A) : new Color(0xFE, 0xF2, 0xF2);
        } else {
            rowBg = ThemeManager.bgSurface();
        }
        c.setBackground(rowBg);

        // Kiểm tra nháy nền khi vừa đổi trạng thái (600ms)
        if (target != null && flashTimestamps.containsKey(target.getId())) {
            long diff = System.currentTimeMillis() - flashTimestamps.get(target.getId());
            if (diff < 600) {
                Color flashColor = ThemeManager.accent();
                int alpha = (int) (40 * (1.0 - (double) diff / 600));
                rowBg = new Color(flashColor.getRed(), flashColor.getGreen(), flashColor.getBlue(), alpha);
                c.setBackground(rowBg);
            } else {
                flashTimestamps.remove(target.getId());
            }
        }

        // Căn chỉnh và định dạng từng cột
        int modelCol = table.convertColumnIndexToModel(column);
        switch (modelCol) {
            case 0: // Checkbox chọn hàng (Chuẩn mẫu Ảnh 2)
                JPanel chkWrap = new JPanel(new GridBagLayout());
                chkWrap.setOpaque(true);
                chkWrap.setBackground(rowBg);
                if (isSelected) {
                    chkWrap.setBorder(BorderFactory.createMatteBorder(0, UIScale.scale(3), 0, 0, ThemeManager.accent()));
                }
                JCheckBox chk = new JCheckBox();
                chk.setOpaque(false);
                chk.setSelected(isSelected);
                chk.setFocusable(false);
                chk.setEnabled(false);
                chk.putClientProperty("FlatLaf.style", "arc: 4;");
                chkWrap.add(chk);
                return chkWrap;

            case 1: // Máy chủ
                c.setIcon(null);
                c.setHorizontalAlignment(SwingConstants.LEFT);
                c.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(6), 0, UIScale.scale(4)));
                c.setFont(ThemeManager.sans(Font.BOLD, 12));
                c.setForeground(isPaused ? ThemeManager.textMuted() : ThemeManager.textPrimary());
                String nameStr = value != null ? value.toString() : "";
                if (target != null && target.isFlapping()) {
                    c.setIcon(ThemeManager.getIcon("zap", 14));
                    c.setText(nameStr + " ⚡");
                    c.setToolTipText(String.format("%s - Đang bị rung lắc mạng (Flapping: %.1f%%) - Tạm gộp cảnh báo", nameStr, target.getFlappingRatio()));
                } else {
                    if (target != null && (target.getProtocol() == ProtocolType.HTTPS || target.getProtocol() == ProtocolType.HTTP)) {
                        c.setIcon(ThemeManager.getIcon("globe", 14));
                    } else if (target != null && target.getName() != null && target.getName().toLowerCase().contains("router")) {
                        c.setIcon(ThemeManager.getIcon("wifi", 14));
                    } else {
                        c.setIcon(ThemeManager.getIcon("server", 14));
                    }
                    c.setText(nameStr);
                    c.setToolTipText(nameStr);
                }
                c.setIconTextGap(UIScale.scale(6));
                break;

            case 2: // Địa chỉ (host:port) — Cắt giữa (middle-ellipsis) giữ cổng và tooltip đầy đủ (ERRATA §E13 #2)
                c.setIcon(null);
                c.setHorizontalAlignment(SwingConstants.LEFT);
                c.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4), 0, UIScale.scale(4)));
                c.setFont(ThemeManager.mono(Font.PLAIN, 12));
                c.setForeground(isPaused ? ThemeManager.textMuted() : ThemeManager.textSecondary());
                String addrStr = value != null ? value.toString() : "";
                c.setToolTipText(addrStr.isEmpty() ? null : addrStr);
                int colWidth = table.getColumnModel().getColumn(column).getWidth();
                c.setText(formatAddressMiddleEllipsis(addrStr, c.getFontMetrics(c.getFont()), colWidth - UIScale.scale(8)));
                break;

            case 3: // Giao thức (Chip, hỗ trợ chỉ báo ICMP* khi fallback)
                return createProtocolChip(target, value instanceof ProtocolType ? (ProtocolType) value : null, rowBg, isOffline);

            case 4: // Trạm đo (Agent Badge)
                return createAgentBadge(target, rowBg, isOffline);

            case 5: // Trạng thái (Badge)
                return createStatusBadge(target, rowBg, isOffline);

            case 6: // Độ trễ
                c.setIcon(null);
                c.setHorizontalAlignment(SwingConstants.CENTER);
                c.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(2), 0, UIScale.scale(2)));
                c.setFont(ThemeManager.mono(Font.PLAIN, 12));
                if (target != null && target.isEnabled() && target.getStatus() != ServerStatus.PAUSED && target.getCurrentLatency() >= 0) {
                    long lat = target.getCurrentLatency();
                    long thresh = target.getWarningThresholdMs() > 0 ? target.getWarningThresholdMs() : warningLatencyThreshold;
                    c.setText(lat + " ms");
                    if (lat > thresh) {
                        c.setForeground(ThemeManager.statusWarning());
                    } else {
                        c.setForeground(ThemeManager.textPrimary());
                    }
                } else {
                    c.setText("—");
                    c.setForeground(ThemeManager.textMuted());
                }
                break;

            case 7: // Dải 60 nhịp tim (Heartbeat Bar kiểu Uptime Kuma)
                return heartbeatRenderer.getTableCellRendererComponent(table, target, isSelected, hasFocus, row, column);

            case 8: // Khả dụng (Uptime %)
                c.setIcon(null);
                c.setHorizontalAlignment(SwingConstants.CENTER);
                c.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(2), 0, UIScale.scale(2)));
                c.setFont(ThemeManager.mono(Font.PLAIN, 12));
                if (target != null && target.isEnabled()) {
                    c.setText(String.format("%.1f%%", target.getUptime24h()).replace('.', ','));
                    c.setForeground(target.getUptime24h() >= 99.0 ? ThemeManager.statusOnline() : ThemeManager.textPrimary());
                } else {
                    c.setText("—");
                    c.setForeground(ThemeManager.textMuted());
                }
                break;

            case 9: // Giám sát (Toggle Switch)
                boolean enabled = Boolean.TRUE.equals(value);
                return createSwitchCell(enabled, rowBg, isOffline);

            default:
                c.setText(value != null ? value.toString() : "");
                break;
        }

        // Vẽ vạch trái 3 px accent khi hàng đang được chọn
        if (isSelected && column == 0) {
            JPanel stripePanel = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(ThemeManager.accent());
                    g2.fillRect(0, 0, UIScale.scale(3), getHeight());
                    g2.dispose();
                }
            };
            stripePanel.setBackground(c.getBackground());
            stripePanel.add(c, BorderLayout.CENTER);
            return stripePanel;
        }

        return c;
    }

    private JComponent createProtocolChip(ServerTarget target, ProtocolType protocol, Color rowBg, boolean isOffline) {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int arc = UIScale.scale(6);
                int padX = UIScale.scale(8);
                int padY = UIScale.scale(8);
                int w = getWidth() - (padX * 2);
                int h = getHeight() - (padY * 2);

                g2.setColor(ThemeManager.bgSurfaceAlt());
                g2.fillRoundRect(padX, padY, w, h, arc, arc);

                g2.setColor(ThemeManager.border());
                g2.drawRoundRect(padX, padY, w, h, arc, arc);

                g2.dispose();
            }
        };
        p.setOpaque(true);
        p.setBackground(rowBg);

        boolean isFallback = (target != null && target.isIcmpFallback());
        String protoText = (protocol != null ? protocol.name() : "");
        if (isFallback) {
            protoText = "ICMP*";
        }

        JLabel lbl = new JLabel(protoText);
        lbl.setFont(ThemeManager.mono(Font.BOLD, 11));
        lbl.setForeground(ThemeManager.textSecondary());
        if (isFallback) {
            String tip = "ICMP fallback qua TCP (cổng " + (target.getPort() > 0 ? target.getPort() : 80) + ")";
            p.setToolTipText(tip);
            lbl.setToolTipText(tip);
        }
        p.add(lbl);
        return p;
    }

    private JComponent createAgentBadge(ServerTarget target, Color rowBg, boolean isOffline) {
        boolean dark = ThemeManager.isDarkMode();
        String text;
        Icon icon;
        Color fg;
        Color bg;
        Color borderCol;
        String tooltip;

        if (target != null && target.getScope() == com.networkalert.model.TargetScope.SERVER) {
            text = "Server";
            icon = ThemeManager.getIcon("server", 12);
            fg = dark ? new Color(0x94, 0xA3, 0xB8) : new Color(0x47, 0x55, 0x69);
            bg = dark ? new Color(0x1E, 0x29, 0x3B) : new Color(0xF1, 0xF5, 0xF9);
            borderCol = dark ? new Color(0x47, 0x55, 0x69, 90) : new Color(0xCB, 0xD5, 0xE1);
            tooltip = "Đo trực tiếp từ Máy chủ Trung tâm (Server)";
        } else if (target != null && target.getScope() == com.networkalert.model.TargetScope.MULTI) {
            text = target.getQuorumSummary();
            icon = ThemeManager.getIcon("activity", 12);
            boolean quorumOk = target.getStatus() == ServerStatus.ONLINE || target.getStatus() == ServerStatus.WARNING;
            if (quorumOk) {
                fg = dark ? new Color(0x34, 0xD3, 0x99) : new Color(0x15, 0x80, 0x3D);
                bg = dark ? new Color(0x06, 0x4E, 0x3B) : new Color(0xDC, 0xFC, 0xE7);
                borderCol = dark ? new Color(0x10, 0xB9, 0x81, 80) : new Color(0x86, 0xEF, 0xAC);
            } else {
                fg = dark ? new Color(0xF8, 0x71, 0x71) : new Color(0xDC, 0x26, 0x26);
                bg = dark ? new Color(0x45, 0x0A, 0x0A) : new Color(0xFE, 0xE2, 0xE2);
                borderCol = dark ? new Color(0xEF, 0x44, 0x44, 80) : new Color(0xFC, 0xA5, 0xA5);
            }
            StringBuilder tipSb = new StringBuilder("<html><b>Đo đa trạm (Quorum " + text + "):</b>");
            Map<String, com.networkalert.model.CheckResult> results = target.getStationResults();
            if (results != null && !results.isEmpty()) {
                for (Map.Entry<String, com.networkalert.model.CheckResult> entry : results.entrySet()) {
                    boolean ok = entry.getValue().isSuccess();
                    tipSb.append("<br>• ").append(entry.getKey()).append(": ")
                            .append(ok ? entry.getValue().getLatencyMs() + " ms (OK)" : "Mất kết nối");
                }
            }
            tipSb.append("</html>");
            tooltip = tipSb.toString();
        } else {
            String agentId = target != null ? target.getAssignedAgent() : null;
            if (agentId == null || agentId.trim().isEmpty() || "null".equalsIgnoreCase(agentId)) {
                text = "Nội bộ";
                icon = ThemeManager.getIcon("server", 12);
                fg = dark ? new Color(0x94, 0xA3, 0xB8) : new Color(0x47, 0x55, 0x69);
                bg = dark ? new Color(0x1E, 0x29, 0x3B) : new Color(0xF1, 0xF5, 0xF9);
                borderCol = dark ? new Color(0x47, 0x55, 0x69, 90) : new Color(0xCB, 0xD5, 0xE1);
                tooltip = "Đo từ trạm nội bộ (Local)";
            } else {
                text = agentId;
                icon = ThemeManager.getIcon("radio", 12);
                fg = dark ? new Color(0x38, 0xBD, 0xF8) : new Color(0x03, 0x69, 0xA1);
                bg = dark ? new Color(0x0F, 0x29, 0x42) : new Color(0xE0, 0xF2, 0xFE);
                borderCol = dark ? new Color(0x02, 0x84, 0xC7, 80) : new Color(0x7D, 0xD3, 0xFC);
                tooltip = "Đo từ trạm Agent từ xa: " + agentId;
            }
        }

        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int arc = UIScale.scale(6);
                int padX = UIScale.scale(5);
                int padY = UIScale.scale(7);
                int w = Math.max(10, getWidth() - (padX * 2));
                int h = Math.max(10, getHeight() - (padY * 2));

                g2.setColor(bg);
                g2.fillRoundRect(padX, padY, w, h, arc, arc);

                g2.setColor(borderCol);
                g2.drawRoundRect(padX, padY, w, h, arc, arc);

                g2.dispose();
            }
        };
        p.setOpaque(true);
        p.setBackground(rowBg);
        p.setToolTipText(tooltip);

        JLabel lbl = new JLabel(text, icon, SwingConstants.CENTER);
        lbl.setFont(ThemeManager.sans(Font.BOLD, 11));
        lbl.setForeground(fg);
        lbl.setIconTextGap(UIScale.scale(4));
        lbl.setToolTipText(tooltip);
        p.add(lbl);
        return p;
    }

    private JComponent createStatusBadge(ServerTarget target, Color rowBg, boolean isOffline) {
        ServerStatus status = target != null ? target.getStatus() : null;
        Color statusColor;
        Color textColor;
        String text;

        if (status == ServerStatus.UNREACHABLE) {
            statusColor = ThemeManager.statusUnreachable();
            textColor = ThemeManager.textMuted();
            text = "Không tới được";
        } else if (status == ServerStatus.MAINTENANCE) {
            statusColor = ThemeManager.statusMaintenance();
            textColor = ThemeManager.statusMaintenanceText();
            text = "Bảo trì";
        } else if (status == ServerStatus.ONLINE) {
            statusColor = ThemeManager.statusOnline();
            textColor = ThemeManager.statusOnlineText();
            text = "Trực tuyến";
        } else if (status == ServerStatus.WARNING) {
            statusColor = ThemeManager.statusWarning();
            textColor = ThemeManager.statusWarningText();
            text = "Cảnh báo";
        } else if (status == ServerStatus.OFFLINE) {
            statusColor = ThemeManager.statusOffline();
            textColor = ThemeManager.statusOfflineText();
            text = "Ngoại tuyến";
        } else {
            statusColor = ThemeManager.statusNeutral();
            textColor = ThemeManager.textMuted();
            text = "Tạm dừng";
        }

        // Hiển thị lần thử SOFT (lần 1/3, 2/3) theo chuẩn Nagios
        if (target != null && target.getStateType() == com.networkalert.model.StateType.SOFT) {
            text = text + " (" + target.getCurrentAttempt() + "/" + target.getMaxCheckAttempts() + ")";
        }

        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int arc = UIScale.scale(6);
                int padX = UIScale.scale(4);
                int padY = UIScale.scale(7);
                int w = Math.max(10, getWidth() - (padX * 2));
                int h = Math.max(10, getHeight() - (padY * 2));

                // Nền alpha dịu 14%
                boolean dark = ThemeManager.isDarkMode();
                int bgAlpha = dark ? 28 : 22;
                g2.setColor(new Color(statusColor.getRed(), statusColor.getGreen(), statusColor.getBlue(), bgAlpha));
                g2.fillRoundRect(padX, padY, w, h, arc, arc);

                // Viền alpha nhẹ
                g2.setColor(new Color(statusColor.getRed(), statusColor.getGreen(), statusColor.getBlue(), 75));
                g2.drawRoundRect(padX, padY, w, h, arc, arc);

                // Chấm tròn trạng thái 6 px
                int dotSize = UIScale.scale(6);
                int dotX = padX + UIScale.scale(7);
                int dotY = (getHeight() - dotSize) / 2;
                g2.setColor(statusColor);
                g2.fillOval(dotX, dotY, dotSize, dotSize);

                g2.dispose();
            }
        };
        p.setOpaque(true);
        p.setBackground(rowBg);

        JLabel lbl = new JLabel(text);
        lbl.setFont(ThemeManager.sans(Font.BOLD, 11));
        lbl.setForeground(textColor);
        lbl.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(13), 0, UIScale.scale(2)));
        p.add(lbl);
        return p;
    }

    private JComponent createSwitchCell(boolean enabled, Color rowBg, boolean isOffline) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(true);
        p.setBackground(rowBg);

        ToggleSwitch sw = new ToggleSwitch(enabled);
        sw.setEnabled(false); // Dùng cho cell rendering (tương tác qua CellEditor)
        p.add(sw);
        return p;
    }

    private String formatTimeAgo(LocalDateTime time) {
        if (time == null) return "Chưa kiểm tra";
        Duration d = Duration.between(time, LocalDateTime.now(java.time.Clock.systemUTC()));
        long sec = Math.max(0, d.getSeconds());
        if (sec < 60) {
            return sec + " giây trước";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + " phút trước";
        }
        long hours = min / 60;
        return hours + " giờ trước";
    }

    /**
     * Định dạng địa chỉ theo cơ chế cắt giữa (middle-ellipsis), giữ nguyên cổng dịch vụ :port (ERRATA §E13 #2).
     */
    public static String formatAddressMiddleEllipsis(String address, FontMetrics fm, int availableWidth) {
        if (address == null || address.isEmpty()) {
            return "";
        }
        if (availableWidth <= 0 || fm == null || fm.stringWidth(address) <= availableWidth) {
            return address;
        }
        String ellipsis = "...";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        if (ellipsisWidth >= availableWidth) {
            return address;
        }

        int colonIdx = address.lastIndexOf(':');
        String suffix;
        String prefix;
        if (colonIdx > 0 && colonIdx < address.length()) {
            suffix = address.substring(colonIdx); // ví dụ: ":9000"
            prefix = address.substring(0, colonIdx);
        } else {
            int cutLen = Math.min(6, address.length() / 2);
            suffix = address.substring(address.length() - cutLen);
            prefix = address.substring(0, address.length() - cutLen);
        }

        while (!prefix.isEmpty() && fm.stringWidth(prefix + ellipsis + suffix) > availableWidth) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (prefix.isEmpty()) {
            return ellipsis + suffix;
        }
        return prefix + ellipsis + suffix;
    }
}

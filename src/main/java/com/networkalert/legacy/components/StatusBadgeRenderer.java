package com.networkalert.legacy.components;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Renderer vẽ các nhãn trạng thái (Badge) bo góc mềm mại cho JTable
 */
public class StatusBadgeRenderer extends DefaultTableCellRenderer {

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int column) {
        JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setOpaque(true);

        if (value instanceof ServerStatus) {
            ServerStatus status = (ServerStatus) value;
            label.setText("  " + status.getLabel() + "  ");
            label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
            label.setBackground(status.getPrimaryColor());
            label.setForeground(Color.WHITE);
            label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return new BadgePanel(label.getText(), status.getPrimaryColor(), Color.WHITE);
        } else if (value instanceof ProtocolType) {
            ProtocolType proto = (ProtocolType) value;
            Color bgColor = getProtocolColor(proto);
            return new BadgePanel(proto.name(), bgColor, Color.WHITE);
        } else if (value instanceof Long) {
            long latency = (Long) value;
            if (latency < 0) {
                label.setText("N/A");
                label.setForeground(new Color(150, 150, 150));
            } else {
                label.setText(latency + " ms");
                if (latency < 100) {
                    label.setForeground(new Color(46, 204, 113));
                } else if (latency < 300) {
                    label.setForeground(new Color(241, 196, 15));
                } else {
                    label.setForeground(new Color(231, 76, 60));
                }
            }
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            if (!isSelected) {
                label.setBackground(table.getBackground());
            }
            return label;
        } else if (value instanceof Double) {
            double uptime = (Double) value;
            label.setText(String.format("%.1f%%", uptime));
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            if (uptime >= 95.0) {
                label.setForeground(new Color(46, 204, 113));
            } else if (uptime >= 80.0) {
                label.setForeground(new Color(241, 196, 15));
            } else {
                label.setForeground(new Color(231, 76, 60));
            }
            if (!isSelected) {
                label.setBackground(table.getBackground());
            }
            return label;
        }

        return label;
    }

    private Color getProtocolColor(ProtocolType proto) {
        switch (proto) {
            case TCP: return new Color(41, 128, 185);
            case UDP: return new Color(22, 160, 133);
            case HTTP: return new Color(39, 174, 96);
            case HTTPS: return new Color(24, 188, 156);
            case ICMP: return new Color(142, 68, 173);
            case DNS: return new Color(230, 126, 34);
            default: return new Color(127, 140, 141);
        }
    }

    /**
     * Component vẽ pill badge bo tròn
     */
    private static class BadgePanel extends JPanel {
        private final String text;
        private final Color bgColor;
        private final Color textColor;

        public BadgePanel(String text, Color bgColor, Color textColor) {
            this.text = text;
            this.bgColor = bgColor;
            this.textColor = textColor;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int badgeWidth = Math.min(width - 8, 90);
            int badgeHeight = Math.min(height - 6, 22);
            int x = (width - badgeWidth) / 2;
            int y = (height - badgeHeight) / 2;

            g2.setColor(bgColor);
            g2.fillRoundRect(x, y, badgeWidth, badgeHeight, 12, 12);

            g2.setColor(textColor);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();
            int strX = x + (badgeWidth - fm.stringWidth(text)) / 2;
            int strY = y + ((badgeHeight - fm.getHeight()) / 2) + fm.getAscent();
            g2.drawString(text, strX, strY);

            g2.dispose();
        }
    }
}

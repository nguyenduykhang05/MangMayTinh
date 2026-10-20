package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Thanh tỉ lệ dải sức khỏe hệ thống (cao 8 px, bo góc 4 px, chia đoạn phân màu)
 */
public class HealthRatioBar extends JComponent {
    private int onlineCount = 0;
    private int warningCount = 0;
    private int offlineCount = 0;
    private int pausedCount = 0;

    private Consumer<String> segmentClickListener;

    public HealthRatioBar() {
        setPreferredSize(new Dimension(280, UIScale.scale(6)));
        setMinimumSize(new Dimension(100, UIScale.scale(6)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (segmentClickListener == null) return;
                String clickedType = getSegmentAt(e.getX());
                if (clickedType != null) {
                    segmentClickListener.accept(clickedType);
                }
            }
        });
    }

    public void setCounts(int online, int warning, int offline, int paused) {
        this.onlineCount = online;
        this.warningCount = warning;
        this.offlineCount = offline;
        this.pausedCount = paused;

        int total = online + warning + offline + paused;
        if (total == 0) {
            setToolTipText("Chưa có máy chủ");
        } else {
            setToolTipText(String.format("Online: %d | Cảnh báo: %d | Offline: %d | Tạm tắt: %d (Bấm để lọc)",
                    online, warning, offline, paused));
        }
        repaint();
    }

    public void setOnSegmentClick(Consumer<String> listener) {
        this.segmentClickListener = listener;
    }

    private String getSegmentAt(int x) {
        int total = onlineCount + warningCount + offlineCount + pausedCount;
        if (total == 0) return null;

        int w = getWidth();
        int gap = UIScale.scale(2);

        int activeSegments = 0;
        if (onlineCount > 0) activeSegments++;
        if (warningCount > 0) activeSegments++;
        if (offlineCount > 0) activeSegments++;
        if (pausedCount > 0) activeSegments++;

        int totalGaps = Math.max(0, activeSegments - 1) * gap;
        int availableW = Math.max(0, w - totalGaps);

        int curX = 0;

        // Check online
        if (onlineCount > 0) {
            int segW = (int) Math.round((double) onlineCount / total * availableW);
            if (x >= curX && x <= curX + segW) return "ONLINE";
            curX += segW + gap;
        }
        // Check warning
        if (warningCount > 0) {
            int segW = (int) Math.round((double) warningCount / total * availableW);
            if (x >= curX && x <= curX + segW) return "WARNING";
            curX += segW + gap;
        }
        // Check offline
        if (offlineCount > 0) {
            int segW = (int) Math.round((double) offlineCount / total * availableW);
            if (x >= curX && x <= curX + segW) return "OFFLINE";
            curX += segW + gap;
        }
        // Check paused
        if (pausedCount > 0) {
            int segW = (int) Math.round((double) pausedCount / total * availableW);
            if (x >= curX && x <= curX + segW) return "PAUSED";
        }

        return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int arc = UIScale.scale(3);
        int gap = UIScale.scale(2);

        // Nền rãnh thanh tỉ lệ
        g2.setColor(ThemeManager.bgSurfaceAlt());
        g2.fillRoundRect(0, 0, w, h, arc, arc);

        int total = onlineCount + warningCount + offlineCount + pausedCount;
        if (total == 0) {
            g2.dispose();
            return;
        }

        int activeSegments = 0;
        if (onlineCount > 0) activeSegments++;
        if (warningCount > 0) activeSegments++;
        if (offlineCount > 0) activeSegments++;
        if (pausedCount > 0) activeSegments++;

        int totalGaps = Math.max(0, activeSegments - 1) * gap;
        int availableW = Math.max(0, w - totalGaps);

        int curX = 0;

        // Draw segments
        if (onlineCount > 0) {
            int segW = Math.max(UIScale.scale(4), (int) Math.round((double) onlineCount / total * availableW));
            g2.setColor(ThemeManager.statusOnline());
            g2.fillRoundRect(curX, 0, segW, h, arc, arc);
            curX += segW + gap;
        }
        if (warningCount > 0) {
            int segW = Math.max(UIScale.scale(4), (int) Math.round((double) warningCount / total * availableW));
            g2.setColor(ThemeManager.statusWarning());
            g2.fillRoundRect(curX, 0, segW, h, arc, arc);
            curX += segW + gap;
        }
        if (offlineCount > 0) {
            int segW = Math.max(UIScale.scale(4), (int) Math.round((double) offlineCount / total * availableW));
            g2.setColor(ThemeManager.statusOffline());
            g2.fillRoundRect(curX, 0, segW, h, arc, arc);
            curX += segW + gap;
        }
        if (pausedCount > 0) {
            int segW = Math.max(UIScale.scale(4), w - curX);
            g2.setColor(ThemeManager.statusNeutral());
            g2.fillRoundRect(curX, 0, segW, h, arc, arc);
        }

        g2.dispose();
    }
}

package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Công tắc bật/tắt (Toggle Switch) kiểu phẳng, bo tròn hình viên thuốc
 */
public class ToggleSwitch extends JComponent {
    private boolean selected = true;
    private Consumer<Boolean> onToggleListener;
    private float thumbPosition = 1.0f; // 0.0f = OFF, 1.0f = ON
    private Timer animTimer;

    public ToggleSwitch() {
        this(true);
    }

    public ToggleSwitch(boolean initialSelected) {
        this.selected = initialSelected;
        this.thumbPosition = initialSelected ? 1.0f : 0.0f;

        setPreferredSize(new Dimension(UIScale.scale(38), UIScale.scale(20)));
        setMinimumSize(new Dimension(UIScale.scale(38), UIScale.scale(20)));
        setMaximumSize(new Dimension(UIScale.scale(38), UIScale.scale(20)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!isEnabled()) return;
                setSelected(!selected);
                if (onToggleListener != null) {
                    onToggleListener.accept(selected);
                }
            }
        });
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean sel) {
        if (this.selected == sel) return;
        this.selected = sel;
        animateTo(sel ? 1.0f : 0.0f);
    }

    public void setSelectedWithoutAnimation(boolean sel) {
        this.selected = sel;
        this.thumbPosition = sel ? 1.0f : 0.0f;
        repaint();
    }

    public void setOnToggle(Consumer<Boolean> listener) {
        this.onToggleListener = listener;
    }

    private void animateTo(float target) {
        if (animTimer != null && animTimer.isRunning()) {
            animTimer.stop();
        }

        animTimer = new Timer(15, e -> {
            float step = 0.2f;
            if (thumbPosition < target) {
                thumbPosition = Math.min(target, thumbPosition + step);
            } else {
                thumbPosition = Math.max(target, thumbPosition - step);
            }
            repaint();
            if (Math.abs(thumbPosition - target) < 0.01f) {
                thumbPosition = target;
                animTimer.stop();
                repaint();
            }
        });
        animTimer.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int arc = h;

        // Màu nền công tắc
        Color offColor = ThemeManager.bgSurfaceAlt();
        Color onColor = ThemeManager.accent();

        int r = (int) (offColor.getRed() + (onColor.getRed() - offColor.getRed()) * thumbPosition);
        int gr = (int) (offColor.getGreen() + (onColor.getGreen() - offColor.getGreen()) * thumbPosition);
        int b = (int) (offColor.getBlue() + (onColor.getBlue() - offColor.getBlue()) * thumbPosition);
        Color trackColor = new Color(r, gr, b);

        g2.setColor(trackColor);
        g2.fillRoundRect(0, 0, w, h, arc, arc);

        g2.setColor(ThemeManager.border());
        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

        // Nút trượt (Thumb)
        int thumbPad = UIScale.scale(2);
        int thumbSize = h - (thumbPad * 2);
        int travel = w - thumbSize - (thumbPad * 2);
        int thumbX = thumbPad + (int) (travel * thumbPosition);
        int thumbY = thumbPad;

        g2.setColor(Color.WHITE);
        g2.fillOval(thumbX, thumbY, thumbSize, thumbSize);

        g2.dispose();
    }
}

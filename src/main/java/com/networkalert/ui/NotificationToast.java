package com.networkalert.ui;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Toast thông báo góc dưới phải màn hình theo mục 5.8:
 * Rộng 340 px, tự ẩn sau 6 giây, tối đa 3 cái xếp chồng, vạch màu bên trái theo mức độ.
 */
public class NotificationToast extends JWindow {
    private static final int TOAST_WIDTH = 360;
    private static final int TOAST_HEIGHT = 78;
    private static final int MAX_STACK = 3;

    private static final List<NotificationToast> ACTIVE_TOASTS = new ArrayList<>();

    public static synchronized void show(String title, String message, boolean isError) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Nếu vượt quá MAX_STACK, đóng cái cũ nhất
                if (ACTIVE_TOASTS.size() >= MAX_STACK) {
                    NotificationToast oldest = ACTIVE_TOASTS.remove(0);
                    oldest.dispose();
                }

                Color stripeColor;
                if (isError) {
                    stripeColor = ThemeManager.statusOffline();
                } else if (title.contains("CẢNH BÁO") || title.contains("CHẬM")) {
                    stripeColor = ThemeManager.statusWarning();
                } else {
                    stripeColor = ThemeManager.statusOnline();
                }

                NotificationToast toast = new NotificationToast(title, message, stripeColor);
                ACTIVE_TOASTS.add(toast);
                repositionToasts();
                toast.setVisible(true);
            } catch (Exception ignored) {}
        });
    }

    private static synchronized void repositionToasts() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle screenBounds = ge.getMaximumWindowBounds();

        int baseY = screenBounds.y + screenBounds.height - UIScale.scale(TOAST_HEIGHT) - UIScale.scale(30);
        int baseX = screenBounds.x + screenBounds.width - UIScale.scale(TOAST_WIDTH) - UIScale.scale(20);

        for (int i = ACTIVE_TOASTS.size() - 1; i >= 0; i--) {
            NotificationToast t = ACTIVE_TOASTS.get(i);
            int offsetIndex = ACTIVE_TOASTS.size() - 1 - i;
            int y = baseY - (offsetIndex * (UIScale.scale(TOAST_HEIGHT) + UIScale.scale(8)));
            t.setLocation(baseX, y);
        }
    }

    private synchronized void dismiss() {
        ACTIVE_TOASTS.remove(this);
        dispose();
        repositionToasts();
    }

    private NotificationToast(String title, String message, Color stripeColor) {
        setSize(UIScale.scale(TOAST_WIDTH), UIScale.scale(TOAST_HEIGHT));
        setAlwaysOnTop(true);

        JPanel panel = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int arc = UIScale.scale(8);
                g2.setColor(ThemeManager.bgSurface());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

                g2.setColor(ThemeManager.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);

                // Vạch màu mức độ bên trái (4 px)
                g2.setColor(stripeColor);
                g2.fillRoundRect(0, 0, UIScale.scale(4), getHeight(), arc, arc);

                g2.dispose();
                super.paintComponent(g);
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 14));
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Nội dung thông báo
        JPanel contentPanel = new JPanel(new BorderLayout(0, 4));
        contentPanel.setOpaque(false);

        JLabel lblTitle = new JLabel(title);
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 13));
        lblTitle.setForeground(ThemeManager.textPrimary());

        JTextArea txtMsg = new JTextArea(message);
        txtMsg.setFont(ThemeManager.sans(Font.PLAIN, 12));
        txtMsg.setForeground(ThemeManager.textSecondary());
        txtMsg.setLineWrap(true);
        txtMsg.setWrapStyleWord(true);
        txtMsg.setEditable(false);
        txtMsg.setFocusable(false);
        txtMsg.setOpaque(false);
        txtMsg.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        txtMsg.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dismiss();
            }
        });

        contentPanel.add(lblTitle, BorderLayout.NORTH);
        contentPanel.add(txtMsg, BorderLayout.CENTER);
        panel.add(contentPanel, BorderLayout.CENTER);

        add(panel);

        // Click để đóng
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dismiss();
            }
        });

        // Tự ẩn sau 6 giây
        Timer autoHideTimer = new Timer(6000, e -> dismiss());
        autoHideTimer.setRepeats(false);
        autoHideTimer.start();
    }
}

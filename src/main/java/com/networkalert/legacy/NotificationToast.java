package com.networkalert.legacy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Toast Popup nổi ở góc dưới màn hình thông báo cảnh báo mạng tức thời
 */
public class NotificationToast extends JWindow {
    private static final int WIDTH = 340;
    private static final int HEIGHT = 90;

    public static void show(String title, String message, boolean isError) {
        SwingUtilities.invokeLater(() -> {
            try {
                NotificationToast toast = new NotificationToast(title, message, isError);
                toast.setVisible(true);
            } catch (Exception ignored) {}
        });
    }

    private NotificationToast(String title, String message, boolean isError) {
        setSize(WIDTH, HEIGHT);
        setAlwaysOnTop(true);

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle screenBounds = ge.getMaximumWindowBounds();
        int x = screenBounds.x + screenBounds.width - WIDTH - 20;
        int y = screenBounds.y + screenBounds.height - HEIGHT - 20;
        setLocation(x, y);

        JPanel panel = new JPanel(new BorderLayout(10, 5)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = isError ? new Color(192, 57, 43) : new Color(39, 174, 96);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.setColor(new Color(255, 255, 255, 60));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        JLabel iconLabel = new JLabel(isError ? "⚠" : "✔");
        iconLabel.setFont(new Font("Segoe UI", Font.BOLD, 28));
        iconLabel.setForeground(Color.WHITE);
        panel.add(iconLabel, BorderLayout.WEST);

        JPanel contentPanel = new JPanel(new GridLayout(2, 1, 2, 2));
        contentPanel.setOpaque(false);

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 14));
        titleLbl.setForeground(Color.WHITE);

        JLabel msgLbl = new JLabel("<html><body style='width: 220px'>" + message + "</body></html>");
        msgLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        msgLbl.setForeground(new Color(240, 240, 240));

        contentPanel.add(titleLbl);
        contentPanel.add(msgLbl);
        panel.add(contentPanel, BorderLayout.CENTER);

        add(panel);

        // Click vào toast để đóng
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dispose();
            }
        });

        // Tự động đóng sau 4.5 giây
        Timer timer = new Timer(4500, e -> dispose());
        timer.setRepeats(false);
        timer.start();
    }
}

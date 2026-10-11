package com.networkalert.legacy.components;

import com.networkalert.model.ServerTarget;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.GeneralPath;
import java.util.List;

/**
 * Biểu đồ vẽ diễn biến độ trễ (Latency Chart) thời gian thực bằng Java Graphics2D
 */
public class LatencyChartPanel extends JPanel {
    private ServerTarget currentTarget;
    private long warningThreshold = 300;

    public LatencyChartPanel() {
        setPreferredSize(new Dimension(380, 220));
        setMinimumSize(new Dimension(250, 160));
    }

    public void updateTarget(ServerTarget target, long warningThreshold) {
        this.currentTarget = target;
        this.warningThreshold = warningThreshold;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();

        // Nền biểu đồ (Dark theme container)
        Color bgCard = UIManager.getColor("Panel.background");
        if (bgCard == null) bgCard = new Color(30, 30, 30);
        g2.setColor(bgCard);
        g2.fillRect(0, 0, width, height);

        if (currentTarget == null) {
            drawEmptyState(g2, width, height, "Chọn một máy chủ trong bảng để xem biểu đồ độ trễ");
            g2.dispose();
            return;
        }

        List<Long> latencies = currentTarget.getRecentLatencies();
        if (latencies.isEmpty()) {
            drawEmptyState(g2, width, height, "Đang chờ dữ liệu đo lường đầu tiên...");
            g2.dispose();
            return;
        }

        // Thống kê Min / Avg / Max
        long min = Long.MAX_VALUE;
        long max = 0;
        long sum = 0;
        for (long l : latencies) {
            if (l > 0) {
                if (l < min) min = l;
                if (l > max) max = l;
                sum += l;
            }
        }
        if (min == Long.MAX_VALUE) min = 0;
        long avg = latencies.isEmpty() ? 0 : sum / latencies.size();

        // Vẽ Banner thống kê trên đầu
        int bannerY = 24;
        g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
        g2.setColor(UIManager.getColor("Label.foreground"));
        g2.drawString("Độ trễ thời gian thực: " + currentTarget.getName(), 15, bannerY);

        g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        String statsText = String.format("Hiện tại: %d ms | Min: %d ms | Avg: %d ms | Max: %d ms",
                currentTarget.getCurrentLatency() >= 0 ? currentTarget.getCurrentLatency() : 0, min, avg, max);
        g2.setColor(new Color(160, 160, 160));
        g2.drawString(statsText, 15, bannerY + 18);

        // Vùng vẽ đồ thị (Plot Area)
        int paddingLeft = 45;
        int paddingRight = 20;
        int paddingTop = 55;
        int paddingBottom = 30;

        int plotX = paddingLeft;
        int plotY = paddingTop;
        int plotW = width - paddingLeft - paddingRight;
        int plotH = height - paddingTop - paddingBottom;

        if (plotW <= 10 || plotH <= 10) {
            g2.dispose();
            return;
        }

        // Xác định thang đo Y (Y-Scale)
        long maxYVal = Math.max(warningThreshold + 50, Math.max(max + 20, 100));

        // Vẽ đường lưới (Grid Lines)
        g2.setColor(new Color(255, 255, 255, 20));
        g2.setStroke(new BasicStroke(1.0f));
        int gridSteps = 4;
        for (int i = 0; i <= gridSteps; i++) {
            int y = plotY + plotH - (i * plotH / gridSteps);
            long val = (i * maxYVal / gridSteps);
            g2.drawLine(plotX, y, plotX + plotW, y);

            g2.setColor(new Color(140, 140, 140));
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            String valStr = val + "ms";
            g2.drawString(valStr, plotX - 38, y + 4);
            g2.setColor(new Color(255, 255, 255, 20));
        }

        // Đường ngưỡng cảnh báo Warning Threshold (Dashed Red Line)
        if (warningThreshold <= maxYVal) {
            int threshY = (int) (plotY + plotH - (warningThreshold * plotH / maxYVal));
            Stroke dashed = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f}, 0.0f);
            g2.setStroke(dashed);
            g2.setColor(new Color(231, 76, 60, 180));
            g2.drawLine(plotX, threshY, plotX + plotW, threshY);
            g2.drawString("Ngưỡng: " + warningThreshold + "ms", plotX + plotW - 90, threshY - 3);
        }

        // Tọa độ các điểm trên đồ thị
        int n = latencies.size();
        Point[] points = new Point[n];
        for (int i = 0; i < n; i++) {
            long l = latencies.get(i);
            int px = n > 1 ? plotX + (i * plotW / (n - 1)) : plotX + plotW / 2;
            int py = (int) (plotY + plotH - (l * plotH / maxYVal));
            points[i] = new Point(px, py);
        }

        // Vẽ vùng diện tích Gradient (Fill Area)
        GeneralPath fillPath = new GeneralPath();
        fillPath.moveTo(points[0].x, plotY + plotH);
        for (Point p : points) {
            fillPath.lineTo(p.x, p.y);
        }
        fillPath.lineTo(points[n - 1].x, plotY + plotH);
        fillPath.closePath();

        GradientPaint gradient = new GradientPaint(
                0, plotY, new Color(46, 204, 113, 100),
                0, plotY + plotH, new Color(46, 204, 113, 10)
        );
        g2.setPaint(gradient);
        g2.fill(fillPath);

        // Vẽ đường nối các điểm (Line)
        g2.setColor(new Color(46, 204, 113));
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        GeneralPath linePath = new GeneralPath();
        linePath.moveTo(points[0].x, points[0].y);
        for (int i = 1; i < n; i++) {
            linePath.lineTo(points[i].x, points[i].y);
        }
        g2.draw(linePath);

        // Vẽ các chấm tròn điểm đo
        for (int i = 0; i < n; i++) {
            Point p = points[i];
            long l = latencies.get(i);
            Color dotColor = (l > warningThreshold) ? new Color(231, 76, 60) :
                    (l > 150 ? new Color(241, 196, 15) : new Color(46, 204, 113));
            g2.setColor(dotColor);
            g2.fillOval(p.x - 3, p.y - 3, 6, 6);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawOval(p.x - 3, p.y - 3, 6, 6);
        }

        g2.dispose();
    }

    private void drawEmptyState(Graphics2D g2, int width, int height, String msg) {
        g2.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        g2.setColor(new Color(130, 130, 130));
        FontMetrics fm = g2.getFontMetrics();
        int strX = (width - fm.stringWidth(msg)) / 2;
        int strY = (height - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(msg, strX, strY);
    }
}

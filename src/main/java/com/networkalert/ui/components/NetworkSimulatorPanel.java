package com.networkalert.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.simulator.MockHttpServer;
import com.networkalert.simulator.MockTcpServer;
import com.networkalert.simulator.MockUdpServer;
import com.networkalert.simulator.scenario.ScenarioRunner;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;
import java.awt.*;
import java.util.function.BiConsumer;

/**
 * Tab Giả lập mạng & Trung tâm Thử nghiệm Trực quan (Interactive Demo Center)
 * Cho phép người dùng chạy demo 1-click để kiểm chứng tức thì lợi ích và hoạt động của hệ thống
 */
public class NetworkSimulatorPanel extends JPanel {
    private final MockTcpServer tcpServer;
    private final MockUdpServer udpServer;
    private final MockHttpServer httpServer;
    private final ScenarioRunner scenarioRunner;
    private BiConsumer<String, String> toastNotifier;

    // Các thành phần điều khiển để đồng bộ khi bấm Kịch bản Demo
    private ToggleSwitch swTcp;
    private ToggleSwitch swUdp;
    private ToggleSwitch swHttp;

    private JLabel chipTcp;
    private JLabel chipUdp;
    private JLabel chipHttp;

    private JSlider delaySliderTcp;
    private JSlider delaySliderHttp;
    private JLabel lblDelayTcp;
    private JLabel lblDelayHttp;

    private JToggleButton btnCode200;
    private JToggleButton btnCode500;
    private JToggleButton btnCode404;

    private JButton btnRunAutoScenario;
    private JButton btnStopAutoScenario;
    private JLabel lblScenarioStatus;

    public NetworkSimulatorPanel(MockTcpServer tcp, MockUdpServer udp, MockHttpServer http,
                                 BiConsumer<String, String> toastNotifier) {
        this.tcpServer = tcp;
        this.udpServer = udp;
        this.httpServer = http;
        this.toastNotifier = toastNotifier;
        this.scenarioRunner = new ScenarioRunner(tcp, udp, http);

        this.scenarioRunner.setStepListener((idx, step) -> {
            SwingUtilities.invokeLater(() -> {
                syncUiControls();
                if (lblScenarioStatus != null) {
                    lblScenarioStatus.setText(String.format("Đang chạy: Bước %d/5 (+%ds): %s", idx + 1, step.getTimeOffsetSeconds(), step.getName()));
                }
                notifyAction("Kịch bản Demo Tự Động", step.getName() + " - " + step.getDescription());
            });
        });
        this.scenarioRunner.setCompletionListener(() -> {
            SwingUtilities.invokeLater(() -> {
                syncUiControls();
                if (lblScenarioStatus != null) {
                    lblScenarioStatus.setText("Trạng thái: Đã hoàn thành 5/5 bước kịch bản demo.");
                }
                if (btnRunAutoScenario != null) btnRunAutoScenario.setEnabled(true);
                if (btnStopAutoScenario != null) btnStopAutoScenario.setEnabled(false);
                notifyAction("Kịch bản Demo Tự Động", "Hoàn thành toàn bộ kịch bản.");
            });
        });

        setLayout(new BorderLayout(0, 12));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // Danh sách các khối dọc
        JPanel contentContainer = new JPanel();
        contentContainer.setLayout(new BoxLayout(contentContainer, BoxLayout.Y_AXIS));
        contentContainer.setOpaque(false);

        // 1. HERO DEMO SHOWCASE (Minh họa trực tiếp bằng demo 1-click)
        contentContainer.add(createDemoShowcaseBlock());
        contentContainer.add(Box.createVerticalStrut(UIScale.scale(12)));

        // 2. CÁC KHỐI ĐIỀU KHIỂN SERVER RIÊNG LẺ
        contentContainer.add(createTcpBlock());
        contentContainer.add(Box.createVerticalStrut(UIScale.scale(10)));
        contentContainer.add(createUdpBlock());
        contentContainer.add(Box.createVerticalStrut(UIScale.scale(10)));
        contentContainer.add(createHttpBlock());

        JScrollPane scrollPane = new JScrollPane(contentContainer);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Khối Hero Showcase: Giúp người xem thấy ngay lợi ích bằng demo 1-click
     */
    private JPanel createDemoShowcaseBlock() {
        JPanel hero = new JPanel(new BorderLayout(0, 10)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = UIScale.scale(8);
                g2.setColor(ThemeManager.bgSurfaceAlt());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.setColor(ThemeManager.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        hero.setOpaque(false);
        hero.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // Tiêu đề & mô tả
        JPanel header = new JPanel(new BorderLayout(0, 3));
        header.setOpaque(false);

        JLabel lblTitle = new JLabel("🎯 KỊCH BẢN THỬ NGHIỆM TRỰC TIẾP (1-CLICK DEMO)");
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 12));
        lblTitle.setForeground(ThemeManager.accent());

        JLabel lblSub = new JLabel("Bấm nhanh kịch bản bên dưới để quan sát hệ thống cảnh báo và phản ứng theo thời gian thực:");
        lblSub.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblSub.setForeground(ThemeManager.textSecondary());

        header.add(lblTitle, BorderLayout.NORTH);
        header.add(lblSub, BorderLayout.SOUTH);
        hero.add(header, BorderLayout.NORTH);

        // Hàng nút kịch bản
        JPanel btnGrid = new JPanel(new GridLayout(3, 2, 8, 8));
        btnGrid.setOpaque(false);

        // Nút 1: Chạy toàn bộ (Xanh)
        JButton btnDemoOnline = new JButton("🟢 Kịch bản 1: Mọi thứ Ổn định");
        styleScenarioButton(btnDemoOnline);
        btnDemoOnline.setToolTipText("Khởi chạy toàn bộ TCP, UDP, HTTP (trễ 0ms) -> Trạng thái xanh hoàn toàn");
        btnDemoOnline.addActionListener(e -> runScenarioAllOnline());

        // Nút 2: Giả lập nghẽn mạng (Vàng)
        JButton btnDemoWarning = new JButton("🟡 Kịch bản 2: Giả lập Nghẽn mạng");
        styleScenarioButton(btnDemoWarning);
        btnDemoWarning.setToolTipText("Đẩy độ trễ HTTP lên 600ms (vượt ngưỡng 300ms) -> Kích hoạt cảnh báo vàng");
        btnDemoWarning.addActionListener(e -> runScenarioLatencyWarning());

        // Nút 3: Giả lập sập server (Đỏ)
        JButton btnDemoOffline = new JButton("🔴 Kịch bản 3: Giả lập Sập máy chủ");
        styleScenarioButton(btnDemoOffline);
        btnDemoOffline.setToolTipText("Ngắt Mock TCP Server (Port 9000) -> Kích hoạt cảnh báo đỏ & thông báo Toast");
        btnDemoOffline.addActionListener(e -> runScenarioServerOutage());

        // Nút 4: Khôi phục
        JButton btnDemoReset = new JButton("🔄 Khôi phục mặc định");
        styleScenarioButton(btnDemoReset);
        btnDemoReset.setToolTipText("Bật lại toàn bộ server và đưa độ trễ về 0ms");
        btnDemoReset.addActionListener(e -> runScenarioAllOnline());

        // Nút 5 & 6: Kịch bản tự động theo ERRATA §E14.1 (ScenarioRunner)
        btnRunAutoScenario = new JButton("▶ Chạy kịch bản tự động");
        styleScenarioButton(btnRunAutoScenario);
        btnRunAutoScenario.setToolTipText("Chạy chuỗi kịch bản diễn tiến tự động theo ERRATA §E14.1 (t=0 OK, +30s TCP sập, +90s HTTP 500, +150s trễ 500ms, +210s hồi phục)");
        btnRunAutoScenario.addActionListener(e -> {
            btnRunAutoScenario.setEnabled(false);
            btnStopAutoScenario.setEnabled(true);
            scenarioRunner.start();
        });

        btnStopAutoScenario = new JButton("⏹ Dừng kịch bản");
        styleScenarioButton(btnStopAutoScenario);
        btnStopAutoScenario.setEnabled(false);
        btnStopAutoScenario.setToolTipText("Dừng kịch bản tự động đang chạy");
        btnStopAutoScenario.addActionListener(e -> {
            scenarioRunner.stop();
            btnRunAutoScenario.setEnabled(true);
            btnStopAutoScenario.setEnabled(false);
            if (lblScenarioStatus != null) {
                lblScenarioStatus.setText("Trạng thái: Đã dừng kịch bản.");
            }
        });

        btnGrid.add(btnDemoOnline);
        btnGrid.add(btnDemoWarning);
        btnGrid.add(btnDemoOffline);
        btnGrid.add(btnDemoReset);
        btnGrid.add(btnRunAutoScenario);
        btnGrid.add(btnStopAutoScenario);

        hero.add(btnGrid, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        statusPanel.setOpaque(false);
        lblScenarioStatus = new JLabel("⚡ Kịch bản tự động: Sẵn sàng (scenarios/demo.json)");
        lblScenarioStatus.setFont(ThemeManager.sans(Font.ITALIC, 11));
        lblScenarioStatus.setForeground(ThemeManager.textSecondary());
        statusPanel.add(lblScenarioStatus);
        hero.add(statusPanel, BorderLayout.SOUTH);

        return hero;
    }

    private void styleScenarioButton(JButton btn) {
        btn.setFont(ThemeManager.sans(Font.BOLD, 11));
        btn.setPreferredSize(new Dimension(UIScale.scale(160), UIScale.scale(32)));
        btn.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFocusable(false);
    }

    /**
     * Kịch bản 1: Mọi thứ ổn định (Bật cả 3 server, độ trễ 0ms, HTTP 200)
     */
    public void runScenarioAllOnline() {
        tcpServer.start();
        udpServer.start();
        httpServer.start();

        tcpServer.setArtificialDelayMs(0);
        httpServer.setArtificialDelayMs(0);
        httpServer.setResponseCode(200);

        syncUiControls();
        notifyAction("Kịch bản Demo", "Toàn bộ máy chủ đang hoạt động ổn định (độ trễ phản hồi < 5ms).");
    }

    /**
     * Kịch bản 2: Giả lập nghẽn mạng (Độ trễ HTTP vọt lên 600ms)
     */
    public void runScenarioLatencyWarning() {
        httpServer.start();
        httpServer.setArtificialDelayMs(600);

        syncUiControls();
        notifyAction("Kịch bản Cảnh báo", "Đã tạo nghẽn mạng: HTTP Server trễ 600ms (vượt ngưỡng 300ms)!");
    }

    /**
     * Kịch bản 3: Sập server (Ngắt TCP Server)
     */
    public void runScenarioServerOutage() {
        tcpServer.stop();

        syncUiControls();
        notifyAction("Kịch bản Sự cố", "Máy chủ Mock TCP (127.0.0.1:9000) đã bị ngắt kết nối đột ngột!");
    }

    private void syncUiControls() {
        if (swTcp != null) swTcp.setSelected(tcpServer.isRunning());
        if (swUdp != null) swUdp.setSelected(udpServer.isRunning());
        if (swHttp != null) swHttp.setSelected(httpServer.isRunning());

        if (chipTcp != null) updateStatusChip(chipTcp, tcpServer.isRunning());
        if (chipUdp != null) updateStatusChip(chipUdp, udpServer.isRunning());
        if (chipHttp != null) updateStatusChip(chipHttp, httpServer.isRunning());

        if (delaySliderTcp != null) delaySliderTcp.setValue(tcpServer.getArtificialDelayMs());
        if (delaySliderHttp != null) delaySliderHttp.setValue(httpServer.getArtificialDelayMs());

        if (lblDelayTcp != null) lblDelayTcp.setText("Độ trễ nhân tạo: " + tcpServer.getArtificialDelayMs() + " ms");
        if (lblDelayHttp != null) lblDelayHttp.setText("Độ trễ nhân tạo: " + httpServer.getArtificialDelayMs() + " ms");

        int code = httpServer.getResponseCode();
        if (btnCode200 != null) btnCode200.setSelected(code == 200);
        if (btnCode500 != null) btnCode500.setSelected(code == 500);
        if (btnCode404 != null) btnCode404.setSelected(code == 404);
    }

    private JPanel createBlockContainer() {
        JPanel p = new JPanel(new BorderLayout(10, 8)) {
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
        p.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        return p;
    }

    private JPanel createTcpBlock() {
        JPanel block = createBlockContainer();

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        JLabel title = new JLabel("Mock TCP Server");
        title.setFont(ThemeManager.sans(Font.BOLD, 13));
        title.setForeground(ThemeManager.textPrimary());
        JLabel addr = new JLabel("127.0.0.1:9000");
        addr.setFont(ThemeManager.mono(Font.PLAIN, 12));
        addr.setForeground(ThemeManager.textSecondary());
        left.add(title);
        left.add(addr);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        chipTcp = createStatusChip(tcpServer.isRunning());
        swTcp = new ToggleSwitch(tcpServer.isRunning());
        swTcp.setOnToggle(enabled -> {
            if (enabled) {
                tcpServer.start();
                updateStatusChip(chipTcp, true);
                notifyAction("Mock TCP Server", "Đã khởi chạy cổng 9000");
            } else {
                tcpServer.stop();
                updateStatusChip(chipTcp, false);
                notifyAction("Mock TCP Server", "Đã dừng cổng 9000");
            }
        });
        right.add(chipTcp);
        right.add(swTcp);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        block.add(header, BorderLayout.NORTH);

        // Body: Điều chỉnh trễ mạng
        JPanel body = new JPanel(new BorderLayout(8, 4));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        lblDelayTcp = new JLabel("Độ trễ nhân tạo: " + tcpServer.getArtificialDelayMs() + " ms");
        lblDelayTcp.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblDelayTcp.setForeground(ThemeManager.textSecondary());

        delaySliderTcp = new JSlider(0, 1000, tcpServer.getArtificialDelayMs());
        delaySliderTcp.setMajorTickSpacing(250);
        delaySliderTcp.setPaintTicks(true);
        delaySliderTcp.addChangeListener(e -> {
            int val = delaySliderTcp.getValue();
            tcpServer.setArtificialDelayMs(val);
            lblDelayTcp.setText("Độ trễ nhân tạo: " + val + " ms");
        });

        body.add(lblDelayTcp, BorderLayout.NORTH);
        body.add(delaySliderTcp, BorderLayout.CENTER);
        block.add(body, BorderLayout.CENTER);

        return block;
    }

    private JPanel createUdpBlock() {
        JPanel block = createBlockContainer();

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        JLabel title = new JLabel("Mock UDP Echo");
        title.setFont(ThemeManager.sans(Font.BOLD, 13));
        title.setForeground(ThemeManager.textPrimary());
        JLabel addr = new JLabel("127.0.0.1:9001");
        addr.setFont(ThemeManager.mono(Font.PLAIN, 12));
        addr.setForeground(ThemeManager.textSecondary());
        left.add(title);
        left.add(addr);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        chipUdp = createStatusChip(udpServer.isRunning());
        swUdp = new ToggleSwitch(udpServer.isRunning());
        swUdp.setOnToggle(enabled -> {
            if (enabled) {
                udpServer.start();
                updateStatusChip(chipUdp, true);
                notifyAction("Mock UDP Echo", "Đã khởi chạy cổng 9001");
            } else {
                udpServer.stop();
                updateStatusChip(chipUdp, false);
                notifyAction("Mock UDP Echo", "Đã dừng cổng 9001");
            }
        });
        right.add(chipUdp);
        right.add(swUdp);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        block.add(header, BorderLayout.CENTER);
        return block;
    }

    private JPanel createHttpBlock() {
        JPanel block = createBlockContainer();

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        JLabel title = new JLabel("Mock HTTP Web");
        title.setFont(ThemeManager.sans(Font.BOLD, 13));
        title.setForeground(ThemeManager.textPrimary());
        JLabel addr = new JLabel("127.0.0.1:9002");
        addr.setFont(ThemeManager.mono(Font.PLAIN, 12));
        addr.setForeground(ThemeManager.textSecondary());
        left.add(title);
        left.add(addr);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        chipHttp = createStatusChip(httpServer.isRunning());
        swHttp = new ToggleSwitch(httpServer.isRunning());
        swHttp.setOnToggle(enabled -> {
            if (enabled) {
                httpServer.start();
                updateStatusChip(chipHttp, true);
                notifyAction("Mock HTTP Server", "Đã khởi chạy cổng 9002");
            } else {
                httpServer.stop();
                updateStatusChip(chipHttp, false);
                notifyAction("Mock HTTP Server", "Đã dừng cổng 9002");
            }
        });
        right.add(chipHttp);
        right.add(swHttp);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        block.add(header, BorderLayout.NORTH);

        // Body: Điều chỉnh độ trễ HTTP + Mã phản hồi
        JPanel body = new JPanel(new BorderLayout(8, 6));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        lblDelayHttp = new JLabel("Độ trễ nhân tạo: " + httpServer.getArtificialDelayMs() + " ms");
        lblDelayHttp.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblDelayHttp.setForeground(ThemeManager.textSecondary());

        delaySliderHttp = new JSlider(0, 1000, httpServer.getArtificialDelayMs());
        delaySliderHttp.setMajorTickSpacing(250);
        delaySliderHttp.setPaintTicks(true);
        delaySliderHttp.addChangeListener(e -> {
            int val = delaySliderHttp.getValue();
            httpServer.setArtificialDelayMs(val);
            lblDelayHttp.setText("Độ trễ nhân tạo: " + val + " ms");
        });

        JPanel delayPanel = new JPanel(new BorderLayout(0, 4));
        delayPanel.setOpaque(false);
        delayPanel.add(lblDelayHttp, BorderLayout.NORTH);
        delayPanel.add(delaySliderHttp, BorderLayout.CENTER);

        // Hàng Mã phản hồi (Segmented Buttons: 200 OK · 500 Lỗi · 404 Không tìm thấy)
        JPanel codeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        codeRow.setOpaque(false);

        JLabel lblCode = new JLabel(I18n.get("simulator.response_code") + ":");
        lblCode.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblCode.setForeground(ThemeManager.textSecondary());
        codeRow.add(lblCode);

        ButtonGroup codeGroup = new ButtonGroup();
        btnCode200 = createCodeBtn("200 OK", httpServer.getResponseCode() == 200);
        btnCode500 = createCodeBtn("500 Lỗi máy chủ", httpServer.getResponseCode() == 500);
        btnCode404 = createCodeBtn("404 Không tìm thấy", httpServer.getResponseCode() == 404);

        btnCode200.addActionListener(e -> setHttpCode(200, "200 OK"));
        btnCode500.addActionListener(e -> setHttpCode(500, "500 Lỗi máy chủ"));
        btnCode404.addActionListener(e -> setHttpCode(404, "404 Not Found"));

        codeGroup.add(btnCode200);
        codeGroup.add(btnCode500);
        codeGroup.add(btnCode404);

        codeRow.add(btnCode200);
        codeRow.add(btnCode500);
        codeRow.add(btnCode404);

        body.add(delayPanel, BorderLayout.NORTH);
        body.add(codeRow, BorderLayout.SOUTH);

        block.add(body, BorderLayout.CENTER);
        return block;
    }

    private JToggleButton createCodeBtn(String text, boolean sel) {
        JToggleButton btn = new JToggleButton(text, sel);
        btn.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btn.putClientProperty(FlatClientProperties.STYLE, "arc: 4");
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void setHttpCode(int code, String label) {
        httpServer.setResponseCode(code);
        notifyAction("Mock HTTP Server", "Đặt mã phản hồi = " + label);
    }

    private JLabel createStatusChip(boolean running) {
        JLabel lbl = new JLabel();
        lbl.setFont(ThemeManager.sans(Font.BOLD, 11));
        updateStatusChip(lbl, running);
        return lbl;
    }

    private void updateStatusChip(JLabel lbl, boolean running) {
        if (running) {
            lbl.setText("● " + I18n.get("simulator.running"));
            lbl.setForeground(ThemeManager.statusOnline());
        } else {
            lbl.setText("● " + I18n.get("simulator.stopped"));
            lbl.setForeground(ThemeManager.statusNeutral());
        }
    }

    private void notifyAction(String title, String message) {
        LoggerUtil.info(title + ": " + message);
        if (toastNotifier != null) {
            toastNotifier.accept(title, message);
        }
    }
}

package com.networkalert.ui.components;

import com.networkalert.simulator.MockHttpServer;
import com.networkalert.simulator.MockTcpServer;
import com.networkalert.simulator.MockUdpServer;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Trung tâm điều khiển bộ giả lập trạm mạng (Network Simulator Control Center)
 * Cho phép bật/tắt các dịch vụ mạng cục bộ để demo sập/chậm kết nối trực tiếp trước giảng viên.
 */
public class SimulatorPanel extends JPanel {
    private final MockTcpServer tcpServer;
    private final MockUdpServer udpServer;
    private final MockHttpServer httpServer;

    private final JLabel lblTcpStatus = new JLabel("● ĐÃ DỪNG");
    private final JButton btnToggleTcp = new JButton("Khởi động TCP (9000)");

    private final JLabel lblUdpStatus = new JLabel("● ĐÃ DỪNG");
    private final JButton btnToggleUdp = new JButton("Khởi động UDP (9001)");

    private final JLabel lblHttpStatus = new JLabel("● ĐÃ DỪNG");
    private final JButton btnToggleHttp = new JButton("Khởi động HTTP (9002)");
    private final JComboBox<String> cmbHttpCode = new JComboBox<>(new String[]{"200 OK (Bình thường)", "500 Internal Server Error", "404 Not Found"});

    public SimulatorPanel(MockTcpServer tcp, MockUdpServer udp, MockHttpServer http) {
        this.tcpServer = tcp;
        this.udpServer = udp;
        this.httpServer = http;

        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel cardsPanel = new JPanel(new GridLayout(1, 3, 10, 10));
        cardsPanel.add(createTcpCard());
        cardsPanel.add(createUdpCard());
        cardsPanel.add(createHttpCard());

        add(cardsPanel, BorderLayout.CENTER);

        // Hướng dẫn demo cho sinh viên
        JPanel hintPanel = new JPanel(new BorderLayout());
        hintPanel.setBorder(BorderFactory.createTitledBorder("💡 Hướng dẫn Kịch bản Demo"));
        JLabel hintLabel = new JLabel("<html><body style='padding: 5px; line-height: 1.4;'>" +
                "<b>Kịch bản 1 (TCP):</b> Bật TCP 9000 -> Server Online -> Bấm Dừng -> Hệ thống phát hiện OFFLINE, kêu Beep & bật Toast Popup.<br>" +
                "<b>Kịch bản 2 (HTTP):</b> Đang Online mã 200 -> Chuyển sang mã 500 -> Hệ thống phát hiện lỗi Web Server ngay lập tức.<br>" +
                "<b>Kịch bản 3 (Độ trễ):</b> Kéo thanh trượt độ trễ TCP lên 500ms -> Hệ thống chuyển sang màu vàng WARNING độ trễ cao." +
                "</body></html>");
        hintPanel.add(hintLabel, BorderLayout.CENTER);
        add(hintPanel, BorderLayout.SOUTH);

        updateUiState();
    }

    private JPanel createTcpCard() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "1. Mock TCP Server (Cổng 9000)", TitledBorder.LEFT, TitledBorder.TOP, new Font("Segoe UI", Font.BOLD, 12)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0;

        lblTcpStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        p.add(lblTcpStatus, gbc);

        gbc.gridy = 1;
        btnToggleTcp.addActionListener(e -> {
            if (tcpServer.isRunning()) {
                tcpServer.stop();
                LoggerUtil.info("Đã dừng Mock TCP Server cổng " + tcpServer.getPort());
            } else {
                tcpServer.start();
                LoggerUtil.info("Đã khởi chạy Mock TCP Server cổng " + tcpServer.getPort());
            }
            updateUiState();
        });
        p.add(btnToggleTcp, gbc);

        gbc.gridy = 2;
        p.add(new JLabel("Mô phỏng trễ mạng (ms):"), gbc);

        gbc.gridy = 3;
        JSlider delaySlider = new JSlider(0, 1000, 0);
        delaySlider.setMajorTickSpacing(250);
        delaySlider.setPaintTicks(true);
        delaySlider.setPaintLabels(true);
        delaySlider.addChangeListener(e -> tcpServer.setArtificialDelayMs(delaySlider.getValue()));
        p.add(delaySlider, gbc);

        return p;
    }

    private JPanel createUdpCard() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "2. Mock UDP Echo (Cổng 9001)", TitledBorder.LEFT, TitledBorder.TOP, new Font("Segoe UI", Font.BOLD, 12)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0;

        lblUdpStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        p.add(lblUdpStatus, gbc);

        gbc.gridy = 1;
        btnToggleUdp.addActionListener(e -> {
            if (udpServer.isRunning()) {
                udpServer.stop();
                LoggerUtil.info("Đã dừng Mock UDP Server cổng " + udpServer.getPort());
            } else {
                udpServer.start();
                LoggerUtil.info("Đã khởi chạy Mock UDP Server cổng " + udpServer.getPort());
            }
            updateUiState();
        });
        p.add(btnToggleUdp, gbc);

        gbc.gridy = 2;
        JLabel desc = new JLabel("<html><body style='width: 140px; font-size: 11px; color: gray;'>Mô phỏng trao đổi gói tin tầng Vận chuyển UDP Echo không kết nối.</body></html>");
        p.add(desc, gbc);

        return p;
    }

    private JPanel createHttpCard() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "3. Mock HTTP Web (Cổng 9002)", TitledBorder.LEFT, TitledBorder.TOP, new Font("Segoe UI", Font.BOLD, 12)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0;

        lblHttpStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        p.add(lblHttpStatus, gbc);

        gbc.gridy = 1;
        btnToggleHttp.addActionListener(e -> {
            if (httpServer.isRunning()) {
                httpServer.stop();
                LoggerUtil.info("Đã dừng Mock HTTP Server cổng " + httpServer.getPort());
            } else {
                httpServer.start();
                LoggerUtil.info("Đã khởi chạy Mock HTTP Server cổng " + httpServer.getPort());
            }
            updateUiState();
        });
        p.add(btnToggleHttp, gbc);

        gbc.gridy = 2;
        p.add(new JLabel("Mã phản hồi HTTP:"), gbc);

        gbc.gridy = 3;
        cmbHttpCode.addActionListener(e -> {
            int idx = cmbHttpCode.getSelectedIndex();
            int code = (idx == 0) ? 200 : (idx == 1 ? 500 : 404);
            httpServer.setResponseCode(code);
            LoggerUtil.info("Đặt mã phản hồi Mock HTTP Server = " + code);
        });
        p.add(cmbHttpCode, gbc);

        return p;
    }

    public void updateUiState() {
        // TCP state
        if (tcpServer.isRunning()) {
            lblTcpStatus.setText("● ĐANG CHẠY (ONLINE)");
            lblTcpStatus.setForeground(new Color(46, 204, 113));
            btnToggleTcp.setText("Dừng TCP Server");
        } else {
            lblTcpStatus.setText("● ĐÃ DỪNG (OFFLINE)");
            lblTcpStatus.setForeground(new Color(231, 76, 60));
            btnToggleTcp.setText("Khởi động TCP (9000)");
        }

        // UDP state
        if (udpServer.isRunning()) {
            lblUdpStatus.setText("● ĐANG CHẠY (ONLINE)");
            lblUdpStatus.setForeground(new Color(46, 204, 113));
            btnToggleUdp.setText("Dừng UDP Server");
        } else {
            lblUdpStatus.setText("● ĐÃ DỪNG (OFFLINE)");
            lblUdpStatus.setForeground(new Color(231, 76, 60));
            btnToggleUdp.setText("Khởi động UDP (9001)");
        }

        // HTTP state
        if (httpServer.isRunning()) {
            lblHttpStatus.setText("● ĐANG CHẠY (ONLINE)");
            lblHttpStatus.setForeground(new Color(46, 204, 113));
            btnToggleHttp.setText("Dừng HTTP Server");
        } else {
            lblHttpStatus.setText("● ĐÃ DỪNG (OFFLINE)");
            lblHttpStatus.setForeground(new Color(231, 76, 60));
            btnToggleHttp.setText("Khởi động HTTP (9002)");
        }
    }
}

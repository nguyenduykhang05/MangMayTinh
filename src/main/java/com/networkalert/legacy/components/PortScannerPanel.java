package com.networkalert.legacy.components;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.TargetRepository;
import com.networkalert.scanner.PortScanResult;
import com.networkalert.scanner.PortScannerService;
import com.networkalert.service.MonitorService;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Giao diện công cụ Quét Cổng Mạng & Tự động Nhận diện Dịch vụ (Port Scanner Panel)
 */
public class PortScannerPanel extends JPanel {
    private final PortScannerService scannerService = new PortScannerService();
    private final MonitorService monitorService;
    private final TargetRepository targetRepository;
    private final Consumer<ServerTarget> onTargetAddedCallback;

    private final JTextField txtHost = new JTextField("127.0.0.1", 12);
    private final JSpinner spinStartPort = new JSpinner(new SpinnerNumberModel(1, 1, 65535, 1));
    private final JSpinner spinEndPort = new JSpinner(new SpinnerNumberModel(1024, 1, 65535, 1));
    private final JSpinner spinTimeout = new JSpinner(new SpinnerNumberModel(600, 100, 5000, 50));
    private final JComboBox<String> cmbPresets = new JComboBox<>(new String[]{
            "Cổng Hệ Thống Cơ Bản (1 - 1024)",
            "Cổng Web & Mail Phổ Biến (20 - 465)",
            "Cổng Ứng Dụng & DB (1024 - 9500)",
            "Toàn Bộ Cổng Mock NCAS (9000 - 9005)",
            "Dải Tùy Biến"
    });

    private final JButton btnStartScan = new JButton("▶ Bắt Đầu Quét");
    private final JButton btnStopScan = new JButton("⏹ Dừng");
    private final JCheckBox chkOnlyOpen = new JCheckBox("Chỉ hiển thị các cổng đang MỞ (Open Ports)", true);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel lblStatus = new JLabel("Sẵn sàng quét.");

    private DefaultTableModel tableModel;
    private JTable resultTable;
    private final List<PortScanResult> allResults = new ArrayList<>();

    public PortScannerPanel(MonitorService monitorService, TargetRepository targetRepository,
                            Consumer<ServerTarget> onTargetAddedCallback) {
        this.monitorService = monitorService;
        this.targetRepository = targetRepository;
        this.onTargetAddedCallback = onTargetAddedCallback;

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(createControlBar(), BorderLayout.NORTH);
        add(createTablePanel(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);

        btnStopScan.setEnabled(false);
    }

    private JPanel createControlBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(BorderFactory.createTitledBorder("Cấu Hình Quét Cổng Mạng (TCP Port Discovery)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row;
        bar.add(new JLabel("Mục tiêu (Host/IP):"), gbc);
        gbc.gridx = 1;
        bar.add(txtHost, gbc);

        gbc.gridx = 2;
        bar.add(new JLabel("Cấu hình sẵn:"), gbc);
        gbc.gridx = 3;
        bar.add(cmbPresets, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        bar.add(new JLabel("Từ cổng:"), gbc);
        gbc.gridx = 1;
        bar.add(spinStartPort, gbc);

        gbc.gridx = 2;
        bar.add(new JLabel("Đến cổng:"), gbc);
        gbc.gridx = 3;
        bar.add(spinEndPort, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        bar.add(new JLabel("Timeout (ms):"), gbc);
        gbc.gridx = 1;
        bar.add(spinTimeout, gbc);

        gbc.gridx = 2; gbc.gridwidth = 2;
        JPanel btnBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        btnStartScan.setFont(btnStartScan.getFont().deriveFont(Font.BOLD));
        btnStartScan.addActionListener(e -> startScan());
        btnStopScan.addActionListener(e -> stopScan());
        btnBox.add(btnStartScan);
        btnBox.add(btnStopScan);
        bar.add(btnBox, gbc);

        cmbPresets.addActionListener(e -> {
            int idx = cmbPresets.getSelectedIndex();
            if (idx == 0) {
                spinStartPort.setValue(1);
                spinEndPort.setValue(1024);
            } else if (idx == 1) {
                spinStartPort.setValue(20);
                spinEndPort.setValue(465);
            } else if (idx == 2) {
                spinStartPort.setValue(1024);
                spinEndPort.setValue(9500);
            } else if (idx == 3) {
                spinStartPort.setValue(9000);
                spinEndPort.setValue(9005);
            }
        });

        return bar;
    }

    private JPanel createTablePanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));

        String[] cols = {"Cổng (Port)", "Trạng Thái", "Độ Trễ", "Dịch Vụ Nhận Diện"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
            @Override
            public Class<?> getColumnClass(int col) {
                if (col == 0) return Integer.class;
                return String.class;
            }
        };

        resultTable = new JTable(tableModel);
        resultTable.setRowHeight(26);
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Renderer trang trí cho cột Trạng Thái
        resultTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object val, boolean isSel, boolean hasFocus, int r, int c) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, val, isSel, hasFocus, r, c);
                lbl.setHorizontalAlignment(SwingConstants.CENTER);
                lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
                if ("MỞ (OPEN)".equals(val)) {
                    lbl.setForeground(new Color(46, 204, 113));
                } else {
                    lbl.setForeground(new Color(180, 180, 180));
                }
                return lbl;
            }
        });

        p.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        return p;
    }

    private JPanel createBottomPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JPanel progBox = new JPanel(new BorderLayout(6, 6));
        progressBar.setStringPainted(true);
        progBox.add(lblStatus, BorderLayout.NORTH);
        progBox.add(progressBar, BorderLayout.CENTER);

        JPanel actionBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        chkOnlyOpen.addActionListener(e -> refreshTableDisplay());
        actionBox.add(chkOnlyOpen);

        JButton btnAddTarget = new JButton("➕ Thêm Cổng Đang Chọn Vào Giám Sát");
        btnAddTarget.setFont(btnAddTarget.getFont().deriveFont(Font.BOLD));
        btnAddTarget.addActionListener(e -> onAddSelectedPortToMonitoring());
        actionBox.add(btnAddTarget);

        p.add(progBox, BorderLayout.CENTER);
        p.add(actionBox, BorderLayout.EAST);
        return p;
    }

    private void startScan() {
        String host = txtHost.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập địa chỉ Host hoặc IP cần quét!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int startPort = (Integer) spinStartPort.getValue();
        int endPort = (Integer) spinEndPort.getValue();
        int timeout = (Integer) spinTimeout.getValue();

        if (startPort > endPort) {
            JOptionPane.showMessageDialog(this, "Cổng bắt đầu không được lớn hơn cổng kết thúc!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        allResults.clear();
        tableModel.setRowCount(0);
        btnStartScan.setEnabled(false);
        btnStopScan.setEnabled(true);
        progressBar.setValue(0);
        lblStatus.setText("Đang quét dải cổng " + startPort + " - " + endPort + " trên " + host + "...");

        scannerService.scanRangeAsync(host, startPort, endPort, timeout, (res, progress) -> {
            SwingUtilities.invokeLater(() -> {
                allResults.add(res);
                progressBar.setValue(progress);
                lblStatus.setText(String.format("Đang quét: %d%% (%d kết quả)", progress, allResults.size()));

                if (res.isOpen() || !chkOnlyOpen.isSelected()) {
                    addResultToTable(res);
                }
            });
        }, () -> {
            SwingUtilities.invokeLater(() -> {
                btnStartScan.setEnabled(true);
                btnStopScan.setEnabled(false);
                long openCount = allResults.stream().filter(PortScanResult::isOpen).count();
                lblStatus.setText(String.format("✔ Quét hoàn tất! Tìm thấy %d cổng MỞ trên %s.", openCount, host));
                progressBar.setValue(100);
            });
        });
    }

    private void stopScan() {
        scannerService.cancel();
        btnStartScan.setEnabled(true);
        btnStopScan.setEnabled(false);
        lblStatus.setText("Đã dừng quét.");
    }

    private void addResultToTable(PortScanResult res) {
        tableModel.addRow(new Object[]{
                res.getPort(),
                res.isOpen() ? "MỞ (OPEN)" : "ĐÓNG",
                res.isOpen() ? res.getLatencyMs() + " ms" : "-",
                res.getServiceName()
        });
    }

    private void refreshTableDisplay() {
        tableModel.setRowCount(0);
        boolean onlyOpen = chkOnlyOpen.isSelected();
        for (PortScanResult res : allResults) {
            if (!onlyOpen || res.isOpen()) {
                addResultToTable(res);
            }
        }
    }

    private void onAddSelectedPortToMonitoring() {
        int row = resultTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một dòng cổng MỞ trên bảng!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int port = (Integer) tableModel.getValueAt(row, 0);
        String service = (String) tableModel.getValueAt(row, 3);
        String host = txtHost.getText().trim();

        ProtocolType protocol = ProtocolType.TCP;
        if (port == 80) protocol = ProtocolType.HTTP;
        else if (port == 443) protocol = ProtocolType.HTTPS;
        else if (port == 53) protocol = ProtocolType.DNS;

        String name = service + " (" + host + ":" + port + ")";
        ServerTarget newTarget = new ServerTarget(0, name, host, port, protocol, 5, 1500, true);

        targetRepository.addTarget(newTarget);
        monitorService.addTarget(newTarget);

        if (onTargetAddedCallback != null) {
            onTargetAddedCallback.accept(newTarget);
        }

        JOptionPane.showMessageDialog(this,
                "Đã thêm thành công mục tiêu:\n'" + name + "' vào danh sách giám sát chính!",
                "Thành công", JOptionPane.INFORMATION_MESSAGE);
    }
}

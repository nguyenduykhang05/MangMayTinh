package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.LanDevice;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.RealNetworkInfo;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.TargetRepository;
import com.networkalert.scanner.PortScanResult;
import com.networkalert.scanner.PortScannerService;
import com.networkalert.service.MonitorService;
import com.networkalert.service.RealNetworkService;
import com.networkalert.ui.NotificationToast;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Giao diện Khám phá Mạng LAN Thật (ARP/ICMP /24) & Quét Cổng Dịch Vụ TCP (Port Scanner Panel).
 * Cho phép quét toàn bộ thiết bị máy thật đang kết nối chung Wi-Fi/LAN,
 * thêm máy thật vào danh sách giám sát chỉ với 1-Click, hoặc quét cổng chuyên sâu trên từng máy.
 */
public class PortScannerPanel extends JPanel {
    private static final String CARD_PORTS = "PORTS";
    private static final String CARD_LAN = "LAN";

    private final PortScannerService scannerService = new PortScannerService();
    private final MonitorService monitorService;
    private final TargetRepository targetRepository;
    private final Consumer<ServerTarget> onTargetAddedCallback;

    // Thông tin hạ tầng mạng thật
    private final JLabel lblNicInfo = new JLabel("Đang đọc thông số Card mạng vật lý...");
    private final JLabel lblGatewayInfo = new JLabel("Gateway: —");
    private final JButton btnScanLan = new JButton("📡 Quét Máy Thật LAN (/24 + ARP)");
    private final JButton btnUseMyIp = new JButton("💻 IP Máy Này");
    private final JButton btnUseGatewayIp = new JButton("🏠 IP Router");

    // Điều khiển quét cổng TCP
    private final JTextField txtHost = new JTextField("127.0.0.1", 11);
    private final JSpinner spinStartPort = new JSpinner(new SpinnerNumberModel(1, 1, 65535, 1));
    private final JSpinner spinEndPort = new JSpinner(new SpinnerNumberModel(1024, 1, 65535, 1));
    private final JSpinner spinTimeout = new JSpinner(new SpinnerNumberModel(600, 100, 5000, 50));
    private final JComboBox<String> cmbPresets = new JComboBox<>(new String[]{
            "Cổng Hệ Thống (1 - 1024)",
            "Cổng Web & Mail (20 - 465)",
            "Cổng App & DB (1024 - 9500)",
            "Cổng Mock NCAS (9000 - 9005)",
            "Dải Tùy Biến"
    });

    private final JButton btnStartScan = new JButton("▶ Quét Cổng");
    private final JButton btnStopScan = new JButton("⏹ Dừng");
    private final JCheckBox chkOnlyOpen = new JCheckBox("Chỉ hiện cổng MỞ", true);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel lblStatus = new JLabel("Sẵn sàng khám phá mạng LAN thật hoặc quét cổng TCP.");

    // Chuyển đổi bảng hiển thị
    private final CardLayout tableCardLayout = new CardLayout();
    private final JPanel tableCardContainer = new JPanel(tableCardLayout);
    private final JToggleButton tabBtnPorts = new JToggleButton("🔍 Kết Quả Quét Cổng TCP (0)", true);
    private final JToggleButton tabBtnLan = new JToggleButton("🖧 Máy Thật Trong Mạng LAN (0)", false);

    private DefaultTableModel portTableModel;
    private JTable resultTable;
    private final List<PortScanResult> allResults = new ArrayList<>();

    private DefaultTableModel lanTableModel;
    private JTable lanTable;
    private final List<LanDevice> discoveredLanDevices = new ArrayList<>();

    private JButton btnAddTarget;
    private JButton btnScanSelectedLanPorts;
    private boolean showingLanView = false;

    public PortScannerPanel(MonitorService monitorService, TargetRepository targetRepository,
                            Consumer<ServerTarget> onTargetAddedCallback) {
        this.monitorService = monitorService;
        this.targetRepository = targetRepository;
        this.onTargetAddedCallback = onTargetAddedCallback;

        setLayout(new BorderLayout(0, UIScale.scale(6)));
        setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(8), UIScale.scale(8), UIScale.scale(8)));

        JPanel topStack = new JPanel();
        topStack.setLayout(new BoxLayout(topStack, BoxLayout.Y_AXIS));
        topStack.setOpaque(false);
        topStack.add(createRealNetworkCard());
        topStack.add(Box.createVerticalStrut(UIScale.scale(6)));
        topStack.add(createControlBar());

        add(topStack, BorderLayout.NORTH);
        add(createCenterPanel(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);

        btnStopScan.setEnabled(false);
        refreshRealNetworkInfo();
    }

    /**
     * Khung hiển thị thông số Card mạng vật lý (NIC) & Quét các máy thật trong mạng LAN /24
     */
    private JPanel createRealNetworkCard() {
        JPanel card = new JPanel(new BorderLayout(UIScale.scale(6), UIScale.scale(4)));
        card.setOpaque(true);
        card.setBackground(ThemeManager.bgSurface());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border(), 1),
                BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10))
        ));

        JPanel infoBox = new JPanel();
        infoBox.setLayout(new BoxLayout(infoBox, BoxLayout.Y_AXIS));
        infoBox.setOpaque(false);

        lblNicInfo.setFont(ThemeManager.sans(Font.BOLD, 12));
        lblNicInfo.setForeground(ThemeManager.textPrimary());
        lblGatewayInfo.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblGatewayInfo.setForeground(ThemeManager.textSecondary());

        infoBox.add(lblNicInfo);
        infoBox.add(Box.createVerticalStrut(UIScale.scale(2)));
        infoBox.add(lblGatewayInfo);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), UIScale.scale(2)));
        actionRow.setOpaque(false);

        btnScanLan.setFont(ThemeManager.sans(Font.BOLD, 11));
        btnScanLan.putClientProperty("FlatLaf.style", "background: #059669; foreground: #FFFFFF; arc: 6; focusWidth: 0");
        btnScanLan.setMargin(new Insets(4, 10, 4, 10));
        btnScanLan.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnScanLan.setToolTipText("Phát sóng ICMP & đọc bảng ARP toàn mạng LAN /24 để tìm tất cả máy thật đang kết nối chung mạng");
        btnScanLan.addActionListener(e -> startLanSubnetDiscovery());

        btnUseMyIp.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btnUseMyIp.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btnUseMyIp.setMargin(new Insets(4, 8, 4, 8));
        btnUseMyIp.setToolTipText("Điền địa chỉ IP LAN thật của máy tính này vào ô Mục tiêu");
        btnUseMyIp.addActionListener(e -> {
            RealNetworkInfo info = RealNetworkService.getInstance().getNetworkInfo();
            if (info != null && info.getLocalIp() != null) {
                txtHost.setText(info.getLocalIp());
                switchTableMode(false);
            }
        });

        btnUseGatewayIp.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btnUseGatewayIp.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btnUseGatewayIp.setMargin(new Insets(4, 8, 4, 8));
        btnUseGatewayIp.setToolTipText("Điền địa chỉ IP của Router / Default Gateway vào ô Mục tiêu");
        btnUseGatewayIp.addActionListener(e -> {
            RealNetworkInfo info = RealNetworkService.getInstance().getNetworkInfo();
            if (info != null && info.getGatewayIp() != null
                    && !info.getGatewayIp().startsWith("Không")
                    && !info.getGatewayIp().startsWith("Đang")) {
                txtHost.setText(info.getGatewayIp());
                switchTableMode(false);
            }
        });

        actionRow.add(btnScanLan);
        actionRow.add(btnUseMyIp);
        actionRow.add(btnUseGatewayIp);

        card.add(infoBox, BorderLayout.CENTER);
        card.add(actionRow, BorderLayout.SOUTH);
        return card;
    }

    public void refreshRealNetworkInfo() {
        RealNetworkInfo info = RealNetworkService.getInstance().getNetworkInfo();
        if (info == null) return;

        String nicType = info.getInterfaceType() != null ? info.getInterfaceType() : "LAN";
        String localIp = info.getLocalIp() != null ? info.getLocalIp() : "127.0.0.1";
        String mac = info.getMacAddress() != null ? info.getMacAddress() : "N/A";
        String gw = info.getGatewayIp() != null ? info.getGatewayIp() : "N/A";
        long gwPing = info.getGatewayLatencyMs();
        String pubIp = info.getPublicIp() != null ? info.getPublicIp() : "N/A";

        lblNicInfo.setText(String.format("🖧 Card mạng thật: %s  |  IP LAN: %s  |  MAC: %s", nicType, localIp, mac));
        String gwPingStr = gwPing >= 0 ? (gwPing + " ms") : "N/A";
        lblGatewayInfo.setText(String.format("🏠 Router Gateway: %s (%s)  |  Public IP: %s  |  Dải mạng: %s",
                gw, gwPingStr, pubIp, info.getSubnetMask()));

        if ("127.0.0.1".equals(txtHost.getText().trim()) && !"127.0.0.1".equals(localIp)) {
            txtHost.setText(localIp);
        }
    }

    private JPanel createControlBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(BorderFactory.createTitledBorder("Cấu Hình Quét Cổng Mạng (TCP Port Discovery)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        bar.add(new JLabel("Host / IP:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.4;
        bar.add(txtHost, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        bar.add(new JLabel("Mẫu dải:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.6;
        bar.add(cmbPresets, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        bar.add(new JLabel("Từ cổng:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.4;
        bar.add(spinStartPort, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        bar.add(new JLabel("Đến cổng:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.6;
        bar.add(spinEndPort, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        bar.add(new JLabel("Timeout (ms):"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.4;
        bar.add(spinTimeout, gbc);

        gbc.gridx = 2; gbc.gridwidth = 2; gbc.weightx = 0.6;
        JPanel btnBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnBox.setOpaque(false);
        btnStartScan.setFont(ThemeManager.sans(Font.BOLD, 12));
        btnStartScan.putClientProperty("FlatLaf.style", "background: #2563EB; foreground: #FFFFFF; arc: 6; focusWidth: 0");
        btnStartScan.setMargin(new Insets(4, 10, 4, 10));
        btnStartScan.addActionListener(e -> startScan());

        btnStopScan.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnStopScan.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btnStopScan.setMargin(new Insets(4, 10, 4, 10));
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

    private JPanel createCenterPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        wrapper.setOpaque(false);

        // Thanh chuyển chế độ xem kết quả: Quét Cổng TCP <-> Máy Thật LAN
        JPanel modeSwitchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        modeSwitchBar.setOpaque(false);

        ButtonGroup grp = new ButtonGroup();
        grp.add(tabBtnPorts);
        grp.add(tabBtnLan);

        tabBtnPorts.setFont(ThemeManager.sans(Font.BOLD, 11));
        tabBtnPorts.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        tabBtnPorts.addActionListener(e -> switchTableMode(false));

        tabBtnLan.setFont(ThemeManager.sans(Font.BOLD, 11));
        tabBtnLan.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        tabBtnLan.addActionListener(e -> switchTableMode(true));

        modeSwitchBar.add(tabBtnPorts);
        modeSwitchBar.add(tabBtnLan);
        wrapper.add(modeSwitchBar, BorderLayout.NORTH);

        // Bảng 1: Kết quả quét cổng TCP
        String[] portCols = {"Cổng (Port)", "Trạng Thái", "Độ Trễ", "Dịch Vụ Nhận Diện"};
        portTableModel = new DefaultTableModel(portCols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
            @Override
            public Class<?> getColumnClass(int col) {
                if (col == 0) return Integer.class;
                return String.class;
            }
        };

        resultTable = new JTable(portTableModel);
        resultTable.setRowHeight(UIScale.scale(30));
        resultTable.setShowHorizontalLines(true);
        resultTable.setShowVerticalLines(false);
        resultTable.setGridColor(ThemeManager.border());
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

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

        // Bảng 2: Danh sách Máy thật phát hiện trong mạng LAN (/24 + ARP)
        String[] lanCols = {"IP Máy Thật", "Địa chỉ MAC (ARP)", "Tên Thiết Bị", "Phân Loại Thiết Bị", "Độ Trễ"};
        lanTableModel = new DefaultTableModel(lanCols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };

        lanTable = new JTable(lanTableModel);
        lanTable.setRowHeight(UIScale.scale(30));
        lanTable.setShowHorizontalLines(true);
        lanTable.setShowVerticalLines(false);
        lanTable.setGridColor(ThemeManager.border());
        lanTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lanTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = lanTable.getSelectedRow();
                if (row >= 0) {
                    int modelRow = lanTable.convertRowIndexToModel(row);
                    String ip = String.valueOf(lanTableModel.getValueAt(modelRow, 0));
                    txtHost.setText(ip);
                }
            }
        });

        tableCardContainer.add(new JScrollPane(resultTable), CARD_PORTS);
        tableCardContainer.add(new JScrollPane(lanTable), CARD_LAN);
        wrapper.add(tableCardContainer, BorderLayout.CENTER);

        return wrapper;
    }

    private JPanel createBottomPanel() {
        JPanel p = new JPanel(new BorderLayout(UIScale.scale(6), UIScale.scale(4)));

        JPanel progBox = new JPanel(new BorderLayout(UIScale.scale(4), UIScale.scale(2)));
        progressBar.setStringPainted(true);
        lblStatus.setFont(ThemeManager.sans(Font.PLAIN, 11));
        progBox.add(lblStatus, BorderLayout.NORTH);
        progBox.add(progressBar, BorderLayout.CENTER);

        JPanel actionBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), UIScale.scale(2)));
        chkOnlyOpen.setFont(ThemeManager.sans(Font.PLAIN, 11));
        chkOnlyOpen.addActionListener(e -> refreshTableDisplay());
        actionBox.add(chkOnlyOpen);

        btnScanSelectedLanPorts = new JButton("🔍 Quét Cổng Máy Này");
        btnScanSelectedLanPorts.setFont(ThemeManager.sans(Font.BOLD, 11));
        btnScanSelectedLanPorts.putClientProperty("FlatLaf.style", "background: #2563EB; foreground: #FFFFFF; arc: 6; focusWidth: 0");
        btnScanSelectedLanPorts.setMargin(new Insets(4, 10, 4, 10));
        btnScanSelectedLanPorts.setVisible(false);
        btnScanSelectedLanPorts.addActionListener(e -> onScanPortsOfSelectedLanDevice());
        actionBox.add(btnScanSelectedLanPorts);

        btnAddTarget = new JButton("➕ Giám Sát Mục Tiêu Đang Chọn");
        btnAddTarget.setFont(ThemeManager.sans(Font.BOLD, 11));
        btnAddTarget.putClientProperty("FlatLaf.style", "background: #10B981; foreground: #FFFFFF; arc: 6; focusWidth: 0");
        btnAddTarget.setMargin(new Insets(4, 10, 4, 10));
        btnAddTarget.addActionListener(e -> {
            if (showingLanView) {
                onAddSelectedLanDeviceToMonitoring();
            } else {
                onAddSelectedPortToMonitoring();
            }
        });
        actionBox.add(btnAddTarget);

        p.add(progBox, BorderLayout.NORTH);
        p.add(actionBox, BorderLayout.SOUTH);
        return p;
    }

    private void switchTableMode(boolean lanMode) {
        this.showingLanView = lanMode;
        tabBtnLan.setSelected(lanMode);
        tabBtnPorts.setSelected(!lanMode);
        tableCardLayout.show(tableCardContainer, lanMode ? CARD_LAN : CARD_PORTS);
        chkOnlyOpen.setVisible(!lanMode);
        btnScanSelectedLanPorts.setVisible(lanMode);
        if (lanMode) {
            btnAddTarget.setText("➕ Giám Sát Máy Thật Đang Chọn (ICMP)");
        } else {
            btnAddTarget.setText("➕ Giám Sát Cổng Đang Chọn");
        }
    }

    /**
     * Quét toàn bộ máy thật đang hoạt động trong mạng LAN (/24 + bảng ARP hệ điều hành)
     */
    private void startLanSubnetDiscovery() {
        refreshRealNetworkInfo();
        switchTableMode(true);
        btnScanLan.setEnabled(false);
        lanTableModel.setRowCount(0);
        discoveredLanDevices.clear();
        progressBar.setIndeterminate(true);
        lblStatus.setText("Đang phát sóng ICMP & đọc bảng ARP toàn mạng LAN /24 để tìm máy thật...");

        SwingWorker<List<LanDevice>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<LanDevice> doInBackground() {
                return RealNetworkService.getInstance().scanLanSubnet(500);
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                btnScanLan.setEnabled(true);
                try {
                    List<LanDevice> devices = get();
                    if (devices != null) {
                        discoveredLanDevices.addAll(devices);
                        for (LanDevice d : devices) {
                            lanTableModel.addRow(new Object[]{
                                    d.getIp(),
                                    d.getMac(),
                                    d.getHostname(),
                                    d.getDeviceType(),
                                    d.getLatencyMs() + " ms"
                            });
                        }
                    }
                    tabBtnLan.setText("🖧 Máy Thật Trong Mạng LAN (" + discoveredLanDevices.size() + ")");
                    lblStatus.setText(String.format("✔ Hoàn tất quét LAN! Phát hiện %d thiết bị máy thật đang hoạt động.",
                            discoveredLanDevices.size()));
                    NotificationToast.show("Quét mạng LAN hoàn tất",
                            "Tìm thấy " + discoveredLanDevices.size() + " thiết bị thật trong mạng nội bộ!", false);
                } catch (Exception ex) {
                    lblStatus.setText("Lỗi quét mạng LAN: " + ex.getMessage());
                }
            }
        };
        worker.execute();
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

        switchTableMode(false);
        allResults.clear();
        portTableModel.setRowCount(0);
        btnStartScan.setEnabled(false);
        btnStopScan.setEnabled(true);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        lblStatus.setText("Đang quét dải cổng " + startPort + " - " + endPort + " trên " + host + "...");

        scannerService.scanRangeAsync(host, startPort, endPort, timeout, (res, progress) -> {
            SwingUtilities.invokeLater(() -> {
                allResults.add(res);
                progressBar.setValue(progress);
                long openSoFar = allResults.stream().filter(PortScanResult::isOpen).count();
                tabBtnPorts.setText("🔍 Kết Quả Quét Cổng TCP (" + openSoFar + ")");
                lblStatus.setText(String.format("Đang quét %s: %d%% (%d cổng mở)", host, progress, openSoFar));

                if (res.isOpen() || !chkOnlyOpen.isSelected()) {
                    addResultToTable(res);
                }
            });
        }, () -> {
            SwingUtilities.invokeLater(() -> {
                btnStartScan.setEnabled(true);
                btnStopScan.setEnabled(false);
                long openCount = allResults.stream().filter(PortScanResult::isOpen).count();
                tabBtnPorts.setText("🔍 Kết Quả Quét Cổng TCP (" + openCount + ")");
                lblStatus.setText(String.format("✔ Quét hoàn tất! Tìm thấy %d cổng MỞ trên %s.", openCount, host));
                progressBar.setValue(100);
            });
        });
    }

    private void stopScan() {
        scannerService.cancel();
        btnStartScan.setEnabled(true);
        btnStopScan.setEnabled(false);
        lblStatus.setText("Đã dừng quét cổng.");
    }

    private void addResultToTable(PortScanResult res) {
        portTableModel.addRow(new Object[]{
                res.getPort(),
                res.isOpen() ? "MỞ (OPEN)" : "ĐÓNG",
                res.isOpen() ? res.getLatencyMs() + " ms" : "-",
                res.getServiceName()
        });
    }

    private void refreshTableDisplay() {
        portTableModel.setRowCount(0);
        boolean onlyOpen = chkOnlyOpen.isSelected();
        for (PortScanResult res : allResults) {
            if (!onlyOpen || res.isOpen()) {
                addResultToTable(res);
            }
        }
    }

    private void onScanPortsOfSelectedLanDevice() {
        int row = lanTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một thiết bị máy thật trên bảng LAN!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = lanTable.convertRowIndexToModel(row);
        String ip = String.valueOf(lanTableModel.getValueAt(modelRow, 0));
        txtHost.setText(ip);
        startScan();
    }

    private void onAddSelectedLanDeviceToMonitoring() {
        int row = lanTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một thiết bị máy thật trên bảng LAN để đưa vào giám sát!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = lanTable.convertRowIndexToModel(row);
        String ip = String.valueOf(lanTableModel.getValueAt(modelRow, 0));
        String hostname = String.valueOf(lanTableModel.getValueAt(modelRow, 2));
        String devType = String.valueOf(lanTableModel.getValueAt(modelRow, 3));

        String displayTitle = (hostname != null && !hostname.isEmpty() && !hostname.equals(ip))
                ? hostname : devType;
        String name = displayTitle + " [" + ip + "]";

        ServerTarget newTarget = new ServerTarget(0, name, ip, 80, ProtocolType.ICMP, 5, 1500, true);
        if (targetRepository != null) {
            targetRepository.addTarget(newTarget);
        }
        if (monitorService != null) {
            monitorService.addTarget(newTarget);
        }
        if (onTargetAddedCallback != null) {
            onTargetAddedCallback.accept(newTarget);
        }

        NotificationToast.show("Đã thêm máy thật vào giám sát",
                "Đang giám sát thời gian thực thiết bị '" + name + "' qua ICMP!", false);
    }

    private void onAddSelectedPortToMonitoring() {
        int row = resultTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một dòng cổng MỞ trên bảng!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int modelRow = resultTable.convertRowIndexToModel(row);
        int port = (Integer) portTableModel.getValueAt(modelRow, 0);
        String service = (String) portTableModel.getValueAt(modelRow, 3);
        String host = txtHost.getText().trim();

        ProtocolType protocol = ProtocolType.TCP;
        if (port == 80) protocol = ProtocolType.HTTP;
        else if (port == 443) protocol = ProtocolType.HTTPS;
        else if (port == 53) protocol = ProtocolType.DNS;

        String name = service + " (" + host + ":" + port + ")";
        ServerTarget newTarget = new ServerTarget(0, name, host, port, protocol, 5, 1500, true);

        if (targetRepository != null) {
            targetRepository.addTarget(newTarget);
        }
        if (monitorService != null) {
            monitorService.addTarget(newTarget);
        }

        if (onTargetAddedCallback != null) {
            onTargetAddedCallback.accept(newTarget);
        }

        JOptionPane.showMessageDialog(this,
                "Đã thêm thành công mục tiêu:\n'" + name + "' vào danh sách giám sát chính!",
                "Thành công", JOptionPane.INFORMATION_MESSAGE);
    }
}

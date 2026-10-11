package com.networkalert.legacy;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.networkalert.model.CheckResult;
import com.networkalert.model.IncidentLog;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.service.AlertService;
import com.networkalert.service.MonitorService;
import com.networkalert.simulator.MockHttpServer;
import com.networkalert.simulator.MockTcpServer;
import com.networkalert.simulator.MockUdpServer;
import com.networkalert.legacy.components.LatencyChartPanel;
import com.networkalert.legacy.components.PortScannerPanel;
import com.networkalert.legacy.components.SimulatorPanel;
import com.networkalert.legacy.components.StatusBadgeRenderer;
import com.networkalert.util.CsvExporter;
import com.networkalert.util.LoggerUtil;
import com.networkalert.web.EmbeddedWebDashboard;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Cửa sổ chính điều khiển toàn bộ Hệ thống Giám sát & Cảnh báo Kết nối Mạng
 */
public class MainFrame extends JFrame {
    private final MonitorService monitorService;
    private final AlertService alertService;
    private final TargetRepository targetRepository;
    private final IncidentRepository incidentRepository;

    private final MockTcpServer mockTcpServer = new MockTcpServer(9000);
    private final MockUdpServer mockUdpServer = new MockUdpServer(9001);
    private final MockHttpServer mockHttpServer = new MockHttpServer(9002);

    // KPI Cards
    private final JLabel lblTotalServers = new JLabel("0");
    private final JLabel lblOnlineServers = new JLabel("0");
    private final JLabel lblOfflineServers = new JLabel("0");
    private final JLabel lblAvgLatency = new JLabel("0 ms");

    // Table
    private DefaultTableModel tableModel;
    private JTable serverTable;
    private TableRowSorter<DefaultTableModel> sorter;
    private final JTextField txtSearch = new JTextField(12);
    private final JComboBox<String> cmbProtocolFilter = new JComboBox<>(new String[]{"Tất cả giao thức", "TCP", "UDP", "HTTP", "HTTPS", "ICMP", "DNS"});

    // Right details & Components
    private LatencyChartPanel latencyChartPanel;
    private SimulatorPanel simulatorPanel;
    private PortScannerPanel portScannerPanel;
    private DefaultTableModel incidentTableModel;
    private JTable incidentTable;
    private final JTextArea txtLiveConsole = new JTextArea(5, 50);

    // Buttons & Services
    private JButton btnStartStop;
    private boolean isDarkMode = true;
    private final EmbeddedWebDashboard webDashboard;
    private SystemTrayManager systemTrayManager;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM");

    public MainFrame(MonitorService monitorService, AlertService alertService,
                     TargetRepository targetRepo, IncidentRepository incidentRepo) {
        this.monitorService = monitorService;
        this.alertService = alertService;
        this.targetRepository = targetRepo;
        this.incidentRepository = incidentRepo;
        this.webDashboard = new EmbeddedWebDashboard(8080, monitorService, incidentRepo);

        setTitle("Network Connection Alert System - Giám Sát & Cảnh Báo Kết Nối Mạng");
        setSize(1240, 780);
        setMinimumSize(new Dimension(1000, 650));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        // Đăng ký sự kiện thu nhỏ vào Tray khi bấm (X)
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (alertService.getConfig().isMinimizeToTrayOnClose() && systemTrayManager != null && systemTrayManager.isTraySupported()) {
                    systemTrayManager.minimizeToTray();
                } else {
                    int confirm = JOptionPane.showConfirmDialog(MainFrame.this,
                            "Bạn có muốn thoát hoàn toàn chương trình không?",
                            "Xác nhận thoát", JOptionPane.YES_NO_OPTION);
                    if (confirm == JOptionPane.YES_OPTION) {
                        webDashboard.stop();
                        dispose();
                        System.exit(0);
                    }
                }
            }
        });

        initUi();
        systemTrayManager = new SystemTrayManager(this, monitorService);
        setupListeners();
        loadInitialData();

        // Tự động khởi chạy bộ giám sát ngầm & Web Dashboard
        monitorService.startAll();
        webDashboard.start();
        updateMonitoringButtonState();
    }

    private void initUi() {
        getContentPane().setLayout(new BorderLayout());

        // 1. Header & KPI Cards
        JPanel topContainer = new JPanel(new BorderLayout(5, 5));
        topContainer.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        topContainer.add(createKpiPanel(), BorderLayout.NORTH);
        topContainer.add(createToolBar(), BorderLayout.SOUTH);
        getContentPane().add(topContainer, BorderLayout.NORTH);

        // 2. Center Content SplitPane (Trái: Danh sách Server | Phải: Biểu đồ & Giả lập & Incident)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setResizeWeight(0.55);
        mainSplit.setDividerLocation(680);

        mainSplit.setLeftComponent(createServerListPanel());
        mainSplit.setRightComponent(createDetailTabbedPane());

        // 3. Bottom Live Console
        JPanel bottomContainer = new JPanel(new BorderLayout());
        bottomContainer.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));

        JPanel consoleHeader = new JPanel(new BorderLayout());
        JLabel lblLogTitle = new JLabel(" Nhập ký hoạt động thời gian thực (Live System Log):");
        lblLogTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        JButton btnClearLog = new JButton("Xóa Màn Hình Log");
        btnClearLog.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnClearLog.addActionListener(e -> txtLiveConsole.setText(""));
        consoleHeader.add(lblLogTitle, BorderLayout.WEST);
        consoleHeader.add(btnClearLog, BorderLayout.EAST);

        txtLiveConsole.setEditable(false);
        txtLiveConsole.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollConsole = new JScrollPane(txtLiveConsole);
        scrollConsole.setPreferredSize(new Dimension(100, 110));

        bottomContainer.add(consoleHeader, BorderLayout.NORTH);
        bottomContainer.add(scrollConsole, BorderLayout.CENTER);

        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplit, bottomContainer);
        verticalSplit.setResizeWeight(0.80);
        verticalSplit.setDividerLocation(520);

        getContentPane().add(verticalSplit, BorderLayout.CENTER);
    }

    private JPanel createKpiPanel() {
        JPanel kpiContainer = new JPanel(new GridLayout(1, 4, 12, 0));
        kpiContainer.add(createKpiCard("TỔNG SỐ MÁY CHỦ", lblTotalServers, new Color(52, 152, 219)));
        kpiContainer.add(createKpiCard("MÁY CHỦ ONLINE", lblOnlineServers, new Color(46, 204, 113)));
        kpiContainer.add(createKpiCard("SỰ CỐ OFFLINE", lblOfflineServers, new Color(231, 76, 60)));
        kpiContainer.add(createKpiCard("ĐỘ TRỄ TRUNG BÌNH", lblAvgLatency, new Color(241, 196, 15)));
        return kpiContainer;
    }

    private JPanel createKpiCard(String title, JLabel valueLabel, Color accentColor) {
        JPanel card = new JPanel(new BorderLayout(5, 5)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(accentColor);
                g2.fillRoundRect(0, 0, 5, getHeight(), 4, 4);
                g2.dispose();
            }
        };
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 20), 1),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        titleLbl.setForeground(new Color(160, 160, 160));

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        valueLabel.setForeground(accentColor);

        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JButton btnAdd = new JButton("+ Thêm Máy Chủ");
        btnAdd.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnAdd.addActionListener(e -> onAddServer());

        btnStartStop = new JButton("Tạm Dừng Giám Sát");
        btnStartStop.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnStartStop.addActionListener(e -> onToggleMonitoring());

        JButton btnCheckNow = new JButton("⚡ Kiểm Tra Ngay");
        btnCheckNow.addActionListener(e -> onCheckSelectedNow());

        JButton btnSettings = new JButton("⚙ Cấu Hình Cảnh Báo & DB");
        btnSettings.addActionListener(e -> new SettingsDialog(this, alertService).setVisible(true));

        JButton btnExport = new JButton("📊 Xuất Báo Cáo CSV");
        btnExport.addActionListener(e -> onExportCsv());

        JButton btnWebDashboard = new JButton("🌐 Web Dashboard");
        btnWebDashboard.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnWebDashboard.setForeground(new Color(52, 152, 219));
        btnWebDashboard.addActionListener(e -> onOpenWebDashboard());

        JButton btnToggleTheme = new JButton("🌓 Giao Diện");
        btnToggleTheme.addActionListener(e -> toggleTheme());

        toolBar.add(btnAdd);
        toolBar.addSeparator(new Dimension(8, 0));
        toolBar.add(btnStartStop);
        toolBar.addSeparator(new Dimension(8, 0));
        toolBar.add(btnCheckNow);
        toolBar.addSeparator(new Dimension(8, 0));
        toolBar.add(btnSettings);
        toolBar.addSeparator(new Dimension(8, 0));
        toolBar.add(btnExport);
        toolBar.addSeparator(new Dimension(8, 0));
        toolBar.add(btnWebDashboard);
        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(btnToggleTheme);

        return toolBar;
    }

    private JPanel createServerListPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Thanh Tìm kiếm & Lọc
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filterBar.add(new JLabel("Tìm kiếm:"));
        filterBar.add(txtSearch);
        filterBar.add(new JLabel("Lọc:"));
        filterBar.add(cmbProtocolFilter);

        txtSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        cmbProtocolFilter.addActionListener(e -> applyFilter());

        panel.add(filterBar, BorderLayout.NORTH);

        // Bảng dữ liệu Máy chủ
        String[] columns = {"ID", "Tên Máy Chủ", "Host / IP", "Cổng", "Giao Thức", "Trạng Thái", "Độ Trễ", "Uptime %", "Bật"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                switch (columnIndex) {
                    case 0: case 3: return Integer.class;
                    case 4: return ProtocolType.class;
                    case 5: return ServerStatus.class;
                    case 6: return Long.class;
                    case 7: return Double.class;
                    case 8: return Boolean.class;
                    default: return String.class;
                }
            }
        };

        serverTable = new JTable(tableModel);
        serverTable.setRowHeight(32);
        serverTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sorter = new TableRowSorter<>(tableModel);
        serverTable.setRowSorter(sorter);

        // Gán Renderers
        StatusBadgeRenderer badgeRenderer = new StatusBadgeRenderer();
        serverTable.getColumnModel().getColumn(4).setCellRenderer(badgeRenderer); // Protocol
        serverTable.getColumnModel().getColumn(5).setCellRenderer(badgeRenderer); // Status
        serverTable.getColumnModel().getColumn(6).setCellRenderer(badgeRenderer); // Latency
        serverTable.getColumnModel().getColumn(7).setCellRenderer(badgeRenderer); // Uptime %

        serverTable.getColumnModel().getColumn(0).setPreferredWidth(35);
        serverTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        serverTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        serverTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        serverTable.getColumnModel().getColumn(4).setPreferredWidth(85);
        serverTable.getColumnModel().getColumn(5).setPreferredWidth(95);
        serverTable.getColumnModel().getColumn(6).setPreferredWidth(75);
        serverTable.getColumnModel().getColumn(7).setPreferredWidth(70);
        serverTable.getColumnModel().getColumn(8).setPreferredWidth(40);

        serverTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onServerSelected();
            }
        });

        JScrollPane scrollTable = new JScrollPane(serverTable);
        panel.add(scrollTable, BorderLayout.CENTER);

        return panel;
    }

    private JTabbedPane createDetailTabbedPane() {
        JTabbedPane tabbedPane = new JTabbedPane();

        // Tab 1: Biểu đồ độ trễ
        JPanel chartContainer = new JPanel(new BorderLayout(5, 5));
        latencyChartPanel = new LatencyChartPanel();
        chartContainer.add(latencyChartPanel, BorderLayout.CENTER);

        JPanel actionBtnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        JButton btnEdit = new JButton("Sửa Máy Chủ");
        btnEdit.addActionListener(e -> onEditSelectedServer());
        JButton btnDelete = new JButton("Xóa Máy Chủ");
        btnDelete.addActionListener(e -> onDeleteSelectedServer());
        JButton btnToggleActive = new JButton("Bật/Tắt Giám Sát");
        btnToggleActive.addActionListener(e -> onToggleActiveSelectedServer());

        actionBtnBar.add(btnEdit);
        actionBtnBar.add(btnDelete);
        actionBtnBar.add(btnToggleActive);
        chartContainer.add(actionBtnBar, BorderLayout.SOUTH);

        tabbedPane.addTab("📈 Biểu Đồ Độ Trễ (Real-time)", chartContainer);

        // Tab 2: Lịch sử sự cố mạng (Incident Logs)
        tabbedPane.addTab("🚨 Lịch Sử Sự Cố (Incidents)", createIncidentPanel());

        // Tab 3: Giả lập mạng (Simulator)
        simulatorPanel = new SimulatorPanel(mockTcpServer, mockUdpServer, mockHttpServer);
        tabbedPane.addTab("🛠 Giả Lập Mạng (Simulator)", simulatorPanel);

        // Tab 4: Quét cổng mạng & Khám phá dịch vụ (Port Scanner)
        portScannerPanel = new PortScannerPanel(monitorService, targetRepository, this::onTargetAddedFromScanner);
        tabbedPane.addTab("🔍 Quét Cổng (Scanner)", portScannerPanel);

        return tabbedPane;
    }

    private JPanel createIncidentPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        String[] incCols = {"ID", "Máy Chủ", "Địa Chỉ", "Sự Cố", "Thời Gian", "Chi Tiết"};
        incidentTableModel = new DefaultTableModel(incCols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        incidentTable = new JTable(incidentTableModel);
        incidentTable.setRowHeight(26);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnRefreshInc = new JButton("Tải Lại Lịch Sử Sự Cố");
        btnRefreshInc.addActionListener(e -> refreshIncidentTable());
        bar.add(btnRefreshInc);

        p.add(new JScrollPane(incidentTable), BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    private void setupListeners() {
        // Cập nhật khi có dữ liệu kiểm tra mới từ Background Worker
        monitorService.addUpdateListener((target, result) -> SwingUtilities.invokeLater(() -> {
            updateTableRow(target);
            updateKpis();

            ServerTarget selected = getSelectedTarget();
            if (selected != null && selected.getId() == target.getId()) {
                latencyChartPanel.updateTarget(selected, alertService.getConfig().getLatencyThresholdMs());
            }
        }));

        // Toast cảnh báo
        alertService.addToastListener((title, message) -> SwingUtilities.invokeLater(() -> {
            boolean isError = title.contains("CẢNH BÁO");
            NotificationToast.show(title, message, isError);
            if (systemTrayManager != null && !isVisible()) {
                systemTrayManager.showNotification(title, message, isError ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO);
            }
            refreshIncidentTable();
        }));

        // Log Console
        LoggerUtil.addListener(logLine -> SwingUtilities.invokeLater(() -> {
            txtLiveConsole.append(logLine + "\n");
            txtLiveConsole.setCaretPosition(txtLiveConsole.getDocument().getLength());
        }));
    }

    private void loadInitialData() {
        List<ServerTarget> targets = targetRepository.getAllTargets();
        monitorService.setTargets(targets);

        tableModel.setRowCount(0);
        for (ServerTarget t : targets) {
            tableModel.addRow(new Object[]{
                    t.getId(),
                    t.getName(),
                    t.getHost(),
                    t.getPort(),
                    t.getProtocol(),
                    t.getStatus(),
                    t.getCurrentLatency(),
                    t.getUptimePercentage(),
                    t.isEnabled()
            });
        }
        updateKpis();
        refreshIncidentTable();

        if (serverTable.getRowCount() > 0) {
            serverTable.setRowSelectionInterval(0, 0);
        }
    }

    private void updateTableRow(ServerTarget target) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if ((int) tableModel.getValueAt(i, 0) == target.getId()) {
                tableModel.setValueAt(target.getStatus(), i, 5);
                tableModel.setValueAt(target.getCurrentLatency(), i, 6);
                tableModel.setValueAt(target.getUptimePercentage(), i, 7);
                tableModel.setValueAt(target.isEnabled(), i, 8);
                break;
            }
        }
    }

    private void updateKpis() {
        List<ServerTarget> targets = monitorService.getTargets();
        int total = targets.size();
        int online = 0;
        int offline = 0;
        long sumLatency = 0;
        int activeCount = 0;

        for (ServerTarget t : targets) {
            if (t.getStatus() == ServerStatus.ONLINE || t.getStatus() == ServerStatus.WARNING) {
                online++;
                if (t.getCurrentLatency() > 0) {
                    sumLatency += t.getCurrentLatency();
                    activeCount++;
                }
            } else if (t.getStatus() == ServerStatus.OFFLINE) {
                offline++;
            }
        }

        lblTotalServers.setText(String.valueOf(total));
        lblOnlineServers.setText(String.valueOf(online));
        lblOfflineServers.setText(String.valueOf(offline));
        long avg = activeCount > 0 ? (sumLatency / activeCount) : 0;
        lblAvgLatency.setText(avg + " ms");

        if (systemTrayManager != null) {
            systemTrayManager.updateStatusIcon(offline > 0);
        }
    }

    private void onServerSelected() {
        ServerTarget selected = getSelectedTarget();
        if (selected != null) {
            latencyChartPanel.updateTarget(selected, alertService.getConfig().getLatencyThresholdMs());
        }
    }

    private ServerTarget getSelectedTarget() {
        int selectedRow = serverTable.getSelectedRow();
        if (selectedRow >= 0) {
            int modelRow = serverTable.convertRowIndexToModel(selectedRow);
            int id = (int) tableModel.getValueAt(modelRow, 0);
            return monitorService.getTargets().stream().filter(t -> t.getId() == id).findFirst().orElse(null);
        }
        return null;
    }

    private void onAddServer() {
        TargetDialog dialog = new TargetDialog(this, null);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            ServerTarget newTarget = dialog.getTarget();
            targetRepository.addTarget(newTarget);
            monitorService.addTarget(newTarget);

            tableModel.addRow(new Object[]{
                    newTarget.getId(),
                    newTarget.getName(),
                    newTarget.getHost(),
                    newTarget.getPort(),
                    newTarget.getProtocol(),
                    newTarget.getStatus(),
                    newTarget.getCurrentLatency(),
                    newTarget.getUptimePercentage(),
                    newTarget.isEnabled()
            });
            updateKpis();
            LoggerUtil.info("Đã thêm máy chủ mới: " + newTarget.getName());
        }
    }

    private void onEditSelectedServer() {
        ServerTarget selected = getSelectedTarget();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một máy chủ cần sửa!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        TargetDialog dialog = new TargetDialog(this, selected);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            targetRepository.updateTarget(selected);
            monitorService.updateTarget(selected);

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if ((int) tableModel.getValueAt(i, 0) == selected.getId()) {
                    tableModel.setValueAt(selected.getName(), i, 1);
                    tableModel.setValueAt(selected.getHost(), i, 2);
                    tableModel.setValueAt(selected.getPort(), i, 3);
                    tableModel.setValueAt(selected.getProtocol(), i, 4);
                    tableModel.setValueAt(selected.isEnabled(), i, 8);
                    break;
                }
            }
            updateKpis();
            LoggerUtil.info("Đã cập nhật máy chủ: " + selected.getName());
        }
    }

    private void onDeleteSelectedServer() {
        ServerTarget selected = getSelectedTarget();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một máy chủ cần xóa!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc muốn xóa máy chủ '" + selected.getName() + "' khỏi danh sách giám sát?",
                "Xác nhận xóa", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            targetRepository.deleteTarget(selected.getId());
            monitorService.removeTarget(selected.getId());

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if ((int) tableModel.getValueAt(i, 0) == selected.getId()) {
                    tableModel.removeRow(i);
                    break;
                }
            }
            updateKpis();
            LoggerUtil.info("Đã xóa máy chủ: " + selected.getName());
        }
    }

    private void onToggleActiveSelectedServer() {
        ServerTarget selected = getSelectedTarget();
        if (selected == null) return;

        selected.setEnabled(!selected.isEnabled());
        targetRepository.updateTarget(selected);
        monitorService.updateTarget(selected);
        updateTableRow(selected);
        updateKpis();
        LoggerUtil.info("Đã " + (selected.isEnabled() ? "bật" : "tạm dừng") + " giám sát cho " + selected.getName());
    }

    private void onCheckSelectedNow() {
        ServerTarget selected = getSelectedTarget();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một máy chủ để kiểm tra ngay!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        LoggerUtil.info("Thực hiện kiểm tra ngay lập tức: " + selected.getName());
        monitorService.checkTargetNow(selected);
    }

    private void onToggleMonitoring() {
        if (monitorService.isRunning()) {
            monitorService.stopAll();
        } else {
            monitorService.startAll();
        }
        updateMonitoringButtonState();
    }

    private void updateMonitoringButtonState() {
        if (monitorService.isRunning()) {
            btnStartStop.setText("⏸ Tạm Dừng Giám Sát");
            btnStartStop.setForeground(new Color(231, 76, 60));
        } else {
            btnStartStop.setText("▶ Bắt Đầu Giám Sát");
            btnStartStop.setForeground(new Color(46, 204, 113));
        }
    }

    private void applyFilter() {
        String query = txtSearch.getText().trim();
        String proto = (String) cmbProtocolFilter.getSelectedItem();

        RowFilter<DefaultTableModel, Object> rf = new RowFilter<DefaultTableModel, Object>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ?> entry) {
                String name = entry.getStringValue(1).toLowerCase();
                String host = entry.getStringValue(2).toLowerCase();
                String p = entry.getStringValue(4);

                boolean matchText = query.isEmpty() || name.contains(query.toLowerCase()) || host.contains(query.toLowerCase());
                boolean matchProto = proto == null || "Tất cả giao thức".equals(proto) || proto.equalsIgnoreCase(p);
                return matchText && matchProto;
            }
        };
        sorter.setRowFilter(rf);
    }

    private void refreshIncidentTable() {
        List<IncidentLog> logs = incidentRepository.getRecentIncidents(100);
        incidentTableModel.setRowCount(0);
        for (IncidentLog log : logs) {
            incidentTableModel.addRow(new Object[]{
                    log.getId(),
                    log.getTargetName(),
                    log.getHost() + ":" + log.getPort(),
                    log.getEventType(),
                    log.getEventTime() != null ? log.getEventTime().format(TIME_FMT) : "",
                    log.getMessage()
            });
        }
    }

    private void onExportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("BaoCao_GiamSatMang_" + System.currentTimeMillis() + ".csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            List<ServerTarget> targets = monitorService.getTargets();
            List<IncidentLog> incidents = incidentRepository.getRecentIncidents(200);
            boolean ok = CsvExporter.exportReport(f, targets, incidents);
            if (ok) {
                JOptionPane.showMessageDialog(this, "Xuất báo cáo CSV thành công tới:\n" + f.getAbsolutePath(), "Thành công", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Lỗi khi xuất báo cáo!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void toggleTheme() {
        try {
            if (isDarkMode) {
                UIManager.setLookAndFeel(new FlatLightLaf());
                isDarkMode = false;
            } else {
                UIManager.setLookAndFeel(new FlatDarkLaf());
                isDarkMode = true;
            }
            SwingUtilities.updateComponentTreeUI(this);
            latencyChartPanel.repaint();
        } catch (Exception ex) {
            LoggerUtil.error("Lỗi chuyển đổi Theme: " + ex.getMessage());
        }
    }

    private void onOpenWebDashboard() {
        String url = "http://localhost:" + webDashboard.getPort();
        String lanUrl = "http://" + webDashboard.getLocalIpAddress() + ":" + webDashboard.getPort();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
            }
        } catch (Exception ignored) {}

        JOptionPane.showMessageDialog(this,
                "<html><body style='width: 340px; font-size: 12px; line-height: 1.5;'>" +
                "<b>🌐 Web Dashboard Mini đang hoạt động!</b><br><br>" +
                "• <b>Trên máy tính này:</b> <a href='" + url + "'>" + url + "</a><br>" +
                "• <b>Trên Điện thoại di động (cùng Wi-Fi):</b><br>" +
                "<span style='color:#3498db; font-size: 13px; font-weight:bold;'>" + lanUrl + "</span><br><br>" +
                "<i>Chỉ cần dùng trình duyệt web trên điện thoại gõ địa chỉ trên là có thể theo dõi máy chủ từ xa!</i>" +
                "</body></html>",
                "Web Dashboard Di Động", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onTargetAddedFromScanner(ServerTarget newTarget) {
        tableModel.addRow(new Object[]{
                newTarget.getId(),
                newTarget.getName(),
                newTarget.getHost(),
                newTarget.getPort(),
                newTarget.getProtocol(),
                newTarget.getStatus(),
                newTarget.getCurrentLatency(),
                newTarget.getUptimePercentage(),
                newTarget.isEnabled()
        });
        updateKpis();
        LoggerUtil.info("Đã bổ sung mục tiêu từ Port Scanner: " + newTarget.getName());
    }
}

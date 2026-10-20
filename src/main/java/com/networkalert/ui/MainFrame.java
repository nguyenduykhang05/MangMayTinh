package com.networkalert.ui;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.service.AlertService;
import com.networkalert.service.MonitorService;
import com.networkalert.simulator.MockHttpServer;
import com.networkalert.simulator.MockTcpServer;
import com.networkalert.simulator.MockUdpServer;
import com.networkalert.ui.components.AgentClusterPanel;
import com.networkalert.ui.components.AppToolBar;
import com.networkalert.ui.components.DetailTabbedPane;
import com.networkalert.ui.components.HeaderPanel;
import com.networkalert.ui.components.HealthBarPanel;
import com.networkalert.ui.components.LiveLogPanel;
import com.networkalert.ui.components.ServerListPanel;
import com.networkalert.ui.components.StatusBar;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;
import com.networkalert.util.CsvExporter;
import com.networkalert.util.LoggerUtil;
import com.networkalert.web.EmbeddedWebDashboard;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Cửa sổ chính hiện đại của Network Connection Alert System
 * Thiết kế chuẩn theo UI_REDESIGN.md
 */
public class MainFrame extends JFrame {
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainFrame.class);
    private static final String PREF_W = "window_width";
    private static final String PREF_H = "window_height";
    private static final String PREF_X = "window_x";
    private static final String PREF_Y = "window_y";
    private static final String PREF_SPLIT_H = "split_h_location";
    private static final String PREF_SPLIT_V = "split_v_location";

    private final MonitorService monitorService;
    private final AlertService alertService;
    private final TargetRepository targetRepository;
    private final IncidentRepository incidentRepository;

    private final MockTcpServer mockTcpServer = new MockTcpServer(9000);
    private final MockUdpServer mockUdpServer = new MockUdpServer(9001);
    private final MockHttpServer mockHttpServer = new MockHttpServer(9002);
    private final EmbeddedWebDashboard webDashboard;

    // Thành phần khung chính (Stage 2 & Stage 3)
    private HeaderPanel headerPanel;
    private HealthBarPanel healthBarPanel;
    private AppToolBar toolBar;
    private StatusBar statusBar;

    private JSplitPane mainSplitH;
    private JSplitPane mainSplitV;
    private ServerListPanel serverListPanel;
    private DetailTabbedPane detailTabbedPane;
    private LiveLogPanel liveLogPanel;

    private boolean isLogCollapsed = false;
    private int lastLogHeight = 180;

    // Giữ đối tượng SystemTrayManager cũ nếu người dùng muốn thu nhỏ xuống khay
    private SystemTrayManager systemTrayManager;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public MainFrame(MonitorService monitorService, AlertService alertService,
                     TargetRepository targetRepo, IncidentRepository incidentRepo) {
        this.monitorService = monitorService;
        this.alertService = alertService;
        this.targetRepository = targetRepo;
        this.incidentRepository = incidentRepo;
        this.webDashboard = new EmbeddedWebDashboard(8080, monitorService, incidentRepo, targetRepo, alertService);

        initWindowProperties();
        initLayout();
        setupKeyBindings();
        setupListeners();
        loadInitialData();

        // Khởi động các mock server phục vụ demo mượt mà ngay khi mở ứng dụng
        mockTcpServer.start();
        mockUdpServer.start();
        mockHttpServer.start();

        // Khởi động Web Dashboard Mạng Thật
        webDashboard.start();
        if (statusBar != null) {
            statusBar.setWebPort(webDashboard.getPort());
        }

        // Khởi động các dịch vụ giám sát
        monitorService.startAll();
        updateMonitoringState();
    }

    private void initWindowProperties() {
        setTitle(I18n.get("app.title"));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1160, 720));

        // Khôi phục kích thước và vị trí từ Preferences
        int savedW = PREFS.getInt(PREF_W, 1440);
        int savedH = PREFS.getInt(PREF_H, 900);
        setSize(Math.max(1280, savedW), Math.max(860, savedH));

        int savedX = PREFS.getInt(PREF_X, -1);
        int savedY = PREFS.getInt(PREF_Y, -1);
        if (savedX >= 0 && savedY >= 0) {
            setLocation(savedX, savedY);
        } else {
            setLocationRelativeTo(null);
        }

        // Lắng nghe sự kiện di chuyển và co giãn để lưu lại
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                restoreSplitterPreferences();
            }
            @Override
            public void componentResized(ComponentEvent e) {
                if (getExtendedState() == JFrame.NORMAL) {
                    PREFS.putInt(PREF_W, getWidth());
                    PREFS.putInt(PREF_H, getHeight());
                }
            }
            @Override
            public void componentMoved(ComponentEvent e) {
                if (getExtendedState() == JFrame.NORMAL) {
                    PREFS.putInt(PREF_X, getX());
                    PREFS.putInt(PREF_Y, getY());
                }
            }
        });

        // Đóng ứng dụng hoặc thu nhỏ vào khay hệ thống
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
                        saveSplitterPreferences();
                        if (detailTabbedPane != null && detailTabbedPane.getAgentClusterPanel() != null && detailTabbedPane.getAgentClusterPanel().getServerInstance() != null) {
                            detailTabbedPane.getAgentClusterPanel().getServerInstance().stop();
                        }
                        webDashboard.stop();
                        mockTcpServer.stop();
                        mockUdpServer.stop();
                        mockHttpServer.stop();
                        dispose();
                        System.exit(0);
                    }
                }
            }
        });
    }

    private void initLayout() {
        JPanel contentPane = new JPanel(new BorderLayout(0, 0));
        contentPane.setBorder(BorderFactory.createEmptyBorder(10, 14, 6, 14));
        contentPane.setBackground(ThemeManager.bgWindow());
        setContentPane(contentPane);

        // ══════════════════════════════════════════════════════════════════
        // NORTH: Header + HealthBar + Toolbar (3 hàng gọn nhẹ)
        // ══════════════════════════════════════════════════════════════════
        JPanel topStrip = new JPanel();
        topStrip.setLayout(new BoxLayout(topStrip, BoxLayout.Y_AXIS));
        topStrip.setOpaque(false);

        // Hàng 1: Logo + Title + chip trạng thái + Dark/Light button
        headerPanel = new HeaderPanel(this::onToggleMonitoring, () -> ThemeManager.toggleTheme(this));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topStrip.add(headerPanel);

        // Hàng 2: HealthBar — Icon kết luận + text + thanh tỉ lệ + 5 số liệu
        healthBarPanel = new HealthBarPanel(this::onHealthFilterSelected);
        healthBarPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topStrip.add(Box.createVerticalStrut(UIScale.scale(6)));
        topStrip.add(healthBarPanel);

        // Hàng 3: Toolbar action buttons
        toolBar = new AppToolBar(
                this::onAddServer,
                this::onCheckSelectedNow,
                this::onToggleMonitoring,
                this::onOpenSettings,
                this::onExportCsv,
                this::onOpenWebDashboard,
                () -> { if (detailTabbedPane != null) detailTabbedPane.selectAgentTab(); }
        );
        toolBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        topStrip.add(Box.createVerticalStrut(UIScale.scale(4)));
        topStrip.add(toolBar);

        contentPane.add(topStrip, BorderLayout.NORTH);

        // ══════════════════════════════════════════════════════════════════
        // CENTER: Cấu trúc 3 vùng ĐÚNG theo bản gốc:
        //
        //  ┌──────────────────────────┬─────────────────────────────────┐
        //  │                          │                                  │
        //  │  Bảng máy chủ           │  Tabs chi tiết                  │
        //  │  (ServerListPanel)       │  (Biểu đồ / Sự cố /             │
        //  │  ~55% chiều ngang        │   Giả lập / Quét / Agent)       │
        //  │                          │  ~45% chiều ngang               │
        //  │                          │                                  │
        //  ├──────────────────────────┴─────────────────────────────────┤
        //  │          Nhật ký hoạt động (LiveLogPanel)                  │
        //  │          Full width, ~28% chiều dọc                        │
        //  └────────────────────────────────────────────────────────────┘
        //
        // ══════════════════════════════════════════════════════════════════
        serverListPanel = new ServerListPanel(
                this::onAddServer,
                this::onTargetSelected,
                this::onEditServer,
                this::onDeleteServer,
                this::onCheckTargetNow,
                this::onToggleActiveServer
        );

        detailTabbedPane = new DetailTabbedPane(
                incidentRepository,
                mockTcpServer, mockUdpServer, mockHttpServer,
                monitorService, targetRepository,
                target -> {
                    serverListPanel.addTarget(target);
                    detailTabbedPane.setAllTargets(monitorService.getTargets());
                    updateMetricsAndHealth();
                    NotificationToast.show("Đã thêm máy chủ",
                            "Đã thêm " + target.getName() + " vào danh sách giám sát.", false);
                },
                (title, msg) -> NotificationToast.show(title, msg, false)
        );
        if (detailTabbedPane.getAgentClusterPanel() != null) {
            detailTabbedPane.getAgentClusterPanel().addClusterStateListener(() ->
                    SwingUtilities.invokeLater(this::updateMetricsAndHealth));
        }

        liveLogPanel = new LiveLogPanel(this::toggleLogCollapse);

        // Split NGANG (trái/phải): ServerList | DetailTabs (~59% / ~41%)
        mainSplitH = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, serverListPanel, detailTabbedPane);
        mainSplitH.setContinuousLayout(true);
        mainSplitH.setDividerSize(UIScale.scale(5));
        mainSplitH.setBorder(BorderFactory.createEmptyBorder());
        mainSplitH.setOpaque(false);
        mainSplitH.setResizeWeight(0.53);
        serverListPanel.setMinimumSize(new Dimension(UIScale.scale(530), UIScale.scale(200)));
        detailTabbedPane.setMinimumSize(new Dimension(UIScale.scale(400), UIScale.scale(200)));

        mainSplitH.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean initialized = false;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (!initialized && mainSplitH.getWidth() > 200) {
                    initialized = true;
                    restoreSplitterPreferences();
                }
            }
        });

        // Split DỌC (trên/dưới): [ServerList | Tabs] | LiveLog (~73% / ~27%)
        mainSplitV = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplitH, liveLogPanel);
        mainSplitV.setContinuousLayout(true);
        mainSplitV.setDividerSize(UIScale.scale(5));
        mainSplitV.setBorder(BorderFactory.createEmptyBorder());
        mainSplitV.setOpaque(false);
        mainSplitV.setResizeWeight(0.73);
        liveLogPanel.setMinimumSize(new Dimension(UIScale.scale(600), UIScale.scale(120)));

        contentPane.add(mainSplitV, BorderLayout.CENTER);

        // ── SOUTH: Status Bar ─────────────────────────────────────────────
        statusBar = new StatusBar(this::onOpenSettings, this::onOpenWebDashboard);
        contentPane.add(statusBar, BorderLayout.SOUTH);

        // Khôi phục vị trí splitter sau khi UI render
        SwingUtilities.invokeLater(this::restoreSplitterPreferences);
    }

    private void restoreSplitterPreferences() {
        // mainSplitV (DỌC): mainSplitH/[ServerList|Tabs] (TRÊN) | liveLogPanel (DƯỚI)
        int totalH = mainSplitV.getHeight();
        if (totalH > 100) {
            int logH = Math.max(UIScale.scale(130), Math.min(UIScale.scale(220), (int) (totalH * 0.25)));
            mainSplitV.setDividerLocation(totalH - logH);
        } else {
            mainSplitV.setDividerLocation(UIScale.scale(430));
        }

        // mainSplitH (NGANG): serverListPanel (TRÁI ~53%) | detailTabbedPane (PHẢI ~47%)
        // Bảng máy chủ chiếm ~53% chiều ngang, panel chi tiết tối thiểu 400px tránh bóp biểu đồ ở 1366x768 (ERRATA §E13 #3)
        int totalW = mainSplitH.getWidth();
        if (totalW > 200) {
            int leftW = (int) (totalW * 0.53);
            int minLeft = UIScale.scale(530);
            int minRight = UIScale.scale(400);
            mainSplitH.setDividerLocation(
                    Math.max(minLeft, Math.min(leftW, totalW - minRight))
            );
        } else {
            mainSplitH.setDividerLocation(UIScale.scale(640));
        }
    }

    private void saveSplitterPreferences() {
        PREFS.putInt(PREF_SPLIT_H, mainSplitH.getDividerLocation());
        PREFS.putInt(PREF_SPLIT_V, mainSplitV.getDividerLocation());
    }

    public void toggleLogCollapse() {
        if (!isLogCollapsed) {
            // Thu gọn LiveLog: đẩy divider dọc về gần đáy (chỉ còn header ~32px)
            lastLogHeight = mainSplitV.getHeight() - mainSplitV.getDividerLocation();
            mainSplitV.setDividerLocation(mainSplitV.getHeight() - UIScale.scale(32));
            isLogCollapsed = true;
        } else {
            // Khôi phục: tính lại độ cao cũ
            int restoreH = (lastLogHeight > UIScale.scale(60)) ? lastLogHeight : UIScale.scale(160);
            mainSplitV.setDividerLocation(mainSplitV.getHeight() - restoreH);
            isLogCollapsed = false;
        }
    }

    private void setupKeyBindings() {
        JRootPane root = getRootPane();

        // Ctrl+N: Thêm máy chủ
        root.registerKeyboardAction(e -> onAddServer(),
                KeyStroke.getKeyStroke(KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // F4: Chuyển nhanh sang tab Trạm Agent
        root.registerKeyboardAction(e -> {
            if (detailTabbedPane != null) detailTabbedPane.selectAgentTab();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        // F5: Kiểm tra ngay
        root.registerKeyboardAction(e -> onCheckSelectedNow(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+P: Tạm dừng / Tiếp tục
        root.registerKeyboardAction(e -> onToggleMonitoring(),
                KeyStroke.getKeyStroke(KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+T: Giao diện sáng/tối
        root.registerKeyboardAction(e -> ThemeManager.toggleTheme(this),
                KeyStroke.getKeyStroke(KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+F: Tìm kiếm trong danh sách
        root.registerKeyboardAction(e -> {
            if (serverListPanel != null) serverListPanel.focusSearch();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+,: Cấu hình
        root.registerKeyboardAction(e -> onOpenSettings(),
                KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+E: Xuất CSV
        root.registerKeyboardAction(e -> onExportCsv(),
                KeyStroke.getKeyStroke(KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+W: Mở Web Dashboard NOC
        root.registerKeyboardAction(e -> onOpenWebDashboard(),
                KeyStroke.getKeyStroke(KeyEvent.VK_W, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+L: Xóa nhật ký hoạt động
        root.registerKeyboardAction(e -> {
            if (liveLogPanel != null) liveLogPanel.clearLogs();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void setupListeners() {
        // Cập nhật khi có kết quả kiểm tra từ Background Worker
        monitorService.addUpdateListener((target, result) -> SwingUtilities.invokeLater(() -> {
            if (target.getStatus() == ServerStatus.ONLINE) {
                com.networkalert.repository.ProblemRepository.getInstance().resolveActiveProblemsForTarget(
                        target.getId(), "Mục tiêu hoạt động ổn định (" + target.getCurrentLatency() + " ms)");
            }
            serverListPanel.updateTarget(target);
            updateMetricsAndHealth();
            detailTabbedPane.refreshIncidents();

            detailTabbedPane.setAllTargets(monitorService.getTargets());
            ServerTarget selected = serverListPanel.getSelectedTarget();
            if (selected != null && selected.getId() == target.getId()) {
                detailTabbedPane.updateTarget(selected, alertService.getConfig().getLatencyThresholdMs());
            }
        }));

        // Lắng nghe sự cố để tự động cập nhật tab Sự cố & Badge
        alertService.addIncidentListener(log -> SwingUtilities.invokeLater(() -> {
            detailTabbedPane.refreshIncidents();
        }));

        // Toast cảnh báo góc phải màn hình
        alertService.addToastListener((title, message) -> SwingUtilities.invokeLater(() -> {
            boolean isError = title.contains("Mất kết nối") || title.contains("OFFLINE");
            NotificationToast.show(title, message, isError);
            detailTabbedPane.refreshIncidents();
        }));
    }

    private void loadInitialData() {
        List<ServerTarget> targets = targetRepository.getAllTargets();
        com.networkalert.repository.ProblemRepository.getInstance().cleanupStaleTestAndRecoveredProblems(targets);
        monitorService.setTargets(targets);
        serverListPanel.setTargets(targets);
        detailTabbedPane.setAllTargets(targets);
        if (!targets.isEmpty()) {
            detailTabbedPane.updateTarget(targets.get(0), alertService.getConfig().getLatencyThresholdMs());
        }
        detailTabbedPane.refreshIncidents();
        updateMetricsAndHealth();
    }

    private void updateMetricsAndHealth() {
        List<ServerTarget> targets = monitorService.getTargets();
        int total = targets.size();
        int online = 0;
        int warning = 0;
        int offline = 0;
        int paused = 0;
        long sumLatency = 0;
        int activeCount = 0;

        for (ServerTarget t : targets) {
            if (!t.isEnabled() || t.getStatus() == ServerStatus.PAUSED) {
                paused++;
            } else if (t.getStatus() == ServerStatus.ONLINE) {
                online++;
                if (t.getCurrentLatency() > 0) {
                    sumLatency += t.getCurrentLatency();
                    activeCount++;
                }
            } else if (t.getStatus() == ServerStatus.WARNING) {
                warning++;
                if (t.getCurrentLatency() > 0) {
                    sumLatency += t.getCurrentLatency();
                    activeCount++;
                }
            } else if (t.getStatus() == ServerStatus.OFFLINE) {
                offline++;
            }
        }

        long avgLatency = activeCount > 0 ? (sumLatency / activeCount) : 0;
        boolean isRunning = monitorService.isRunning();

        // Lấy thông tin trạm Agent
        boolean serverRunning = false;
        int onlineAgents = 0;
        int lostAgents = 0;
        if (detailTabbedPane != null && detailTabbedPane.getAgentClusterPanel() != null) {
            AgentClusterPanel cluster = detailTabbedPane.getAgentClusterPanel();
            serverRunning = cluster.isServerRunning();
            if (serverRunning && cluster.getServerInstance() != null && cluster.getServerInstance().getAgentManager() != null) {
                onlineAgents = cluster.getServerInstance().getAgentManager().getActiveAgentCount();
                lostAgents = cluster.getServerInstance().getAgentManager().getLostAgentCount();
            }
        }

        // Cập nhật dải sức khỏe
        healthBarPanel.updateHealth(total, online, warning, offline, paused, avgLatency, isRunning, serverRunning, onlineAgents, lostAgents);

        // Cập nhật chip header
        headerPanel.setClusterState(serverRunning, onlineAgents, lostAgents);

        // Cập nhật status bar
        String lastCheck = java.time.LocalTime.now().format(TIME_FMT);
        boolean isDbOk = DatabaseManager.getInstance().isConnected();
        int thresholdMs = (int) alertService.getConfig().getLatencyThresholdMs();
        statusBar.updateStatus(total, lastCheck, thresholdMs, isDbOk);
        if (serverRunning && onlineAgents > 0) {
            statusBar.setModeText(String.format("Server • %d Agent online", onlineAgents));
        } else {
            statusBar.setModeText("Chế độ độc lập, kiểm tra mỗi 3 giây");
        }
    }

    private void updateMonitoringState() {
        boolean isRunning = monitorService.isRunning();
        int intervalSec = 5;
        if (!monitorService.getTargets().isEmpty()) {
            intervalSec = monitorService.getTargets().get(0).getIntervalSeconds();
        }
        headerPanel.setMonitoringState(isRunning, intervalSec);
        toolBar.setMonitoringPaused(!isRunning);
        updateMetricsAndHealth();
    }

    // --- Hành động Toolbar & Menu & Danh sách máy chủ ---
    private void onToggleMonitoring() {
        if (monitorService.isRunning()) {
            monitorService.stopAll();
            LoggerUtil.info("Đã tạm dừng giám sát");
        } else {
            monitorService.startAll();
            LoggerUtil.info("Đã bật giám sát");
        }
        updateMonitoringState();
    }

    private void onTargetSelected(ServerTarget target) {
        if (target != null) {
            detailTabbedPane.updateTarget(target, alertService.getConfig().getLatencyThresholdMs());
        }
    }

    private void onAddServer() {
        com.networkalert.ui.components.ServerTargetDialog dialog =
                new com.networkalert.ui.components.ServerTargetDialog(this, null);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            ServerTarget newTarget = dialog.getTarget();
            targetRepository.addTarget(newTarget);
            monitorService.addTarget(newTarget);
            serverListPanel.addTarget(newTarget);
            detailTabbedPane.setAllTargets(monitorService.getTargets());
            updateMetricsAndHealth();
            LoggerUtil.info("Đã thêm máy chủ mới: " + newTarget.getName());
        }
    }

    private void onEditServer(ServerTarget target) {
        if (target == null) return;
        com.networkalert.ui.components.ServerTargetDialog dialog =
                new com.networkalert.ui.components.ServerTargetDialog(this, target);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            targetRepository.updateTarget(target);
            monitorService.updateTarget(target);
            serverListPanel.updateTarget(target);
            detailTabbedPane.setAllTargets(monitorService.getTargets());
            updateMetricsAndHealth();
            LoggerUtil.info("Đã cập nhật máy chủ: " + target.getName());
        }
    }

    private void onDeleteServer(ServerTarget target) {
        if (target == null) return;

        Object[] options = {I18n.get("dialog.delete.button"), I18n.get("dialog.target.cancel")};
        int confirm = JOptionPane.showOptionDialog(this,
                I18n.get("dialog.delete.confirm_msg", target.getName()),
                I18n.get("dialog.delete.confirm_title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]);

        if (confirm == 0) {
            targetRepository.deleteTarget(target.getId());
            monitorService.removeTarget(target.getId());
            serverListPanel.removeTarget(target.getId());
            detailTabbedPane.setAllTargets(monitorService.getTargets());
            updateMetricsAndHealth();
            LoggerUtil.info("Đã xóa máy chủ: " + target.getName());
        }
    }

    private void onToggleActiveServer(ServerTarget target) {
        if (target == null) return;
        target.setEnabled(!target.isEnabled());
        targetRepository.updateTarget(target);
        monitorService.updateTarget(target);
        serverListPanel.updateTarget(target);
        updateMetricsAndHealth();
        LoggerUtil.info("Đã " + (target.isEnabled() ? "bật" : "tạm dừng") + " giám sát cho " + target.getName());
    }

    private void onCheckTargetNow(ServerTarget target) {
        if (target == null) return;
        toolBar.setCheckNowRunning(true);
        LoggerUtil.info("Thực hiện kiểm tra ngay lập tức: " + target.getName());

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                monitorService.checkTargetNow(target);
                return null;
            }
            @Override
            protected void done() {
                toolBar.setCheckNowRunning(false);
            }
        };
        worker.execute();
    }

    private void onCheckSelectedNow() {
        ServerTarget selected = serverListPanel.getSelectedTarget();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một máy chủ để kiểm tra ngay!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        onCheckTargetNow(selected);
    }

    private void onOpenSettings() {
        SettingsDialog dialog = new SettingsDialog(this, alertService);
        dialog.setVisible(true);
        updateMetricsAndHealth();
    }

    private void onExportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("BaoCao_GiamSatMang_" + java.time.Clock.systemDefaultZone().millis() + ".csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            List<ServerTarget> targets = monitorService.getTargets();
            var incidents = incidentRepository.getRecentIncidents(200);
            boolean ok = CsvExporter.exportReport(f, targets, incidents);
            if (ok) {
                JOptionPane.showMessageDialog(this, "Xuất báo cáo CSV thành công tới:\n" + f.getAbsolutePath(),
                        "Thành công", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Lỗi khi xuất báo cáo!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onHealthFilterSelected(String filterKey) {
        if (serverListPanel != null) {
            serverListPanel.setQuickFilter(filterKey);
        }
    }

    public void onOpenWebDashboard() {
        int port = webDashboard.getPort();
        String localUrl = "http://localhost:" + port;
        String lanIp = webDashboard.getLocalIpAddress();
        String lanUrl = "http://" + lanIp + ":" + port;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(localUrl));
            } else {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", localUrl});
            }
            NotificationToast.show("Đã mở Web Dashboard NOC", "Truy cập từ điện thoại qua Wi-Fi: " + lanUrl, false);
            LoggerUtil.info("Đã mở Web Dashboard tại: " + localUrl + " (LAN: " + lanUrl + ")");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng mở trình duyệt và truy cập:\n" + localUrl + "\n(Trên điện thoại: " + lanUrl + ")",
                    "Web Dashboard Mạng Thật", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public MonitorService getMonitorService() { return monitorService; }
    public AlertService getAlertService() { return alertService; }
    public TargetRepository getTargetRepository() { return targetRepository; }
    public IncidentRepository getIncidentRepository() { return incidentRepository; }
}

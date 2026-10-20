package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.agent.AgentConfig;
import com.networkalert.agent.NcasAgent;
import com.networkalert.model.RealNetworkInfo;
import com.networkalert.model.ServerTarget;
import com.networkalert.server.AgentSession;
import com.networkalert.server.NcasServer;
import com.networkalert.service.MonitorService;
import com.networkalert.service.RealNetworkService;
import com.networkalert.ui.NotificationToast;
import com.networkalert.ui.theme.ThemeManager;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab giao diện quản lý cụm giám sát phân tán NCAS (Agent Cluster Management Panel)
 * Thiết kế chống tràn (No-Overlap Card Layout), hỗ trợ:
 * - Khởi động / Dừng NCAS Server trên giao diện
 * - Chạy thử nghiệm 2 Agent mẫu (Hà Nội & TP.HCM) ngay trong app chỉ với 1-Click
 * - Bảng theo dõi Agent thời gian thực (CPU %, RAM MB, Nhịp tim, Số target gán)
 * - Tái cân bằng tải (Dynamic Rebalance)
 * - Sao chép lệnh khởi chạy trạm Agent trên các máy khác
 */
public class AgentClusterPanel extends JPanel {
    private final MonitorService monitorService;
    private NcasServer serverInstance;

    // Quản lý các Agent mẫu phục vụ Demo trực tiếp trên giao diện
    private NcasAgent demoAgentHanoi;
    private NcasAgent demoAgentHcm;
    private boolean demoAgentsRunning = false;

    // Component giao diện Header
    private final JLabel lblServerStatus = new JLabel("⚪ Chế độ: Standalone (Server chưa bật)");
    private final JLabel lblAgentCountBadge = new JLabel("0 Agent trực tuyến");
    private final JLabel lblLanIpBadge = new JLabel("🖧 LAN: 127.0.0.1:9876");
    private final JButton btnToggleServer = new JButton("▶ Bật Server (9876)");
    private final JButton btnDemoAgents = new JButton("🧪 Chạy 2 Agent mẫu");
    private final JButton btnRebalance = new JButton("🔄 Tái phân bổ");
    private final JButton btnRefresh = new JButton("Làm mới");

    private final DefaultTableModel tableModel;
    private final JTable agentTable;

    private final JTextField txtCommandCopy = new JTextField();
    private final JButton btnCopyCommand = new JButton("📋 Sao chép lệnh chạy");

    private static final String DEFAULT_TOKEN = "NCAS-SECRET-TOKEN";

    private final java.util.List<Runnable> clusterStateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addClusterStateListener(Runnable r) {
        if (r != null) clusterStateListeners.add(r);
    }

    private void fireClusterStateChanged() {
        for (Runnable r : clusterStateListeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    public AgentClusterPanel(MonitorService monitorService) {
        this.monitorService = monitorService;
        setLayout(new BorderLayout(0, UIScale.scale(8)));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(8), UIScale.scale(8), UIScale.scale(8)));

        // =====================================================================
        // 1. TOP CARD: Bảng điều khiển Server & Demo Agents (Stack dọc chống đè chữ)
        // =====================================================================
        JPanel topCard = new JPanel();
        topCard.setLayout(new BoxLayout(topCard, BoxLayout.Y_AXIS));
        topCard.setOpaque(true);
        topCard.setBackground(ThemeManager.bgSurface());
        topCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border(), 1),
                BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
        ));

        // Dòng 1: Trạng thái Server & Badge số Agent & IP LAN Máy Chủ
        JPanel rowStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(8), UIScale.scale(2)));
        rowStatus.setOpaque(false);

        lblServerStatus.setFont(ThemeManager.sans(Font.BOLD, 13));
        lblAgentCountBadge.setFont(ThemeManager.sans(Font.BOLD, 11));
        lblAgentCountBadge.putClientProperty("FlatLaf.style", "background: #1E293B; foreground: #38BDF8; arc: 6");
        lblAgentCountBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        lblAgentCountBadge.setOpaque(true);

        lblLanIpBadge.setFont(ThemeManager.sans(Font.BOLD, 11));
        lblLanIpBadge.putClientProperty("FlatLaf.style", "background: #064E3B; foreground: #34D399; arc: 6");
        lblLanIpBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        lblLanIpBadge.setOpaque(true);
        lblLanIpBadge.setToolTipText("Địa chỉ IP LAN thật và cổng TCP của Máy chủ Trung tâm NCAS để các máy thật khác kết nối");

        rowStatus.add(lblServerStatus);
        rowStatus.add(lblAgentCountBadge);
        rowStatus.add(lblLanIpBadge);

        // Dòng 2: Nút bật/tắt Server & Nút chạy thử Agent mẫu
        JPanel rowServerButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), UIScale.scale(4)));
        rowServerButtons.setOpaque(false);

        btnToggleServer.setFont(ThemeManager.sans(Font.BOLD, 11));
        btnToggleServer.putClientProperty("FlatLaf.style", "background: #2563EB; foreground: #FFFFFF; arc: 6; focusWidth: 0");
        btnToggleServer.setMargin(new Insets(4, 8, 4, 8));
        btnToggleServer.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnToggleServer.addActionListener(e -> toggleServer());

        btnDemoAgents.setFont(ThemeManager.sans(Font.BOLD, 11));
        btnDemoAgents.putClientProperty("FlatLaf.style", "background: #059669; foreground: #FFFFFF; arc: 6; focusWidth: 0");
        btnDemoAgents.setMargin(new Insets(4, 8, 4, 8));
        btnDemoAgents.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnDemoAgents.addActionListener(e -> toggleDemoAgents());

        btnRebalance.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btnRebalance.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btnRebalance.setMargin(new Insets(4, 8, 4, 8));
        btnRebalance.setEnabled(false);
        btnRebalance.addActionListener(e -> rebalanceTargets());

        btnRefresh.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btnRefresh.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btnRefresh.setMargin(new Insets(4, 8, 4, 8));
        btnRefresh.addActionListener(e -> refreshAgentTable());

        JButton btnEnroll = new JButton("Thêm Agent", ThemeManager.getIcon("plus", 14));
        btnEnroll.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btnEnroll.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btnEnroll.setMargin(new Insets(4, 8, 4, 8));
        btnEnroll.addActionListener(e -> {
            Window win = SwingUtilities.getWindowAncestor(this);
            int port = (serverInstance != null && serverInstance.getPort() > 0) ? serverInstance.getPort() : 9876;
            new com.networkalert.ui.AgentEnrollmentDialog(
                    win,
                    port,
                    serverInstance != null ? serverInstance.getAgentManager() : null
            ).setVisible(true);
        });

        rowServerButtons.add(btnToggleServer);
        rowServerButtons.add(btnDemoAgents);
        rowServerButtons.add(btnEnroll);
        rowServerButtons.add(btnRebalance);
        rowServerButtons.add(btnRefresh);

        topCard.add(rowStatus);
        topCard.add(Box.createVerticalStrut(UIScale.scale(4)));
        topCard.add(rowServerButtons);

        add(topCard, BorderLayout.NORTH);

        // =====================================================================
        // 2. CENTER: Bảng danh sách Agent (Bố cục cuộn ngang linh hoạt)
        // =====================================================================
        String[] columns = {"#", "Agent ID", "IP Remote (Thật)", "Hostname", "Hệ điều hành", "Mục tiêu gán", "CPU", "RAM trống", "Nhịp tim"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        agentTable = new JTable(tableModel);
        agentTable.setFont(ThemeManager.sans(Font.PLAIN, 12));
        agentTable.setRowHeight(UIScale.scale(28));
        agentTable.setShowGrid(true);
        agentTable.setFillsViewportHeight(true);
        agentTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Căn chỉnh độ rộng các cột tối ưu
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);

        agentTable.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(35));  // #
        agentTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);

        agentTable.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(120)); // Agent ID
        agentTable.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(150)); // IP Remote (Thật)
        agentTable.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(95));  // Hostname
        agentTable.getColumnModel().getColumn(4).setPreferredWidth(UIScale.scale(95));  // OS

        agentTable.getColumnModel().getColumn(5).setPreferredWidth(UIScale.scale(85));  // Mục tiêu gán
        agentTable.getColumnModel().getColumn(5).setCellRenderer(centerRenderer);

        agentTable.getColumnModel().getColumn(6).setPreferredWidth(UIScale.scale(60));  // CPU
        agentTable.getColumnModel().getColumn(6).setCellRenderer(centerRenderer);

        agentTable.getColumnModel().getColumn(7).setPreferredWidth(UIScale.scale(80));  // RAM trống
        agentTable.getColumnModel().getColumn(7).setCellRenderer(centerRenderer);

        agentTable.getColumnModel().getColumn(8).setPreferredWidth(UIScale.scale(80));  // Nhịp tim
        agentTable.getColumnModel().getColumn(8).setCellRenderer(centerRenderer);

        JScrollPane scrollPane = new JScrollPane(agentTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        scrollPane.getViewport().setBackground(ThemeManager.bgSurface());
        add(scrollPane, BorderLayout.CENTER);

        // =====================================================================
        // 3. SOUTH: Khung sao chép cú pháp lệnh chạy trạm Agent trên máy khác
        // =====================================================================
        JPanel bottomCard = new JPanel();
        bottomCard.setLayout(new BoxLayout(bottomCard, BoxLayout.Y_AXIS));
        bottomCard.setOpaque(true);
        bottomCard.setBackground(ThemeManager.bgSurface());
        bottomCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.border(), 1),
                BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
        ));

        JLabel lblGuide = new JLabel("💡 Cú pháp khởi chạy trạm đo NCAS Agent trên máy/chi nhánh khác trong mạng:");
        lblGuide.setFont(ThemeManager.sans(Font.BOLD, 11));
        lblGuide.setForeground(ThemeManager.textSecondary());
        lblGuide.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel cmdRow = new JPanel(new BorderLayout(UIScale.scale(6), 0));
        cmdRow.setOpaque(false);
        cmdRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        txtCommandCopy.setFont(new Font("Consolas", Font.PLAIN, 11));
        txtCommandCopy.setEditable(false);
        updateCommandSnippet();

        btnCopyCommand.setFont(ThemeManager.sans(Font.BOLD, 11));
        btnCopyCommand.putClientProperty("FlatLaf.style", "arc: 6; focusWidth: 0");
        btnCopyCommand.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCopyCommand.addActionListener(e -> copyCommandToClipboard());

        cmdRow.add(txtCommandCopy, BorderLayout.CENTER);
        cmdRow.add(btnCopyCommand, BorderLayout.EAST);

        bottomCard.add(lblGuide);
        bottomCard.add(Box.createVerticalStrut(UIScale.scale(4)));
        bottomCard.add(cmdRow);

        add(bottomCard, BorderLayout.SOUTH);
    }

    private void updateCommandSnippet() {
        String localIp = "127.0.0.1";
        RealNetworkInfo net = RealNetworkService.getInstance().getNetworkInfo();
        if (net != null && net.getLocalIp() != null && !net.getLocalIp().isEmpty()) {
            localIp = net.getLocalIp();
        }
        int port = (serverInstance != null && serverInstance.isRunning()) ? serverInstance.getPort() : 9876;
        lblLanIpBadge.setText(String.format("🖧 LAN: %s:%d", localIp, port));
        txtCommandCopy.setText(String.format("java -jar NCAS.jar agent -s %s -p %d --id agent-branch-01 -t %s",
                localIp, port, DEFAULT_TOKEN));
    }

    private void copyCommandToClipboard() {
        String text = txtCommandCopy.getText();
        if (text != null && !text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            NotificationToast.show("Đã sao chép lệnh Agent", "Bạn có thể dán (Paste) vào Terminal máy khác để chạy trạm đo.", false);
        }
    }

    /**
     * Bật hoặc dừng máy chủ phân tán NCAS Server trực tiếp từ GUI
     */
    public synchronized void toggleServer() {
        if (serverInstance == null || !serverInstance.isRunning()) {
            try {
                serverInstance = new NcasServer(9876, "0.0.0.0", DEFAULT_TOKEN);

                // Nối kết quả đo từ Agent về MonitorService của GUI để cập nhật bảng & vẽ đồ thị
                serverInstance.addCheckResultListener((target, result) -> {
                    SwingUtilities.invokeLater(() -> {
                        if (monitorService != null) {
                            for (ServerTarget t : monitorService.getTargets()) {
                                if (t.getId() == target.getId()) {
                                    t.recordCheckResult(result, 300);
                                    t.setAssignedAgent(target.getAssignedAgent());
                                    break;
                                }
                            }
                        }
                    });
                });

                // Lắng nghe Agent gia nhập / rời đi để refresh UI tự động
                serverInstance.getAgentManager().addAgentChangeListener(() -> {
                    SwingUtilities.invokeLater(this::refreshAgentTable);
                    SwingUtilities.invokeLater(this::fireClusterStateChanged);
                });

                serverInstance.start();
                fireClusterStateChanged();

                lblServerStatus.setText("🟢 Server đang chạy trên cổng 9876");
                lblServerStatus.setForeground(ThemeManager.statusOnline());
                btnToggleServer.setText("⏹ Dừng Server");
                btnToggleServer.putClientProperty("FlatLaf.style", "background: #DC2626; foreground: #FFFFFF; arc: 6; focusWidth: 0");
                btnRebalance.setEnabled(true);
                updateCommandSnippet();

                NotificationToast.show("NCAS Server đã kích hoạt", "Máy chủ lắng nghe tại cổng 9876. Các trạm Agent có thể kết nối ngay!", false);
                LoggerUtil.info("[AgentClusterPanel] Đã khởi chạy thành công NCAS Server phân tán.");
            } catch (Exception e) {
                NotificationToast.show("Lỗi khởi động Server", e.getMessage(), true);
                LoggerUtil.error("[AgentClusterPanel] Lỗi khởi động Server: " + e.getMessage());
            }
        } else {
            // Nếu có Agent mẫu đang chạy thì dừng trước
            if (demoAgentsRunning) {
                stopDemoAgentsInternal();
            }

            // Dừng server
            serverInstance.stop();
            serverInstance = null;
            fireClusterStateChanged();

            lblServerStatus.setText("⚪ Chế độ: Standalone (Server chưa bật)");
            lblServerStatus.setForeground(null);
            lblAgentCountBadge.setText("0 Agent trực tuyến");
            btnToggleServer.setText("▶ Bật Server (9876)");
            btnToggleServer.putClientProperty("FlatLaf.style", "background: #2563EB; foreground: #FFFFFF; arc: 6; focusWidth: 0");
            btnRebalance.setEnabled(false);
            tableModel.setRowCount(0);
            updateCommandSnippet();

            // Khôi phục gán Agent về null cho các mục tiêu
            if (monitorService != null) {
                for (ServerTarget t : monitorService.getTargets()) {
                    t.setAssignedAgent(null);
                }
            }

            NotificationToast.show("NCAS Server đã dừng", "Đã ngắt toàn bộ phiên kết nối phân tán.", false);
        }
    }

    /**
     * Khởi động hoặc Dừng 2 Agent mẫu giả lập (Hà Nội & TP.HCM)
     * Giúp người dùng hoặc Giảng viên kiểm tra tính năng phân tán 1-Click ngay trên giao diện!
     */
    public synchronized void toggleDemoAgents() {
        if (!demoAgentsRunning) {
            // Tự động bật server nếu chưa chạy
            if (serverInstance == null || !serverInstance.isRunning()) {
                toggleServer();
            }

            // Chờ server lắng nghe
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}

            int port = (serverInstance != null && serverInstance.isRunning()) ? serverInstance.getPort() : 9876;

            // Khởi động Demo Agent 1: Hà Nội
            AgentConfig configHn = new AgentConfig();
            configHn.setAgentId("agent-hanoi-01");
            configHn.setServerHost("127.0.0.1");
            configHn.setServerPort(port);
            configHn.setAuthToken(DEFAULT_TOKEN);
            configHn.setHeartbeatIntervalSeconds(4);
            demoAgentHanoi = new NcasAgent(configHn);
            demoAgentHanoi.start();

            // Khởi động Demo Agent 2: TP.HCM
            AgentConfig configHcm = new AgentConfig();
            configHcm.setAgentId("agent-hcm-02");
            configHcm.setServerHost("127.0.0.1");
            configHcm.setServerPort(port);
            configHcm.setAuthToken(DEFAULT_TOKEN);
            configHcm.setHeartbeatIntervalSeconds(4);
            demoAgentHcm = new NcasAgent(configHcm);
            demoAgentHcm.start();

            demoAgentsRunning = true;
            btnDemoAgents.setText("⏹ Dừng 2 Agent mẫu");
            btnDemoAgents.putClientProperty("FlatLaf.style", "background: #D97706; foreground: #FFFFFF; arc: 6; focusWidth: 0");

            NotificationToast.show("Đã kích hoạt 2 Agent mẫu!",
                    "Trạm 'agent-hanoi-01' và 'agent-hcm-02' đang kết nối và nhận phân tải đo lường.", false);
        } else {
            stopDemoAgentsInternal();
            NotificationToast.show("Đã dừng Agent mẫu",
                    "Toàn bộ mục tiêu giám sát đã được hoàn trả về máy chủ trung tâm.", false);
        }
    }

    private synchronized void stopDemoAgentsInternal() {
        if (demoAgentHanoi != null) {
            demoAgentHanoi.stop();
            demoAgentHanoi = null;
        }
        if (demoAgentHcm != null) {
            demoAgentHcm.stop();
            demoAgentHcm = null;
        }
        demoAgentsRunning = false;
        btnDemoAgents.setText("🧪 Chạy 2 Agent mẫu");
        btnDemoAgents.putClientProperty("FlatLaf.style", "background: #059669; foreground: #FFFFFF; arc: 6; focusWidth: 0");

        if (serverInstance != null && serverInstance.isRunning()) {
            serverInstance.getTargetDistributor().rebalance();
            refreshAgentTable();
        }
    }

    /**
     * Làm mới bảng danh sách Agent
     */
    public synchronized void refreshAgentTable() {
        tableModel.setRowCount(0);
        if (serverInstance == null || !serverInstance.isRunning()) {
            lblAgentCountBadge.setText("0 Agent trực tuyến");
            return;
        }

        List<AgentSession> sessions = new ArrayList<>(serverInstance.getAgentManager().getActiveSessions());
        lblAgentCountBadge.setText(sessions.size() + " Agent trực tuyến");

        int index = 1;
        for (AgentSession a : sessions) {
            long lastSec = (java.time.Clock.systemUTC().millis() - a.getLastHeartbeatTime()) / 1000;
            String pingStr = (lastSec <= 2) ? "🟢 Vừa xong" : (lastSec + "s trước");

            // Hiển thị IP remote thật từ Socket TCP kết nối đến Server
            String remoteIpWithPort = a.getRemoteIp() + (a.getRemotePort() > 0 ? (":" + a.getRemotePort()) : "");
            String displayIp = remoteIpWithPort;
            if (a.getLocalIp() != null && !a.getLocalIp().trim().isEmpty() && !a.getLocalIp().equals(a.getRemoteIp())) {
                displayIp = remoteIpWithPort + " (LAN: " + a.getLocalIp() + ")";
            }

            tableModel.addRow(new Object[]{
                    index++,
                    a.getAgentId(),
                    displayIp,
                    a.getHostname(),
                    a.getOs(),
                    a.getAssignedTargetIds().size() + " mục tiêu",
                    String.format("%.1f%%", a.getLastCpuUsage()),
                    a.getLastFreeMemoryMb() + " MB",
                    pingStr
            });
        }
    }

    /**
     * Tái phân bổ mục tiêu tới toàn bộ Agent
     */
    private void rebalanceTargets() {
        if (serverInstance != null && serverInstance.isRunning()) {
            serverInstance.getTargetDistributor().rebalance();
            refreshAgentTable();
            NotificationToast.show("Tái cân bằng hoàn tất", "Toàn bộ mục tiêu giám sát đã được phân bổ lại cho các Agent.", false);
        }
    }

    public NcasServer getServerInstance() {
        return serverInstance;
    }

    public boolean isServerRunning() {
        return serverInstance != null && serverInstance.isRunning();
    }

    public JTable getAgentTable() {
        return agentTable;
    }

    public DefaultTableModel getTableModel() {
        return tableModel;
    }

    public JLabel getLblServerStatus() {
        return lblServerStatus;
    }

    public JLabel getLblAgentCountBadge() {
        return lblAgentCountBadge;
    }

    public JButton getBtnToggleServer() {
        return btnToggleServer;
    }

    public JButton getBtnDemoAgents() {
        return btnDemoAgents;
    }
}

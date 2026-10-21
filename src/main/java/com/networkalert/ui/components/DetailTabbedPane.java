package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.ServerTarget;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.service.MonitorService;
import com.networkalert.simulator.MockHttpServer;
import com.networkalert.simulator.MockTcpServer;
import com.networkalert.simulator.MockUdpServer;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Container panel tab bên phải (JTabbedPane underlined, tab cao 40 px)
 * Bao gồm: Biểu đồ độ trễ, Sự cố (có badge số lượng), Giả lập mạng, Quét cổng
 */
public class DetailTabbedPane extends JPanel {
    private final JTabbedPane tabbedPane;
    private final LatencyChartPanel latencyChartPanel;
    private final ProblemsViewPanel problemsViewPanel;
    private final IncidentHistoryPanel incidentHistoryPanel;
    private final NetworkSimulatorPanel networkSimulatorPanel;
    private final PortScannerPanel portScannerPanel;
    private final AgentClusterPanel agentClusterPanel;
    private final TopologyMapPanel topologyMapPanel;

    private final JLabel lblIncidentTabTitle = new JLabel(I18n.get("tabs.problems", "Sự cố"));
    private final JLabel lblIncidentBadge = new JLabel() {
        @Override
        protected void paintComponent(Graphics g) {
            if (isVisible() && getText() != null && !getText().trim().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 1, getWidth(), getHeight() - 2, UIScale.scale(8), UIScale.scale(8));
                g2.dispose();
            }
            super.paintComponent(g);
        }
    };

    public DetailTabbedPane(IncidentRepository incidentRepo,
                            MockTcpServer tcp, MockUdpServer udp, MockHttpServer http,
                            MonitorService monitorService, TargetRepository targetRepo,
                            Consumer<ServerTarget> onTargetAddedCallback,
                            BiConsumer<String, String> toastNotifier) {
        setLayout(new BorderLayout());
        setOpaque(false);

        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(ThemeManager.sans(Font.BOLD, 12));
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.putClientProperty("JTabbedPane.tabHeight", UIScale.scale(36));
        tabbedPane.putClientProperty("JTabbedPane.tabInsets", new Insets(UIScale.scale(4), UIScale.scale(6), UIScale.scale(4), UIScale.scale(6)));

        // Tab 0: Biểu đồ độ trễ (Real-time Latency Chart - luôn ở vị trí đầu tiên)
        latencyChartPanel = new LatencyChartPanel();
        tabbedPane.addTab(I18n.get("tabs.chart", "Biểu đồ"), ThemeManager.getIcon("activity", 14), latencyChartPanel);

        // Tab 1: Sơ đồ mạng phân tầng (Hierarchical Topology Map)
        topologyMapPanel = new TopologyMapPanel(monitorService);
        tabbedPane.addTab(I18n.get("tabs.topology", "Sơ đồ"), ThemeManager.getIcon("git-branch", 14), topologyMapPanel);

        // Tab 2: Sự cố kiểu Nagios/Zabbix (Problems View) thay thế IncidentHistory
        problemsViewPanel = new ProblemsViewPanel(com.networkalert.repository.ProblemRepository.getInstance());
        incidentHistoryPanel = new IncidentHistoryPanel(incidentRepo);
        tabbedPane.addTab(I18n.get("tabs.problems", "Sự cố"), ThemeManager.getIcon("alert-triangle", 14), problemsViewPanel);
        setupIncidentTabHeader();

        // Tab 3: Giả lập mạng
        networkSimulatorPanel = new NetworkSimulatorPanel(tcp, udp, http, toastNotifier);
        tabbedPane.addTab(I18n.get("tabs.simulator", "Giả lập"), ThemeManager.getIcon("play", 14), networkSimulatorPanel);

        // Tab 4: Quét cổng
        portScannerPanel = new PortScannerPanel(monitorService, targetRepo, onTargetAddedCallback);
        tabbedPane.addTab(I18n.get("tabs.port_scanner", "Quét cổng"), ThemeManager.getIcon("search", 14), portScannerPanel);

        // Tab 5: Trạm Agent phân tán
        agentClusterPanel = new AgentClusterPanel(monitorService);
        tabbedPane.addTab(I18n.get("tabs.agent_cluster", "Trạm Agent"), ThemeManager.getIcon("radio", 14), agentClusterPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    public void selectAgentTab() {
        int idx = tabbedPane.indexOfComponent(agentClusterPanel);
        if (idx >= 0) {
            tabbedPane.setSelectedIndex(idx);
        }
    }

    public AgentClusterPanel getAgentClusterPanel() {
        return agentClusterPanel;
    }

    public ProblemsViewPanel getProblemsViewPanel() {
        return problemsViewPanel;
    }

    public IncidentHistoryPanel getIncidentHistoryPanel() {
        return incidentHistoryPanel;
    }

    public TopologyMapPanel getTopologyMapPanel() {
        return topologyMapPanel;
    }

    public DetailTabbedPane(IncidentRepository incidentRepo,
                            MockTcpServer tcp, MockUdpServer udp, MockHttpServer http,
                            BiConsumer<String, String> toastNotifier) {
        this(incidentRepo, tcp, udp, http, null, null, null, toastNotifier);
    }

    private void setupIncidentTabHeader() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.CENTER, UIScale.scale(5), 0));
        header.setOpaque(false);

        lblIncidentTabTitle.setFont(ThemeManager.sans(Font.BOLD, 12));
        lblIncidentTabTitle.setIcon(ThemeManager.getIcon("alert-triangle", 14));
        lblIncidentTabTitle.setIconTextGap(UIScale.scale(4));
        lblIncidentBadge.setFont(ThemeManager.sans(Font.BOLD, 10));
        lblIncidentBadge.setForeground(Color.WHITE);
        lblIncidentBadge.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        lblIncidentBadge.setOpaque(false);
        lblIncidentBadge.setVisible(false);

        header.add(lblIncidentTabTitle);
        header.add(lblIncidentBadge);

        int idx = tabbedPane.indexOfComponent(problemsViewPanel);
        if (idx >= 0) {
            tabbedPane.setTabComponentAt(idx, header);
        }
    }

    public void updateActiveIncidentBadge(int activeCount, boolean hasOffline) {
        lblIncidentTabTitle.setText(I18n.get("tabs.problems", "Sự cố"));
        if (activeCount <= 0) {
            lblIncidentBadge.setVisible(false);
        } else {
            lblIncidentBadge.setText(String.valueOf(activeCount));
            Color bg = hasOffline ? ThemeManager.statusOffline() : ThemeManager.statusWarning();
            lblIncidentBadge.setBackground(bg);
            lblIncidentBadge.setVisible(true);
        }
        tabbedPane.repaint();
    }

    public void updateTarget(ServerTarget target, long threshold) {
        latencyChartPanel.updateTarget(target, threshold);
    }

    public void setAllTargets(List<ServerTarget> targets) {
        latencyChartPanel.setAllTargets(targets);
    }

    public void refreshIncidents() {
        problemsViewPanel.refreshData();
        incidentHistoryPanel.refreshData();
        int active = problemsViewPanel.getActiveProblemsCount();
        boolean hasOffline = problemsViewPanel.hasDisasterOrHighProblems();
        updateActiveIncidentBadge(active, hasOffline);
        if (topologyMapPanel != null) {
            topologyMapPanel.refresh();
        }
    }

    public void selectTab(int index) {
        if (index >= 0 && index < tabbedPane.getTabCount()) {
            tabbedPane.setSelectedIndex(index);
        }
    }
}

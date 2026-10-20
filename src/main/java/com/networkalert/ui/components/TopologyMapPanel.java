package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetScope;
import com.networkalert.server.AgentManager;
import com.networkalert.server.AgentSession;
import com.networkalert.service.MonitorService;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Bản đồ topo mạng phân tầng (Hierarchical Topology Map: Server -> Agent -> Target)
 * Tự động vẽ bằng Java2D thuần (không phụ thuộc thư viện đồ thị ngoài):
 * - Tự động thích ứng 2 tầng (Bộ giám sát -> Targets) ở chế độ Standalone
 * - Tự động chuyển sang 3 tầng (Server -> Agents -> Targets) khi có Agent kết nối
 * - Node UNREACHABLE tự động mờ đi (AlphaComposite 0.40f)
 * - Màu sắc đồng bộ theo ServerStatus (ONLINE: xanh lá, WARNING: vàng, OFFLINE: đỏ, MAINTENANCE: xanh dương)
 */
public class TopologyMapPanel extends JPanel {
    private final MonitorService monitorService;
    private final TopologyCanvas canvas;
    private final JLabel lblTitle;
    private final JLabel lblFooterSummary;

    public TopologyMapPanel(MonitorService monitorService) {
        this.monitorService = monitorService;
        this.canvas = new TopologyCanvas();

        setLayout(new BorderLayout(0, UIScale.scale(8)));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10)));

        // Toolbar
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setOpaque(false);

        lblTitle = new JLabel("Sơ đồ kết nối • Độc lập");
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 13));
        lblTitle.setIcon(ThemeManager.getIcon("git-branch", 15));

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        rightControls.setOpaque(false);

        JButton btnRefresh = new JButton("Làm mới sơ đồ", ThemeManager.getIcon("refresh-cw", 13));
        btnRefresh.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnRefresh.addActionListener(e -> refresh());

        rightControls.add(btnRefresh);

        toolbar.add(lblTitle, BorderLayout.WEST);
        toolbar.add(rightControls, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        scrollPane.getViewport().setBackground(ThemeManager.bgSurface());
        add(scrollPane, BorderLayout.CENTER);

        // Footer
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), 0, 0, 0));

        lblFooterSummary = new JLabel("7 / 7 mục tiêu trực tuyến • không có cảnh báo");
        lblFooterSummary.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblFooterSummary.setForeground(ThemeManager.textMuted());

        JLabel lblZoom = new JLabel("—  Vào khung  +  ⛶");
        lblZoom.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblZoom.setForeground(ThemeManager.textMuted());

        footer.add(lblFooterSummary, BorderLayout.WEST);
        footer.add(lblZoom, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        refresh();
    }

    public void refresh() {
        if (monitorService != null) {
            List<ServerTarget> targets = monitorService.getTargets();
            int total = targets.size();
            int offline = 0;
            int warning = 0;
            int online = 0;
            int paused = 0;
            for (ServerTarget t : targets) {
                if (!t.isEnabled() || t.getStatus() == ServerStatus.PAUSED) {
                    paused++;
                } else if (t.getStatus() == ServerStatus.OFFLINE) {
                    offline++;
                } else if (t.getStatus() == ServerStatus.WARNING) {
                    warning++;
                } else if (t.getStatus() == ServerStatus.ONLINE) {
                    online++;
                }
            }
            int errors = offline + warning;
            if (errors == 0) {
                lblTitle.setText("Sơ đồ kết nối • Độc lập");
            } else {
                lblTitle.setText(String.format("Sơ đồ kết nối: %d lỗi", errors));
            }
            if (paused == total && total > 0) {
                lblFooterSummary.setText(String.format("Tạm dừng giám sát • %d máy chủ", total));
            } else if (errors == 0 && online > 0) {
                lblFooterSummary.setText(String.format("%d / %d mục tiêu trực tuyến • không có cảnh báo", online, total));
            } else if (errors == 0 && total > 0) {
                lblFooterSummary.setText(String.format("%d / %d mục tiêu trực tuyến • không có cảnh báo", total, total));
            } else {
                lblFooterSummary.setText(String.format("%d / %d trực tuyến (%d cảnh báo, %d ngoại tuyến)", online, total, warning, offline));
            }
        }
        if (canvas != null) {
            canvas.repaint();
        }
    }

    // Node biểu diễn trên bản đồ
    private static class GraphNode {
        final String id;
        final String label;
        final String subLabel;
        final int layer; // 0: Server, 1: Agent, 2: Target
        final ServerStatus status;
        final boolean unreachable;
        final boolean flapping;
        final Object data;
        int x, y, width, height;

        GraphNode(String id, String label, String subLabel, int layer, ServerStatus status, boolean unreachable, boolean flapping, Object data) {
            this.id = id;
            this.label = label;
            this.subLabel = subLabel;
            this.layer = layer;
            this.status = status;
            this.unreachable = unreachable;
            this.flapping = flapping;
            this.data = data;
        }

        boolean contains(int px, int py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    private class TopologyCanvas extends JPanel {
        private final List<GraphNode> nodes = new ArrayList<>();
        private GraphNode hoveredNode = null;

        TopologyCanvas() {
            setOpaque(true);
            setBackground(ThemeManager.bgSurface());
            setToolTipText("");

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    GraphNode hit = null;
                    for (GraphNode node : nodes) {
                        if (node.contains(e.getX(), e.getY())) {
                            hit = node;
                            break;
                        }
                    }
                    if (hit != hoveredNode) {
                        hoveredNode = hit;
                        if (hoveredNode != null) {
                            setToolTipText(generateTooltip(hoveredNode));
                        } else {
                            setToolTipText(null);
                        }
                        repaint();
                    }
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        @Override
        public Dimension getPreferredSize() {
            int targetCount = (monitorService != null) ? monitorService.getTargets().size() : 7;
            int h = Math.max(UIScale.scale(180), UIScale.scale(12) + targetCount * UIScale.scale(23) + UIScale.scale(12));
            int w = UIScale.scale(460);
            return new Dimension(w, h);
        }

        private String generateTooltip(GraphNode n) {
            if (n.data instanceof ServerTarget) {
                ServerTarget t = (ServerTarget) n.data;
                return String.format("<html><b>%s</b> (%s:%d)<br>Giao thức: %s<br>Trạng thái: %s<br>Độ trễ: %s<br>Trạm đo: %s</html>",
                        t.getName(), t.getHost(), t.getPort(), t.getProtocol(), t.getStatus(),
                        (t.getCurrentLatency() >= 0 ? t.getCurrentLatency() + " ms" : "N/A"),
                        t.getQuorumSummary());
            } else if (n.data instanceof AgentSession) {
                AgentSession s = (AgentSession) n.data;
                return String.format("<html><b>Trạm đo: %s</b><br>IP Remote: %s<br>Hệ điều hành: %s<br>CPU: %.1f%% | RAM trống: %d MB</html>",
                        s.getAgentId(), s.getRemoteAddress(), s.getOs(), s.getLastCpuUsage(), s.getLastFreeMemoryMb());
            } else {
                return "<html><b>Bộ giám sát trung tâm</b><br>Quản lý và điều phối các mục tiêu kiểm tra</html>";
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            nodes.clear();

            List<ServerTarget> targetList = (monitorService != null) ? monitorService.getTargets() : new ArrayList<>();
            AgentManager am = AgentManager.getInstance();
            Collection<AgentSession> agents = (am != null) ? am.getConnectedSessions() : java.util.Collections.emptyList();
            boolean isStandalone = agents.isEmpty();

            int targetCount = Math.max(1, targetList.size());
            int minNeededH = UIScale.scale(16) + targetCount * UIScale.scale(23) + UIScale.scale(16);
            int panelW = Math.max(getWidth(), UIScale.scale(440));
            int panelH = Math.max(getHeight(), minNeededH);
            int defaultNodeH = Math.min(UIScale.scale(32), Math.max(UIScale.scale(24), (panelH / targetCount) - UIScale.scale(4)));

            if (isStandalone) {
                // CHẾ ĐỘ STANDALONE: CÂY KẾT NỐI CHUẨN DOANH NGHIỆP (Theo mẫu Ảnh 2)
                int col1X = UIScale.scale(20);
                int col1W = UIScale.scale(130);
                int col1H = UIScale.scale(46);
                int padY = UIScale.scale(8);
                int usableH = panelH - (padY * 2);

                int interval = (monitorService != null && !monitorService.getTargets().isEmpty()) ? monitorService.getTargets().get(0).getIntervalSeconds() : 3;
                GraphNode monitorNode = new GraphNode("MONITOR", "Bộ giám sát", "Trực tiếp • chu kỳ " + interval + "s", 0, ServerStatus.ONLINE, false, false, "Monitor");
                monitorNode.width = col1W;
                monitorNode.height = col1H;
                monitorNode.x = col1X;
                monitorNode.y = Math.max(padY, (panelH / 2) - (col1H / 2));
                nodes.add(monitorNode);

                // Khối danh sách cây mục tiêu (Unified Target Tree Card)
                int cardX = col1X + col1W + UIScale.scale(50);
                int cardW = Math.max(UIScale.scale(260), panelW - cardX - UIScale.scale(16));
                int rowH = Math.min(UIScale.scale(24), Math.max(UIScale.scale(21), usableH / targetCount));
                int cardH = targetCount * rowH;
                int cardY = Math.max(padY, (panelH / 2) - (cardH / 2));

                // 1. Vẽ đường trục nối cây hình chữ T/Bracket (Orthogonal Spine & Branches)
                int monitorMidX = col1X + col1W;
                int monitorMidY = monitorNode.y + col1H / 2;
                int busX = monitorMidX + (cardX - monitorMidX) / 2;
                int firstRowMidY = cardY + rowH / 2;
                int lastRowMidY = cardY + (targetCount - 1) * rowH + rowH / 2;

                g2.setColor(ThemeManager.statusOnline());
                g2.setStroke(new BasicStroke(1.5f));
                // Nhánh ngang từ Bộ giám sát tới trục bus
                g2.drawLine(monitorMidX, monitorMidY, busX, monitorMidY);
                // Trục dọc xương sống (Spine)
                g2.drawLine(busX, firstRowMidY, busX, lastRowMidY);
                // Các nhánh ngang nối vào từng dòng máy chủ
                for (int i = 0; i < targetCount; i++) {
                    int rowMidY = cardY + i * rowH + rowH / 2;
                    g2.drawLine(busX, rowMidY, cardX, rowMidY);
                }

                // 2. Vẽ khung bao bọc hợp nhất danh sách máy chủ
                g2.setColor(ThemeManager.bgSurface());
                g2.fill(new RoundRectangle2D.Float(cardX, cardY, cardW, cardH, UIScale.scale(8), UIScale.scale(8)));
                g2.setColor(ThemeManager.border());
                g2.draw(new RoundRectangle2D.Float(cardX, cardY, cardW, cardH, UIScale.scale(8), UIScale.scale(8)));

                // 3. Vẽ từng dòng máy chủ bên trong khung
                for (int i = 0; i < targetList.size(); i++) {
                    ServerTarget t = targetList.get(i);
                    boolean isUnreach = (t.getStatus() == ServerStatus.UNREACHABLE);
                    boolean isFlap = t.isFlapping();
                    int rowY = cardY + i * rowH;

                    GraphNode tn = new GraphNode("T_" + t.getId(), t.getName(),
                            t.getProtocol().name() + "   " + (t.getCurrentLatency() >= 0 ? t.getCurrentLatency() + " ms" : "—"),
                            1, t.getStatus(), isUnreach, isFlap, t);
                    tn.x = cardX;
                    tn.y = rowY;
                    tn.width = cardW;
                    tn.height = rowH;
                    nodes.add(tn);

                    // Hiệu ứng hover dòng
                    if (tn == hoveredNode) {
                        g2.setColor(ThemeManager.isDarkMode() ? new Color(0x33, 0x41, 0x55, 120) : new Color(0xF1, 0xF5, 0xF9));
                        g2.fillRect(cardX + 1, rowY + 1, cardW - 2, rowH - 1);
                    }

                    // Vạch phân cách mảnh giữa các dòng
                    if (i > 0) {
                        g2.setColor(ThemeManager.border());
                        g2.drawLine(cardX + UIScale.scale(8), rowY, cardX + cardW - UIScale.scale(8), rowY);
                    }

                    // Chấm tròn trạng thái
                    Color dotColor = getColorForStatus(t.getStatus());
                    g2.setColor(dotColor);
                    int dotSize = UIScale.scale(6);
                    g2.fillOval(cardX + UIScale.scale(10), rowY + (rowH - dotSize) / 2, dotSize, dotSize);

                    // Tên máy chủ
                    g2.setColor(ThemeManager.textPrimary());
                    g2.setFont(ThemeManager.sans(Font.PLAIN, 11));
                    String name = t.getName();
                    if (name.length() > 20) name = name.substring(0, 18) + "...";
                    g2.drawString(name, cardX + UIScale.scale(22), rowY + (rowH / 2) + UIScale.scale(4));

                    // Huy hiệu Giao thức
                    g2.setFont(ThemeManager.mono(Font.PLAIN, 10));
                    g2.setColor(ThemeManager.textSecondary());
                    int protoX = cardX + cardW - UIScale.scale(92);
                    g2.drawString(t.getProtocol().name(), protoX, rowY + (rowH / 2) + UIScale.scale(4));

                    // Độ trễ
                    String latStr = t.getCurrentLatency() >= 0 ? (t.getCurrentLatency() + " ms") : "—";
                    g2.setColor(t.getStatus() == ServerStatus.ONLINE ? ThemeManager.statusOnline() : ThemeManager.textSecondary());
                    g2.drawString(latStr, cardX + cardW - UIScale.scale(48), rowY + (rowH / 2) + UIScale.scale(4));
                }

                // Vẽ riêng Node Bộ giám sát
                drawNode(g2, monitorNode, monitorNode == hoveredNode);
            } else {
                // CHẾ ĐỘ CỤM PHÂN TÁN (3 TẦNG: Server -> Agent -> Target)
                int col1X = UIScale.scale(20);
                int col2X = UIScale.scale(190);
                int col3X = Math.max(UIScale.scale(370), panelW - UIScale.scale(200));

                int col1W = UIScale.scale(140);
                int col2W = UIScale.scale(150);
                int col3W = panelW - col3X - UIScale.scale(15);

                GraphNode serverNode = new GraphNode("SERVER", "NCAS Server", "Trung tâm điều phối", 0, ServerStatus.ONLINE, false, false, "Server");
                serverNode.width = col1W;
                serverNode.height = UIScale.scale(48);
                serverNode.x = col1X;
                serverNode.y = Math.max(UIScale.scale(40), panelH / 2 - serverNode.height / 2);
                nodes.add(serverNode);

                List<GraphNode> agentNodes = new ArrayList<>();
                List<AgentSession> agentList = new ArrayList<>(agents);
                int agentStartY = UIScale.scale(30);
                int agentSpacing = Math.max(UIScale.scale(60), (panelH - agentStartY * 2) / Math.max(1, agentList.size()));

                for (int i = 0; i < agentList.size(); i++) {
                    AgentSession s = agentList.get(i);
                    ServerStatus st = s.isAuthenticated() ? ServerStatus.ONLINE : ServerStatus.OFFLINE;
                    GraphNode an = new GraphNode(s.getAgentId(), s.getAgentId(), s.getRemoteAddress(), 1, st, false, false, s);
                    an.width = col2W;
                    an.height = defaultNodeH;
                    an.x = col2X;
                    an.y = agentStartY + (i * agentSpacing);
                    agentNodes.add(an);
                    nodes.add(an);
                }

                List<GraphNode> targetNodes = new ArrayList<>();
                int targetStartY = UIScale.scale(20);
                int targetSpacing = Math.max(UIScale.scale(48), (panelH - targetStartY * 2) / Math.max(1, targetList.size()));

                for (int i = 0; i < targetList.size(); i++) {
                    ServerTarget t = targetList.get(i);
                    boolean isUnreach = (t.getStatus() == ServerStatus.UNREACHABLE);
                    boolean isFlap = t.isFlapping();
                    String latStr = t.getCurrentLatency() >= 0 ? (t.getCurrentLatency() + " ms") : "—";
                    String sub = t.getProtocol().name() + "   " + latStr;
                    GraphNode tn = new GraphNode("T_" + t.getId(), t.getName(), sub, 2, t.getStatus(), isUnreach, isFlap, t);
                    tn.width = col3W;
                    tn.height = defaultNodeH;
                    tn.x = col3X;
                    tn.y = targetStartY + (i * targetSpacing);
                    targetNodes.add(tn);
                    nodes.add(tn);
                }

                // Đường nối Server -> Agent
                for (GraphNode an : agentNodes) {
                    drawLink(g2, serverNode, an, "TCP", ThemeManager.statusOnline());
                }

                // Đường nối Agent/Server -> Target
                for (GraphNode tn : targetNodes) {
                    ServerTarget t = (ServerTarget) tn.data;
                    Color linkColor = getColorForStatus(t.getStatus());

                    if (t.getScope() == TargetScope.SERVER || "Server".equalsIgnoreCase(t.getAssignedAgent()) || "LOCAL".equalsIgnoreCase(t.getAssignedAgent())) {
                        drawLink(g2, serverNode, tn, "", linkColor);
                    } else if (t.getScope() == TargetScope.AGENT) {
                        GraphNode matchedAgent = findAgentNode(agentNodes, t.getAssignedAgent());
                        if (matchedAgent != null) {
                            drawLink(g2, matchedAgent, tn, "", linkColor);
                        } else {
                            drawLink(g2, serverNode, tn, "", ThemeManager.textMuted());
                        }
                    } else {
                        drawLink(g2, serverNode, tn, "Quorum", linkColor);
                        for (GraphNode an : agentNodes) {
                            drawLink(g2, an, tn, "", linkColor);
                        }
                    }
                }
            }

            // Vẽ toàn bộ các Node (khi ở chế độ phân tán 3 tầng)
            if (!isStandalone) {
                for (GraphNode node : nodes) {
                    drawNode(g2, node, node == hoveredNode);
                }
            }

            g2.dispose();
        }

        private GraphNode findAgentNode(List<GraphNode> list, String agentId) {
            if (agentId == null) return null;
            for (GraphNode n : list) {
                if (agentId.equalsIgnoreCase(n.id)) return n;
            }
            return null;
        }

        private void drawLink(Graphics2D g2, GraphNode from, GraphNode to, String label, Color color) {
            int x1 = from.x + from.width;
            int y1 = from.y + from.height / 2;
            int x2 = to.x;
            int y2 = to.y + to.height / 2;

            g2.setColor(color != null ? color : ThemeManager.border());
            g2.setStroke(new BasicStroke(1.5f));

            int ctrlX1 = x1 + (x2 - x1) / 2;
            int ctrlY1 = y1;
            int ctrlX2 = x1 + (x2 - x1) / 2;
            int ctrlY2 = y2;

            java.awt.geom.CubicCurve2D curve = new java.awt.geom.CubicCurve2D.Float(x1, y1, ctrlX1, ctrlY1, ctrlX2, ctrlY2, x2, y2);
            g2.draw(curve);

            if (label != null && !label.isEmpty()) {
                g2.setFont(ThemeManager.mono(Font.PLAIN, 10));
                g2.setColor(ThemeManager.textSecondary());
                int mx = (x1 + x2) / 2;
                int my = (y1 + y2) / 2 - UIScale.scale(4);
                g2.drawString(label, mx, my);
            }
        }

        private void drawNode(Graphics2D g2, GraphNode node, boolean isHovered) {
            Composite origComp = g2.getComposite();

            if (node.unreachable) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.40f));
            }

            Color statusColor = getColorForStatus(node.status);
            Color boxBg = ThemeManager.isDarkMode() ? new Color(0x1E, 0x29, 0x3B) : new Color(0xF8, 0xFA, 0xFC);

            g2.setColor(boxBg);
            g2.fill(new RoundRectangle2D.Float(node.x, node.y, node.width, node.height, UIScale.scale(8), UIScale.scale(8)));

            g2.setColor(isHovered ? statusColor : ThemeManager.border());
            g2.setStroke(new BasicStroke(isHovered ? 2.0f : 1.0f));
            g2.draw(new RoundRectangle2D.Float(node.x, node.y, node.width, node.height, UIScale.scale(8), UIScale.scale(8)));

            // Dải màu trạng thái bên trái
            g2.setColor(statusColor);
            g2.fill(new RoundRectangle2D.Float(node.x + UIScale.scale(3), node.y + UIScale.scale(3), UIScale.scale(4), node.height - UIScale.scale(6), UIScale.scale(3), UIScale.scale(3)));

            // Tiêu đề Node
            g2.setColor(ThemeManager.textPrimary());
            g2.setFont(ThemeManager.sans(Font.BOLD, node.height < UIScale.scale(32) ? 11 : 12));
            String title = node.label;
            if (title.length() > 22) title = title.substring(0, 20) + "...";
            int titleY = node.y + (node.height < UIScale.scale(32) ? UIScale.scale(14) : UIScale.scale(16));
            g2.drawString(title, node.x + UIScale.scale(12), titleY);

            if (node.flapping) {
                g2.setColor(ThemeManager.statusWarning());
                g2.drawString("⚡", node.x + node.width - UIScale.scale(18), titleY);
            }

            // SubLabel (Giao thức & độ trễ hoặc IP)
            g2.setColor(ThemeManager.textSecondary());
            g2.setFont(ThemeManager.mono(Font.PLAIN, node.height < UIScale.scale(32) ? 9 : 10));
            String sub = node.subLabel != null ? node.subLabel : "";
            if (sub.length() > 26) sub = sub.substring(0, 24) + "...";
            int subY = node.y + node.height - UIScale.scale(6);
            g2.drawString(sub, node.x + UIScale.scale(12), subY);

            g2.setComposite(origComp);
        }

        private Color getColorForStatus(ServerStatus st) {
            if (st == null) return ThemeManager.textMuted();
            switch (st) {
                case ONLINE:
                    return ThemeManager.statusOnline();
                case WARNING:
                    return ThemeManager.statusWarning();
                case OFFLINE:
                    return ThemeManager.statusOffline();
                case MAINTENANCE:
                    return ThemeManager.statusMaintenance();
                case UNREACHABLE:
                    return new Color(0x94, 0xA3, 0xB8);
                default:
                    return ThemeManager.textMuted();
            }
        }
    }
}

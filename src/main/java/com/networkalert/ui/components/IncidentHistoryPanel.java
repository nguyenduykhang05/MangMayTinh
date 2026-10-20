package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.IncidentLog;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tab Lịch sử sự cố mạng hiện đại theo UI_REDESIGN.md
 */
public class IncidentHistoryPanel extends JPanel {
    private final IncidentRepository incidentRepository;
    private final IncidentTableModel tableModel;
    private final JTable table;
    private final JLabel lblSummary = new JLabel();

    private final ButtonGroup filterGroup = new ButtonGroup();
    private final JToggleButton btnAll = new JToggleButton(I18n.get("incidents.all"));
    private final JToggleButton btnActive = new JToggleButton(I18n.get("incidents.active"));
    private final JToggleButton btnRecovered = new JToggleButton(I18n.get("incidents.recovered"));
    private final JButton btnRefresh = new JButton();
    private final JButton btnClearAll = new JButton();

    private final JPanel emptyPanel = new JPanel(new GridBagLayout());
    private final List<IncidentLog> rawLogs = new ArrayList<>();
    private final TableColumn[] allColumns = new TableColumn[6];

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FULL_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    public IncidentHistoryPanel(IncidentRepository repo) {
        this.incidentRepository = repo;

        setLayout(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // 1. THANH TIÊU ĐỀ & ĐIỀU KHIỂN
        JPanel headerBar = createHeaderBar();
        add(headerBar, BorderLayout.NORTH);

        // 2. BẢNG SỰ CỐ
        tableModel = new IncidentTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(UIScale.scale(36));
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(ThemeManager.border());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getTableHeader().setPreferredSize(new Dimension(100, UIScale.scale(32)));
        table.getTableHeader().setFont(ThemeManager.sans(Font.BOLD, 12));
        table.getTableHeader().setForeground(ThemeManager.textSecondary());

        // Renderer
        IncidentCellRenderer cellRenderer = new IncidentCellRenderer();
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(cellRenderer);
        }

        // Lưu trữ danh sách cột gốc để phục vụ ẩn/hiện linh hoạt
        for (int i = 0; i < 6; i++) {
            allColumns[i] = table.getColumnModel().getColumn(i);
        }

        setColumnWidths();
        setupResponsive();

        // Menu chuột phải cho bảng sự cố
        table.setComponentPopupMenu(createIncidentContextMenu());

        // Nhấp đúp mở chi tiết
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        IncidentLog log = tableModel.getLogAt(row);
                        if (log != null) showIncidentDetails(log);
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        scrollPane.getViewport().setBackground(ThemeManager.bgSurface());

        setupEmptyPanel();

        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setLayout(new OverlayLayout(layeredPane));
        layeredPane.add(emptyPanel, Integer.valueOf(1));
        layeredPane.add(scrollPane, Integer.valueOf(0));

        add(layeredPane, BorderLayout.CENTER);

        refreshData();
    }

    private JPanel createHeaderBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setOpaque(false);

        // Hàng 1: Tiêu đề + Thống kê 24h bên trái, Nút làm mới + Nút xóa bên phải
        JPanel topRow = new JPanel(new BorderLayout(8, 0));
        topRow.setOpaque(false);

        JPanel leftGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftGroup.setOpaque(false);

        JLabel lblTitle = new JLabel(I18n.get("incidents.title"));
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 14));
        lblTitle.setForeground(ThemeManager.textPrimary());

        lblSummary.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblSummary.setForeground(ThemeManager.textSecondary());

        leftGroup.add(lblTitle);
        leftGroup.add(lblSummary);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightActions.setOpaque(false);

        btnRefresh.setIcon(ThemeManager.getIcon("refresh-cw", 15));
        btnRefresh.putClientProperty("JButton.buttonType", "toolBarButton");
        btnRefresh.setToolTipText(I18n.get("incidents.refresh"));
        btnRefresh.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnRefresh.addActionListener(e -> refreshData());

        btnClearAll.setIcon(ThemeManager.getIcon("trash-2", 15));
        btnClearAll.putClientProperty("JButton.buttonType", "toolBarButton");
        btnClearAll.setToolTipText("Xóa toàn bộ lịch sử sự cố");
        btnClearAll.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnClearAll.addActionListener(e -> onClearAllIncidents());

        rightActions.add(btnRefresh);
        rightActions.add(btnClearAll);

        topRow.add(leftGroup, BorderLayout.WEST);
        topRow.add(rightActions, BorderLayout.EAST);

        // Hàng 2: Bộ lọc trạng thái (Tất cả · Đang diễn ra · Đã phục hồi)
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filterRow.setOpaque(false);
        filterRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));

        setupFilterBtn(btnAll, true);
        setupFilterBtn(btnActive, false);
        setupFilterBtn(btnRecovered, false);

        filterGroup.add(btnAll);
        filterGroup.add(btnActive);
        filterGroup.add(btnRecovered);

        filterRow.add(btnAll);
        filterRow.add(btnActive);
        filterRow.add(btnRecovered);

        bar.add(topRow);
        bar.add(filterRow);
        return bar;
    }

    private void setupFilterBtn(JToggleButton btn, boolean sel) {
        btn.setSelected(sel);
        btn.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> applyFilter());
    }

    private void setColumnWidths() {
        if (allColumns[0] != null) {
            allColumns[0].setMinWidth(UIScale.scale(68));
            allColumns[0].setPreferredWidth(UIScale.scale(74));
            allColumns[0].setMaxWidth(UIScale.scale(85));
        }
        if (allColumns[1] != null) {
            allColumns[1].setMinWidth(UIScale.scale(95));
            allColumns[1].setPreferredWidth(UIScale.scale(125));
        }
        if (allColumns[2] != null) {
            allColumns[2].setMinWidth(UIScale.scale(80));
            allColumns[2].setPreferredWidth(UIScale.scale(100));
        }
        if (allColumns[3] != null) {
            allColumns[3].setMinWidth(UIScale.scale(85));
            allColumns[3].setPreferredWidth(UIScale.scale(95));
            allColumns[3].setMaxWidth(UIScale.scale(110));
        }
        if (allColumns[4] != null) {
            allColumns[4].setMinWidth(UIScale.scale(60));
            allColumns[4].setPreferredWidth(UIScale.scale(72));
            allColumns[4].setMaxWidth(UIScale.scale(90));
        }
        if (allColumns[5] != null) {
            allColumns[5].setMinWidth(UIScale.scale(120));
            allColumns[5].setPreferredWidth(UIScale.scale(180));
        }
    }

    private void setupResponsive() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateResponsiveColumns();
            }
        });
        SwingUtilities.invokeLater(this::updateResponsiveColumns);
    }

    private void updateResponsiveColumns() {
        int w = getWidth();
        if (w <= 0) return;

        // Ẩn cột Địa chỉ (cột 2) khi bề rộng < 600px
        // Ẩn cột Thời lượng (cột 4) khi bề rộng < 480px
        boolean showAddr = w >= UIScale.scale(600);
        boolean showDuration = w >= UIScale.scale(480);

        setColumnVisible(2, showAddr, 1);
        setColumnVisible(4, showDuration, 3);
    }

    private void setColumnVisible(int modelIndex, boolean show, int insertAfterModelIndex) {
        TableColumn col = allColumns[modelIndex];
        if (col == null) return;

        int currentViewIndex = -1;
        for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
            if (table.getColumnModel().getColumn(i).getModelIndex() == modelIndex) {
                currentViewIndex = i;
                break;
            }
        }

        if (show && currentViewIndex == -1) {
            table.getColumnModel().addColumn(col);
            int targetViewIdx = table.getColumnModel().getColumnCount() - 1;
            for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
                if (table.getColumnModel().getColumn(i).getModelIndex() == insertAfterModelIndex) {
                    targetViewIdx = i + 1;
                    break;
                }
            }
            if (targetViewIdx < table.getColumnModel().getColumnCount() - 1) {
                table.getColumnModel().moveColumn(table.getColumnModel().getColumnCount() - 1, targetViewIdx);
            }
        } else if (!show && currentViewIndex != -1) {
            table.getColumnModel().removeColumn(col);
        }
    }

    private void setupEmptyPanel() {
        emptyPanel.setOpaque(true);
        emptyPanel.setBackground(ThemeManager.bgSurface());
        emptyPanel.setVisible(false);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);

        JLabel icon = new JLabel(ThemeManager.getIcon("check-circle", 36));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel msg = new JLabel(I18n.get("incidents.empty"));
        msg.setFont(ThemeManager.sans(Font.PLAIN, 13));
        msg.setForeground(ThemeManager.statusOnline());
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);

        p.add(icon);
        p.add(Box.createVerticalStrut(UIScale.scale(10)));
        p.add(msg);

        emptyPanel.add(p);
    }

    public void refreshData() {
        rawLogs.clear();
        if (incidentRepository != null) {
            rawLogs.addAll(incidentRepository.getRecentIncidents(200));
        }
        lblSummary.setText(I18n.get("incidents.summary", rawLogs.size()));
        applyFilter();
    }

    private void applyFilter() {
        List<IncidentLog> filtered = new ArrayList<>();
        for (IncidentLog log : rawLogs) {
            boolean isResolved = "RECOVERED".equalsIgnoreCase(log.getEventType()) ||
                    (log.getDurationSeconds() != null && log.getDurationSeconds() > 0);
            boolean isActive = !isResolved;
            if (btnActive.isSelected() && !isActive) continue;
            if (btnRecovered.isSelected() && !isResolved) continue;
            filtered.add(log);
        }

        tableModel.setLogs(filtered);
        emptyPanel.setVisible(filtered.isEmpty());
    }

    public int getActiveIncidentsCount() {
        int count = 0;
        for (IncidentLog log : rawLogs) {
            boolean isResolved = "RECOVERED".equalsIgnoreCase(log.getEventType()) ||
                    (log.getDurationSeconds() != null && log.getDurationSeconds() >= 0);
            if (!isResolved) {
                count++;
            }
        }
        return count;
    }

    public boolean hasActiveOfflineIncidents() {
        for (IncidentLog log : rawLogs) {
            boolean isResolved = "RECOVERED".equalsIgnoreCase(log.getEventType()) ||
                    (log.getDurationSeconds() != null && log.getDurationSeconds() >= 0);
            if (!isResolved && "OFFLINE".equalsIgnoreCase(log.getEventType())) {
                return true;
            }
        }
        return false;
    }

    private void showIncidentDetails(IncidentLog log) {
        String msg = String.format("Máy chủ: %s (%s:%d)\nLoại sự cố: %s\nThời gian: %s\nChi tiết: %s",
                log.getTargetName(), log.getHost(), log.getPort(),
                log.getEventType(),
                log.getEventTime() != null ? log.getEventTime().format(FULL_FMT) : "",
                log.getMessage());
        JOptionPane.showMessageDialog(this, msg, "Chi tiết sự cố", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onClearAllIncidents() {
        if (rawLogs.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hiện không có sự cố nào để xóa.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn xóa toàn bộ lịch sử sự cố không?\nThao tác này sẽ xóa sạch dữ liệu sự cố trong CSDL.",
                "Xác nhận xóa sự cố", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            incidentRepository.clearAllIncidents();
            refreshData();
            JOptionPane.showMessageDialog(this, "Đã xóa toàn bộ lịch sử sự cố thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private JPopupMenu createIncidentContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem itemDetail = new JMenuItem("Xem chi tiết", ThemeManager.getIcon("info", 16));
        itemDetail.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                IncidentLog log = tableModel.getLogAt(row);
                if (log != null) showIncidentDetails(log);
            }
        });

        JMenuItem itemCopy = new JMenuItem("Sao chép thông tin", ThemeManager.getIcon("copy", 16));
        itemCopy.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                IncidentLog log = tableModel.getLogAt(row);
                if (log != null) {
                    String str = String.format("[%s] Máy chủ: %s (%s:%d) | Loại: %s | Chi tiết: %s",
                            log.getEventTime() != null ? log.getEventTime().format(FULL_FMT) : "",
                            log.getTargetName(), log.getHost(), log.getPort(),
                            log.getEventType(), log.getMessage());
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(str), null);
                }
            }
        });

        JMenuItem itemClear = new JMenuItem("Xóa toàn bộ sự cố", ThemeManager.getIcon("trash-2", 16));
        itemClear.setForeground(ThemeManager.statusOffline());
        itemClear.addActionListener(e -> onClearAllIncidents());

        menu.add(itemDetail);
        menu.add(itemCopy);
        menu.addSeparator();
        menu.add(itemClear);
        return menu;
    }

    // ==========================================
    // TableModel & Renderer
    // ==========================================
    private static class IncidentTableModel extends AbstractTableModel {
        private final String[] columns = {"Thời gian", "Máy chủ", "Địa chỉ", "Loại", "Thời lượng", "Chi tiết"};
        private final List<IncidentLog> logs = new ArrayList<>();

        public void setLogs(List<IncidentLog> newLogs) {
            this.logs.clear();
            if (newLogs != null) this.logs.addAll(newLogs);
            fireTableDataChanged();
        }

        public IncidentLog getLogAt(int r) {
            return (r >= 0 && r < logs.size()) ? logs.get(r) : null;
        }

        @Override public int getRowCount() { return logs.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            IncidentLog log = logs.get(r);
            switch (c) {
                case 0: return log.getEventTime();
                case 1: return log.getTargetName();
                case 2: return log.getHost() + (log.getPort() > 0 ? ":" + log.getPort() : "");
                case 3: return log.getEventType();
                case 4: return log;
                case 5: return log.getMessage();
                default: return "";
            }
        }
    }

    private class IncidentCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JLabel c = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            c.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            c.setBackground(isSelected ? ThemeManager.bgSelected() : ThemeManager.bgSurface());

            int modelCol = table.convertColumnIndexToModel(column);
            switch (modelCol) {
                case 0: // Thời gian (HH:mm:ss)
                    c.setFont(ThemeManager.mono(Font.PLAIN, 11));
                    c.setForeground(ThemeManager.textSecondary());
                    if (value instanceof java.time.LocalDateTime) {
                        var dt = (java.time.LocalDateTime) value;
                        c.setText(dt.format(TIME_FMT));
                        c.setToolTipText(dt.format(FULL_FMT));
                    }
                    break;

                case 1: // Máy chủ
                    c.setFont(ThemeManager.sans(Font.BOLD, 12));
                    c.setForeground(ThemeManager.textPrimary());
                    c.setText(value != null ? value.toString() : "");
                    if (row >= 0 && row < table.getRowCount()) {
                        int modelRow = table.convertRowIndexToModel(row);
                        IncidentLog log = tableModel.getLogAt(modelRow);
                        if (log != null) {
                            c.setToolTipText(log.getTargetName() + " (" + log.getHost() + (log.getPort() > 0 ? ":" + log.getPort() : "") + ")");
                        } else {
                            c.setToolTipText(c.getText());
                        }
                    }
                    break;

                case 2: // Địa chỉ
                    c.setFont(ThemeManager.mono(Font.PLAIN, 11));
                    c.setForeground(ThemeManager.textSecondary());
                    c.setText(value != null ? value.toString() : "");
                    break;

                case 3: // Loại (Badge: Offline đỏ, Trễ cao hổ phách, Đã phục hồi xanh)
                    return createTypeBadge(value != null ? value.toString() : "", isSelected);

                case 4: // Thời lượng
                    c.setFont(ThemeManager.mono(Font.PLAIN, 11));
                    c.setForeground(ThemeManager.textSecondary());
                    if (value instanceof IncidentLog) {
                        IncidentLog log = (IncidentLog) value;
                        if (log.getDurationSeconds() != null && log.getDurationSeconds() > 0) {
                            int sec = log.getDurationSeconds();
                            c.setText(sec >= 60 ? String.format("%dm %ds", sec / 60, sec % 60) : sec + "s");
                        } else if ("RECOVERED".equalsIgnoreCase(log.getEventType())) {
                            c.setText("—");
                        } else {
                            c.setText("Đang lỗi");
                            c.setForeground(ThemeManager.statusWarning());
                        }
                    }
                    break;

                case 5: // Chi tiết
                    c.setFont(ThemeManager.sans(Font.PLAIN, 12));
                    c.setForeground(ThemeManager.textPrimary());
                    String msg = value != null ? value.toString() : "";
                    c.setText(msg);
                    c.setToolTipText(msg);
                    break;
            }
            return c;
        }

        private JComponent createTypeBadge(String eventType, boolean isSelected) {
            Color color;
            String text;
            if ("OFFLINE".equalsIgnoreCase(eventType)) {
                color = ThemeManager.statusOffline();
                text = "Offline";
            } else if ("HIGH_LATENCY".equalsIgnoreCase(eventType)) {
                color = ThemeManager.statusWarning();
                text = "Trễ cao";
            } else {
                color = ThemeManager.statusOnline();
                text = "Đã phục hồi";
            }

            JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4)) {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int arc = UIScale.scale(10);
                    int padX = UIScale.scale(4);
                    int padY = UIScale.scale(4);
                    g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 32));
                    g2.fillRoundRect(padX, padY, getWidth() - (padX * 2), getHeight() - (padY * 2), arc, arc);
                    g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 120));
                    g2.drawRoundRect(padX, padY, getWidth() - (padX * 2), getHeight() - (padY * 2), arc, arc);
                    g2.dispose();
                }
            };
            p.setOpaque(true);
            p.setBackground(isSelected ? ThemeManager.bgSelected() : ThemeManager.bgSurface());

            JLabel lbl = new JLabel("● " + text);
            lbl.setFont(ThemeManager.sans(Font.BOLD, 11));
            lbl.setForeground(color);
            p.add(lbl);
            return p;
        }
    }
}

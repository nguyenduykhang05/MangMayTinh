package com.networkalert.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Danh sách máy chủ (Khối bên trái)
 * Bảng dữ liệu, thanh lọc thông minh, menu chuột phải, phím tắt và trạng thái rỗng
 */
public class ServerListPanel extends JPanel {
    private final ServerTableModel tableModel;
    private final JTable table;
    private final TableRowSorter<ServerTableModel> sorter;
    private final ServerTableRenderer renderer;

    // Bộ lọc
    private final JTextField txtSearch = new JTextField();
    private final JComboBox<String> cmbProtocol = new JComboBox<>();
    private final JComboBox<String> cmbStation = new JComboBox<>();
    private final JComboBox<String> cmbGroup = new JComboBox<>();
    private final ButtonGroup filterGroup = new ButtonGroup();
    private final JToggleButton btnFilterAll = new JToggleButton();
    private final JToggleButton btnFilterOnline = new JToggleButton();
    private final JToggleButton btnFilterWarning = new JToggleButton();
    private final JToggleButton btnFilterOffline = new JToggleButton();

    // Tiêu đề & Footer bảng
    private final JLabel lblHeaderTitle = new JLabel("Máy chủ được giám sát");
    private final JLabel lblHeaderBadge = new JLabel("0");
    private final JLabel lblFooterCount = new JLabel("Hiển thị 0 / 0 máy chủ");
    private final JLabel lblFooterNote = new JLabel("Nhấp đúp chuột để chỉnh sửa cấu hình máy chủ");

    private Timer searchDebounceTimer;
    private Timer relativeTimeTimer;

    // Trạng thái rỗng
    private final JPanel emptyPanel = new JPanel(new GridBagLayout());
    private final JLabel lblEmptyIcon = new JLabel();
    private final JLabel lblEmptyText = new JLabel();
    private final JButton btnEmptyAction = new JButton();

    // Callbacks
    private Consumer<ServerTarget> onSelectTargetCallback;
    private Consumer<ServerTarget> onEditTargetCallback;
    private Consumer<ServerTarget> onDeleteTargetCallback;
    private Consumer<ServerTarget> onCheckNowCallback;
    private Consumer<ServerTarget> onToggleActiveCallback;
    private Runnable onAddServerCallback;

    public ServerListPanel(Runnable onAddServer,
                           Consumer<ServerTarget> onSelectTarget,
                           Consumer<ServerTarget> onEditTarget,
                           Consumer<ServerTarget> onDeleteTarget,
                           Consumer<ServerTarget> onCheckNow,
                           Consumer<ServerTarget> onToggleActive) {
        this.onAddServerCallback = onAddServer;
        this.onSelectTargetCallback = onSelectTarget;
        this.onEditTargetCallback = onEditTarget;
        this.onDeleteTargetCallback = onDeleteTarget;
        this.onCheckNowCallback = onCheckNow;
        this.onToggleActiveCallback = onToggleActive;

        setLayout(new BorderLayout());
        setOpaque(false);

        // 1. THANH LỌC (Cao 48 px)
        JPanel filterBar = createFilterBar();
        add(filterBar, BorderLayout.NORTH);

        // 2. BẢNG DỮ LIỆU
        tableModel = new ServerTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(UIScale.scale(32));
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(ThemeManager.border());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);

        // Header
        table.getTableHeader().setPreferredSize(new Dimension(100, UIScale.scale(30)));
        table.getTableHeader().setFont(ThemeManager.sans(Font.BOLD, 11));
        table.getTableHeader().setForeground(ThemeManager.textSecondary());

        // Header renderer cho Cột 0: Ô Checkbox chọn tất cả theo chuẩn Ảnh 2
        if (table.getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setHeaderRenderer((tbl, val, isSel, hasFoc, r, c) -> {
                JPanel hp = new JPanel(new GridBagLayout());
                hp.setBackground(ThemeManager.bgSurfaceAlt());
                hp.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeManager.border()));
                JCheckBox hchk = new JCheckBox();
                hchk.setOpaque(false);
                hchk.setSelected(false);
                hchk.setEnabled(false);
                hchk.putClientProperty("FlatLaf.style", "arc: 4;");
                hp.add(hchk);
                return hp;
            });
        }

        // Renderer và Editor
        renderer = new ServerTableRenderer();
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        if (table.getColumnCount() > 9) {
            table.getColumnModel().getColumn(9).setCellEditor(new ToggleSwitchCellEditor((target, enabled) -> {
                if (onToggleActiveCallback != null) {
                    onToggleActiveCallback.accept(target);
                }
            }));
        }

        setColumnWidths();

        // Giữ nguyên toàn bộ 11 cột hiển thị đầy đủ
        setupResponsiveColumns();

        // 3. MENU CHUỘT PHẢI & PHÍM TẮT
        setupTableInteractions();

        // Lớp chứa bảng có scroll và hỗ trợ empty state
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        scrollPane.getViewport().setBackground(ThemeManager.bgSurface());

        setupEmptyStatePanel();

        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setLayout(new OverlayLayout(layeredPane));
        layeredPane.add(emptyPanel, Integer.valueOf(1));
        layeredPane.add(scrollPane, Integer.valueOf(0));

        add(layeredPane, BorderLayout.CENTER);

        // 4. FOOTER BẢNG MÁY CHỦ
        JPanel footerBar = new JPanel(new BorderLayout());
        footerBar.setOpaque(false);
        footerBar.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));

        lblFooterCount.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblFooterCount.setForeground(ThemeManager.textMuted());

        lblFooterNote.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblFooterNote.setForeground(ThemeManager.textMuted());

        footerBar.add(lblFooterCount, BorderLayout.WEST);
        footerBar.add(lblFooterNote, BorderLayout.EAST);
        add(footerBar, BorderLayout.SOUTH);

        // Timer cập nhật "N giây trước" mỗi 1 giây
        relativeTimeTimer = new Timer(1000, e -> {
            if (table.isVisible() && tableModel.getRowCount() > 0) {
                int colIdx = 8;
                if (colIdx < table.getColumnCount()) {
                    table.repaint();
                }
            }
        });
        relativeTimeTimer.start();
    }

    private JPanel createFilterBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, UIScale.scale(8), 0));

        // BÊN TRÁI: Ô Tìm kiếm + Dropdown Giao thức (gọn gàng, không bị cắt chữ)
        JPanel leftGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(4), 0));
        leftGroup.setOpaque(false);

        txtSearch.setPreferredSize(new Dimension(UIScale.scale(125), UIScale.scale(28)));
        txtSearch.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, I18n.get("filter.search_placeholder", "Tìm kiếm..."));
        txtSearch.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, ThemeManager.getIcon("search", 15));
        txtSearch.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        txtSearch.putClientProperty(FlatClientProperties.STYLE, "arc: 6");

        // Debounce lọc 200 ms
        txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { triggerSearchDebounce(); }
            public void removeUpdate(DocumentEvent e) { triggerSearchDebounce(); }
            public void changedUpdate(DocumentEvent e) { triggerSearchDebounce(); }
        });

        // Phím Ctrl+F và Esc trong ô tìm kiếm
        txtSearch.registerKeyboardAction(e -> {
            txtSearch.setText("");
            applyFilter();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);

        // Dropdown Giao thức: rộng 125px vừa vặn "Giao thức: Tất cả"
        cmbProtocol.setPreferredSize(new Dimension(UIScale.scale(125), UIScale.scale(28)));
        cmbProtocol.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        cmbProtocol.addItem(I18n.get("filter.protocol_all", "Giao thức: Tất cả"));
        for (ProtocolType p : ProtocolType.values()) {
            cmbProtocol.addItem(p.name());
        }
        cmbProtocol.addActionListener(e -> applyFilter());

        leftGroup.add(txtSearch);
        leftGroup.add(cmbProtocol);

        // BÊN PHẢI: Bộ 4 nút phân đoạn trạng thái (Tất cả, Online, Cảnh báo, Offline)
        JPanel rightGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(3), 0));
        rightGroup.setOpaque(false);

        setupSegmentedButton(btnFilterAll, I18n.get("filter.all", 0), true);
        setupSegmentedButton(btnFilterOnline, I18n.get("filter.online", 0), false);
        setupSegmentedButton(btnFilterWarning, I18n.get("filter.warning", 0), false);
        setupSegmentedButton(btnFilterOffline, I18n.get("filter.offline", 0), false);

        filterGroup.add(btnFilterAll);
        filterGroup.add(btnFilterOnline);
        filterGroup.add(btnFilterWarning);
        filterGroup.add(btnFilterOffline);

        rightGroup.add(btnFilterAll);
        rightGroup.add(btnFilterOnline);
        rightGroup.add(btnFilterWarning);
        rightGroup.add(btnFilterOffline);

        bar.add(leftGroup);
        bar.add(Box.createHorizontalGlue());
        bar.add(rightGroup);
        return bar;
    }

    private void setupSegmentedButton(JToggleButton btn, String text, boolean selected) {
        btn.setText(text);
        btn.setSelected(selected);
        btn.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(UIScale.scale(1), UIScale.scale(2), UIScale.scale(1), UIScale.scale(2)));
        btn.setPreferredSize(new Dimension(UIScale.scale(68), UIScale.scale(28)));
        btn.putClientProperty("FlatLaf.style", "arc: 6; margin: 1,2,1,2");
        btn.addActionListener(e -> applyFilter());
    }

    private void triggerSearchDebounce() {
        if (searchDebounceTimer != null && searchDebounceTimer.isRunning()) {
            searchDebounceTimer.stop();
        }
        searchDebounceTimer = new Timer(200, e -> applyFilter());
        searchDebounceTimer.setRepeats(false);
        searchDebounceTimer.start();
    }

    public void setQuickFilter(String filterKey) {
        if ("ONLINE".equalsIgnoreCase(filterKey)) {
            btnFilterOnline.setSelected(true);
        } else if ("WARNING".equalsIgnoreCase(filterKey)) {
            btnFilterWarning.setSelected(true);
        } else if ("OFFLINE".equalsIgnoreCase(filterKey)) {
            btnFilterOffline.setSelected(true);
        } else {
            btnFilterAll.setSelected(true);
        }
        applyFilter();
    }

    private void applyFilter() {
        String query = txtSearch.getText().trim().toLowerCase();
        String selectedProto = (String) cmbProtocol.getSelectedItem();
        boolean isAllProto = selectedProto == null || selectedProto.equals(I18n.get("filter.protocol_all", "Giao thức: Tất cả"));

        String selectedStation = (String) cmbStation.getSelectedItem();
        boolean isAllStation = selectedStation == null || selectedStation.equals("Trạm: Tất cả");

        String selectedGroup = (String) cmbGroup.getSelectedItem();
        boolean isAllGroup = selectedGroup == null || selectedGroup.equals("Nhóm: Tất cả");

        RowFilter<ServerTableModel, Integer> rf = new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends ServerTableModel, ? extends Integer> entry) {
                ServerTarget t = entry.getModel().getTargetAt(entry.getIdentifier());
                if (t == null) return false;

                // Lọc theo tìm kiếm (tên, host, hoặc tags)
                if (!query.isEmpty()) {
                    boolean matchName = t.getName() != null && t.getName().toLowerCase().contains(query);
                    boolean matchHost = t.getHost() != null && t.getHost().toLowerCase().contains(query);
                    boolean matchTags = t.getTags() != null && t.getTags().toLowerCase().contains(query);
                    if (!matchName && !matchHost && !matchTags) return false;
                }

                // Lọc theo giao thức
                if (!isAllProto && !t.getProtocol().name().equalsIgnoreCase(selectedProto)) {
                    return false;
                }

                // Lọc theo trạm đo
                if (!isAllStation) {
                    if ("Server".equalsIgnoreCase(selectedStation)) {
                        boolean isServer = t.getScope() == com.networkalert.model.TargetScope.SERVER
                                || t.getStationResults().containsKey("LOCAL")
                                || (t.getAssignedAgent() == null || t.getAssignedAgent().isEmpty());
                        if (!isServer) return false;
                    } else {
                        boolean matchAgent = (t.getAssignedAgent() != null && t.getAssignedAgent().equalsIgnoreCase(selectedStation))
                                || t.getStationResults().containsKey(selectedStation);
                        if (!matchAgent) return false;
                    }
                }

                // Lọc theo nhóm mục tiêu
                if (!isAllGroup) {
                    if (t.getGroup() == null || !t.getGroup().equalsIgnoreCase(selectedGroup)) {
                        return false;
                    }
                }

                // Lọc theo nhóm phân đoạn trạng thái (chỉ tính máy chủ đang bật giám sát)
                if (btnFilterOnline.isSelected() && (t.getStatus() != ServerStatus.ONLINE || !t.isEnabled())) {
                    return false;
                }
                if (btnFilterWarning.isSelected() && (t.getStatus() != ServerStatus.WARNING || !t.isEnabled())) {
                    return false;
                }
                if (btnFilterOffline.isSelected() && (t.getStatus() != ServerStatus.OFFLINE || !t.isEnabled())) {
                    return false;
                }

                return true;
            }
        };
        sorter.setRowFilter(rf);
        checkEmptyState();
        lblHeaderBadge.setText(String.valueOf(tableModel.getRowCount()));
        lblFooterCount.setText(String.format("Hiển thị %d / %d máy chủ", table.getRowCount(), tableModel.getRowCount()));
    }

    private void setColumnWidths() {
        if (table.getColumnCount() >= 10) {
            // 0: Checkbox [ ]
            table.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(26));
            table.getColumnModel().getColumn(0).setMinWidth(UIScale.scale(24));
            table.getColumnModel().getColumn(0).setMaxWidth(UIScale.scale(30));

            // 1: Máy chủ (Tên hiển thị - ưu tiên đủ rộng để không bị "...")
            table.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(185));
            table.getColumnModel().getColumn(1).setMinWidth(UIScale.scale(130));

            // 2: Địa chỉ (IP:port / domain) - Co giãn an toàn, tối thiểu 115px (ERRATA §E13 #2)
            table.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(145));
            table.getColumnModel().getColumn(2).setMinWidth(UIScale.scale(115));

            // 3: Giao thức (ICMP, TCP, HTTP, DNS...)
            table.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(60));
            table.getColumnModel().getColumn(3).setMinWidth(UIScale.scale(50));
            table.getColumnModel().getColumn(3).setMaxWidth(UIScale.scale(70));

            // 4: Trạm đo (1/1 OK / Server / Nội bộ)
            table.getColumnModel().getColumn(4).setPreferredWidth(UIScale.scale(84));
            table.getColumnModel().getColumn(4).setMinWidth(UIScale.scale(74));
            table.getColumnModel().getColumn(4).setMaxWidth(UIScale.scale(98));

            // 5: Trạng thái (Trực tuyến / Ngoại tuyến)
            table.getColumnModel().getColumn(5).setPreferredWidth(UIScale.scale(96));
            table.getColumnModel().getColumn(5).setMinWidth(UIScale.scale(86));
            table.getColumnModel().getColumn(5).setMaxWidth(UIScale.scale(112));

            // 6: Độ trễ (1 ms / 251 ms) - minWidth 54 để không bao giờ bị "4..."
            table.getColumnModel().getColumn(6).setPreferredWidth(UIScale.scale(58));
            table.getColumnModel().getColumn(6).setMinWidth(UIScale.scale(54));
            table.getColumnModel().getColumn(6).setMaxWidth(UIScale.scale(72));

            // 7: Nhịp phản hồi (Sparkline 30 nhịp tim)
            table.getColumnModel().getColumn(7).setPreferredWidth(UIScale.scale(90));
            table.getColumnModel().getColumn(7).setMinWidth(UIScale.scale(70));
            table.getColumnModel().getColumn(7).setMaxWidth(UIScale.scale(120));

            // 8: Khả dụng (100.0%)
            table.getColumnModel().getColumn(8).setPreferredWidth(UIScale.scale(70));
            table.getColumnModel().getColumn(8).setMinWidth(UIScale.scale(64));
            table.getColumnModel().getColumn(8).setMaxWidth(UIScale.scale(80));

            // 9: Giám sát (Công tắc bật/tắt)
            table.getColumnModel().getColumn(9).setPreferredWidth(UIScale.scale(56));
            table.getColumnModel().getColumn(9).setMinWidth(UIScale.scale(52));
            table.getColumnModel().getColumn(9).setMaxWidth(UIScale.scale(62));
        }
    }

    private void setupResponsiveColumns() {
        // Giữ nguyên 10 cột chuẩn xác không bị xáo trộn vị trí
    }

    public void updateFilterDropdowns() {
        java.util.Set<String> groups = new java.util.TreeSet<>();
        java.util.Set<String> stations = new java.util.TreeSet<>();
        for (ServerTarget t : tableModel.getAllTargets()) {
            if (t.getGroup() != null && !t.getGroup().trim().isEmpty()) {
                groups.add(t.getGroup());
            }
            if (t.getAssignedAgent() != null && !t.getAssignedAgent().trim().isEmpty()
                    && !t.getAssignedAgent().equalsIgnoreCase("MULTI") && !t.getAssignedAgent().equalsIgnoreCase("LOCAL")) {
                stations.add(t.getAssignedAgent());
            }
            stations.addAll(t.getStationResults().keySet());
        }

        String curGroup = (String) cmbGroup.getSelectedItem();
        cmbGroup.removeAllItems();
        cmbGroup.addItem("Nhóm: Tất cả");
        for (String g : groups) {
            cmbGroup.addItem(g);
        }
        if (curGroup != null) cmbGroup.setSelectedItem(curGroup);

        String curStation = (String) cmbStation.getSelectedItem();
        cmbStation.removeAllItems();
        cmbStation.addItem("Trạm: Tất cả");
        cmbStation.addItem("Server");
        for (String s : stations) {
            if (!s.equalsIgnoreCase("LOCAL") && !s.equalsIgnoreCase("Server")) {
                cmbStation.addItem(s);
            }
        }
        if (curStation != null) cmbStation.setSelectedItem(curStation);
    }

    private void toggleColumnVisibility(int modelColIndex, boolean visible) {
        // Không xóa cột khỏi view để tránh đảo trật tự
    }

    private void setupTableInteractions() {
        // Lựa chọn hàng
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ServerTarget target = getSelectedTarget();
                if (target != null && onSelectTargetCallback != null) {
                    onSelectTargetCallback.accept(target);
                }
            }
        });

        // Menu chuột phải
        JPopupMenu contextMenu = createContextMenu();
        table.setComponentPopupMenu(contextMenu);

        // Nhấp đúp = Sửa máy chủ
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    ServerTarget target = getSelectedTarget();
                    if (target != null && onEditTargetCallback != null) {
                        onEditTargetCallback.accept(target);
                    }
                }
            }
        });

        // Phím tắt điều hướng bảng
        InputMap im = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = table.getActionMap();

        // Enter: Sửa
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "editTarget");
        am.put("editTarget", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                ServerTarget target = getSelectedTarget();
                if (target != null && onEditTargetCallback != null) onEditTargetCallback.accept(target);
            }
        });

        // Space: Bật/Tắt giám sát
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleTarget");
        am.put("toggleTarget", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                ServerTarget target = getSelectedTarget();
                if (target != null && onToggleActiveCallback != null) onToggleActiveCallback.accept(target);
            }
        });

        // Delete: Xóa máy chủ
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteTarget");
        am.put("deleteTarget", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                ServerTarget target = getSelectedTarget();
                if (target != null && onDeleteTargetCallback != null) onDeleteTargetCallback.accept(target);
            }
        });
    }

    private JPopupMenu createContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem itemCheck = new JMenuItem("Kiểm tra ngay", ThemeManager.getIcon("zap", 16));
        itemCheck.addActionListener(e -> {
            ServerTarget t = getSelectedTarget();
            if (t != null && onCheckNowCallback != null) onCheckNowCallback.accept(t);
        });

        JMenuItem itemEdit = new JMenuItem("Sửa", ThemeManager.getIcon("sliders", 16));
        itemEdit.addActionListener(e -> {
            ServerTarget t = getSelectedTarget();
            if (t != null && onEditTargetCallback != null) onEditTargetCallback.accept(t);
        });

        JMenuItem itemToggle = new JMenuItem("Bật/Tắt giám sát", ThemeManager.getIcon("pause", 16));
        itemToggle.addActionListener(e -> {
            ServerTarget t = getSelectedTarget();
            if (t != null && onToggleActiveCallback != null) onToggleActiveCallback.accept(t);
        });

        JMenuItem itemCopy = new JMenuItem("Sao chép địa chỉ", ThemeManager.getIcon("copy", 16));
        itemCopy.addActionListener(e -> {
            ServerTarget t = getSelectedTarget();
            if (t != null) {
                String addr = t.getHost() + (t.getPort() > 0 ? ":" + t.getPort() : "");
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(addr), null);
            }
        });

        JMenuItem itemDelete = new JMenuItem("Xóa", ThemeManager.getIcon("x-circle", 16));
        itemDelete.setForeground(ThemeManager.statusOffline());
        itemDelete.addActionListener(e -> {
            ServerTarget t = getSelectedTarget();
            if (t != null && onDeleteTargetCallback != null) onDeleteTargetCallback.accept(t);
        });

        menu.add(itemCheck);
        menu.add(itemEdit);
        menu.add(itemToggle);
        menu.add(itemCopy);
        menu.addSeparator();
        menu.add(itemDelete);

        return menu;
    }

    private void setupEmptyStatePanel() {
        emptyPanel.setOpaque(true);
        emptyPanel.setBackground(ThemeManager.bgSurface());
        emptyPanel.setVisible(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        lblEmptyIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblEmptyText.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblEmptyText.setFont(ThemeManager.sans(Font.PLAIN, 13));
        lblEmptyText.setForeground(ThemeManager.textSecondary());

        btnEmptyAction.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnEmptyAction.setFont(ThemeManager.sans(Font.BOLD, 12));
        btnEmptyAction.setBackground(ThemeManager.accentFill());
        btnEmptyAction.setForeground(Color.WHITE);

        content.add(lblEmptyIcon);
        content.add(Box.createVerticalStrut(UIScale.scale(12)));
        content.add(lblEmptyText);
        content.add(Box.createVerticalStrut(UIScale.scale(16)));
        content.add(btnEmptyAction);

        emptyPanel.add(content);
    }

    private void checkEmptyState() {
        if (tableModel.getRowCount() == 0) {
            // Không có máy chủ nào
            lblEmptyIcon.setIcon(ThemeManager.getIcon("server", 36));
            lblEmptyText.setText("Chưa có máy chủ nào. Thêm máy chủ đầu tiên để bắt đầu giám sát.");
            btnEmptyAction.setText("Thêm máy chủ");
            btnEmptyAction.setVisible(true);
            btnEmptyAction.removeActionListener(btnEmptyAction.getActionListeners().length > 0 ? btnEmptyAction.getActionListeners()[0] : null);
            btnEmptyAction.addActionListener(e -> {
                if (onAddServerCallback != null) onAddServerCallback.run();
            });
            emptyPanel.setVisible(true);
        } else if (table.getRowCount() == 0) {
            // Lọc không ra kết quả
            lblEmptyIcon.setIcon(ThemeManager.getIcon("search", 36));
            lblEmptyText.setText("Không có máy chủ nào khớp với bộ lọc");
            btnEmptyAction.setText("Xóa bộ lọc");
            btnEmptyAction.setVisible(true);
            btnEmptyAction.removeActionListener(btnEmptyAction.getActionListeners().length > 0 ? btnEmptyAction.getActionListeners()[0] : null);
            btnEmptyAction.addActionListener(e -> {
                txtSearch.setText("");
                cmbProtocol.setSelectedIndex(0);
                btnFilterAll.setSelected(true);
                applyFilter();
            });
            emptyPanel.setVisible(true);
        } else {
            emptyPanel.setVisible(false);
        }
    }

    public void setTargets(List<ServerTarget> targets) {
        tableModel.setTargets(targets);
        updateFilterCounts(targets);
        checkEmptyState();
        lblHeaderBadge.setText(String.valueOf(tableModel.getRowCount()));
        lblFooterCount.setText(String.format("Hiển thị %d / %d máy chủ", table.getRowCount(), tableModel.getRowCount()));
        if (table.getRowCount() > 0 && table.getSelectedRow() == -1) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    public void addTarget(ServerTarget target) {
        tableModel.addTarget(target);
        updateFilterCounts(tableModel.getAllTargets());
        checkEmptyState();
        lblHeaderBadge.setText(String.valueOf(tableModel.getRowCount()));
        lblFooterCount.setText(String.format("Hiển thị %d / %d máy chủ", table.getRowCount(), tableModel.getRowCount()));
    }

    public void removeTarget(int targetId) {
        tableModel.removeTarget(targetId);
        updateFilterCounts(tableModel.getAllTargets());
        checkEmptyState();
        lblHeaderBadge.setText(String.valueOf(tableModel.getRowCount()));
        lblFooterCount.setText(String.format("Hiển thị %d / %d máy chủ", table.getRowCount(), tableModel.getRowCount()));
    }

    public void updateTarget(ServerTarget target) {
        int row = tableModel.updateTarget(target);
        if (row >= 0) {
            renderer.triggerRowFlash(target.getId());
        }
        updateFilterCounts(tableModel.getAllTargets());
        lblHeaderBadge.setText(String.valueOf(tableModel.getRowCount()));
        lblFooterCount.setText(String.format("Hiển thị %d / %d máy chủ", table.getRowCount(), tableModel.getRowCount()));
    }

    private void updateFilterCounts(List<ServerTarget> targets) {
        int total = targets.size();
        int online = 0;
        int warning = 0;
        int offline = 0;

        for (ServerTarget t : targets) {
            if (!t.isEnabled() || t.getStatus() == ServerStatus.PAUSED) {
                continue;
            }
            if (t.getStatus() == ServerStatus.ONLINE) online++;
            else if (t.getStatus() == ServerStatus.WARNING) warning++;
            else if (t.getStatus() == ServerStatus.OFFLINE) offline++;
        }

        btnFilterAll.setText(I18n.get("filter.all", total));
        btnFilterOnline.setText(I18n.get("filter.online", online));
        btnFilterWarning.setText(I18n.get("filter.warning", warning));
        btnFilterOffline.setText(I18n.get("filter.offline", offline));
    }

    public ServerTarget getSelectedTarget() {
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0) {
            int modelRow = table.convertRowIndexToModel(selectedRow);
            return tableModel.getTargetAt(modelRow);
        }
        return null;
    }

    public void focusSearch() {
        txtSearch.requestFocusInWindow();
        txtSearch.selectAll();
    }
}

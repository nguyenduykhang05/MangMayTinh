package com.networkalert.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.Problem;
import com.networkalert.model.ProblemSeverity;
import com.networkalert.model.ProblemStatus;
import com.networkalert.repository.ProblemRepository;
import com.networkalert.ui.MaintenanceDialog;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tab Quản lý Sự cố hệ thống theo chuẩn Nagios / Zabbix (Problems View)
 * Thay thế chế độ xem sự cố cũ với các tính năng:
 * - Phân cấp mức độ sự cố: Information / Warning / High / Disaster
 * - Nguồn phát sinh (Server hoặc Agent cụ thể)
 * - Tự động tính thời lượng duy trì sự cố
 * - Quy trình Acknowledge (Xác nhận sự cố kèm ghi chú)
 * - Tự động Resolved khi mục tiêu hồi phục trạng thái HARD ONLINE
 */
public class ProblemsViewPanel extends JPanel {
    private final ProblemRepository problemRepository;
    private final ProblemTableModel tableModel;
    private final JTable table;
    private final JLabel lblSummary = new JLabel() {
        @Override
        protected void paintComponent(Graphics g) {
            if (getBackground() != null && getText() != null && !getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 1, getWidth(), getHeight() - 2, UIScale.scale(10), UIScale.scale(10));
                g2.dispose();
            }
            super.paintComponent(g);
        }
    };

    private final JComboBox<String> cmbSeverityFilter = new JComboBox<>(new String[]{
            "Mức độ: Tất cả",
            "Khẩn cấp (Disaster)",
            "Cao (High)",
            "Cảnh báo (Warning)",
            "Thông tin (Info)"
    });
    private final JComboBox<String> cmbStatusFilter = new JComboBox<>(new String[]{
            "Trạng thái: Tất cả",
            "Đang mở (Active)",
            "Đã xác nhận (Ack)",
            "Đã đóng (Resolved)"
    });
    private final JTextField txtSearch = new JTextField(10);

    private final List<Problem> allProblems = new ArrayList<>();
    private static final DateTimeFormatter SHORT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FULL_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    public ProblemsViewPanel(ProblemRepository repository) {
        this.problemRepository = repository != null ? repository : ProblemRepository.getInstance();

        setLayout(new BorderLayout(0, UIScale.scale(8)));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10)));

        // 1. THANH CÔNG CỤ & BỘ LỌC
        JPanel topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);

        // 2. BẢNG DỮ LIỆU SỰ CỐ
        tableModel = new ProblemTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(UIScale.scale(34));
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(ThemeManager.border());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFont(ThemeManager.sans(Font.PLAIN, 12));
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);

        table.getTableHeader().setPreferredSize(new Dimension(100, UIScale.scale(32)));
        table.getTableHeader().setFont(ThemeManager.sans(Font.BOLD, 11));
        table.getTableHeader().setForeground(ThemeManager.textSecondary());
        table.getTableHeader().setReorderingAllowed(false);

        configureColumnWidths();

        ProblemCellRenderer cellRenderer = new ProblemCellRenderer();
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(cellRenderer);
        }

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    showSelectedProblemDetails();
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        scrollPane.getViewport().setBackground(ThemeManager.bgSurface());
        add(scrollPane, BorderLayout.CENTER);

        // 3. THANH HÀNH ĐỘNG DƯỚI
        JPanel bottomBar = createBottomBar();
        add(bottomBar, BorderLayout.SOUTH);

        refreshData();
    }

    private void configureColumnWidths() {
        // 0: # (ID)
        table.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(42));
        table.getColumnModel().getColumn(0).setMinWidth(UIScale.scale(36));
        table.getColumnModel().getColumn(0).setMaxWidth(UIScale.scale(52));

        // 1: Mức độ (Badge)
        table.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(88));
        table.getColumnModel().getColumn(1).setMinWidth(UIScale.scale(78));
        table.getColumnModel().getColumn(1).setMaxWidth(UIScale.scale(98));

        // 2: Mục tiêu
        table.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(130));
        table.getColumnModel().getColumn(2).setMinWidth(UIScale.scale(95));

        // 3: Nội dung sự cố
        table.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(210));
        table.getColumnModel().getColumn(3).setMinWidth(UIScale.scale(120));

        // 4: Bắt đầu (HH:mm:ss)
        table.getColumnModel().getColumn(4).setPreferredWidth(UIScale.scale(74));
        table.getColumnModel().getColumn(4).setMinWidth(UIScale.scale(66));
        table.getColumnModel().getColumn(4).setMaxWidth(UIScale.scale(86));

        // 5: Thời lượng
        table.getColumnModel().getColumn(5).setPreferredWidth(UIScale.scale(68));
        table.getColumnModel().getColumn(5).setMinWidth(UIScale.scale(58));
        table.getColumnModel().getColumn(5).setMaxWidth(UIScale.scale(82));

        // 6: Trạng thái (Badge)
        table.getColumnModel().getColumn(6).setPreferredWidth(UIScale.scale(88));
        table.getColumnModel().getColumn(6).setMinWidth(UIScale.scale(78));
        table.getColumnModel().getColumn(6).setMaxWidth(UIScale.scale(100));
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        // Hàng 1: Tiêu đề + Badge tóm tắt bên trái (CENTER để không bao giờ đè lên nút bên phải) | Nút Lịch bảo trì & Làm mới bên phải
        JPanel row1 = new JPanel(new BorderLayout(UIScale.scale(8), 0));
        row1.setOpaque(false);

        JLabel lblTitle = new JLabel(I18n.get("problems.title", "Quản lý sự cố hệ thống"));
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 13));
        lblTitle.setForeground(ThemeManager.textPrimary());
        lblTitle.setIcon(ThemeManager.getIcon("alert-circle", 15));
        lblTitle.setIconTextGap(UIScale.scale(6));

        lblSummary.setFont(ThemeManager.sans(Font.BOLD, 11));
        lblSummary.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(8), UIScale.scale(2), UIScale.scale(8)));
        lblSummary.setOpaque(false);

        JPanel titleGrp = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), UIScale.scale(2)));
        titleGrp.setOpaque(false);
        titleGrp.add(lblTitle);
        titleGrp.add(lblSummary);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        rightControls.setOpaque(false);

        JButton btnDowntime = new JButton(I18n.get("problems.btn_maintenance", "Lịch bảo trì"), ThemeManager.getIcon("tool", 13));
        btnDowntime.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnDowntime.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnDowntime.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 3,8,3,8");
        btnDowntime.addActionListener(e -> {
            Window win = SwingUtilities.getWindowAncestor(this);
            new MaintenanceDialog(win).setVisible(true);
        });

        JButton btnRefresh = new JButton(ThemeManager.getIcon("refresh-cw", 14));
        btnRefresh.setToolTipText("Làm mới danh sách sự cố");
        btnRefresh.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnRefresh.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 3,6,3,6");
        btnRefresh.addActionListener(e -> refreshData());

        rightControls.add(btnDowntime);
        rightControls.add(btnRefresh);

        row1.add(titleGrp, BorderLayout.CENTER);
        row1.add(rightControls, BorderLayout.EAST);

        // Hàng 2: Bộ lọc gọn gàng (2 Dropdown bên trái + Ô tìm kiếm co giãn ở giữa)
        JPanel row2 = new JPanel(new BorderLayout(UIScale.scale(6), 0));
        row2.setOpaque(false);

        JPanel filterLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        filterLeft.setOpaque(false);

        cmbSeverityFilter.setFont(ThemeManager.sans(Font.PLAIN, 12));
        cmbSeverityFilter.setPreferredSize(new Dimension(UIScale.scale(135), UIScale.scale(28)));
        cmbSeverityFilter.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        cmbSeverityFilter.addActionListener(e -> applyFilters());

        cmbStatusFilter.setFont(ThemeManager.sans(Font.PLAIN, 12));
        cmbStatusFilter.setPreferredSize(new Dimension(UIScale.scale(145), UIScale.scale(28)));
        cmbStatusFilter.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        cmbStatusFilter.addActionListener(e -> applyFilters());

        filterLeft.add(cmbSeverityFilter);
        filterLeft.add(cmbStatusFilter);

        txtSearch.setFont(ThemeManager.sans(Font.PLAIN, 12));
        txtSearch.setPreferredSize(new Dimension(UIScale.scale(140), UIScale.scale(28)));
        txtSearch.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Tìm mục tiêu, nguồn hoặc sự cố...");
        txtSearch.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, ThemeManager.getIcon("search", 14));
        txtSearch.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        txtSearch.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        txtSearch.addActionListener(e -> applyFilters());
        txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilters(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilters(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilters(); }
        });

        row2.add(filterLeft, BorderLayout.WEST);
        row2.add(txtSearch, BorderLayout.CENTER);

        panel.add(row1);
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        panel.add(row2);

        return panel;
    }

    private JPanel createBottomBar() {
        JPanel bar = new JPanel(new BorderLayout(UIScale.scale(8), 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), 0, 0, 0));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        leftActions.setOpaque(false);

        JButton btnAck = new JButton(I18n.get("problems.btn_ack", "Xác nhận (Ack)"), ThemeManager.getIcon("check", 14));
        btnAck.setFont(ThemeManager.sans(Font.BOLD, 12));
        btnAck.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnAck.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 4,10,4,10");
        btnAck.addActionListener(e -> onAcknowledgeSelected());

        JButton btnDetail = new JButton("Xem chi tiết", ThemeManager.getIcon("info", 14));
        btnDetail.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnDetail.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnDetail.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 4,10,4,10");
        btnDetail.addActionListener(e -> showSelectedProblemDetails());

        leftActions.add(btnAck);
        leftActions.add(btnDetail);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        rightActions.setOpaque(false);

        JButton btnClearResolved = new JButton("Xóa lịch sử đã đóng", ThemeManager.getIcon("eraser", 14));
        btnClearResolved.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnClearResolved.setForeground(ThemeManager.textSecondary());
        btnClearResolved.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnClearResolved.setToolTipText("Xóa tất cả các sự cố đã giải quyết (Resolved) khỏi danh sách");
        btnClearResolved.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 4,10,4,10");
        btnClearResolved.addActionListener(e -> {
            long resolvedCount = allProblems.stream().filter(p -> p.getStatus() == ProblemStatus.RESOLVED).count();
            if (resolvedCount == 0) {
                JOptionPane.showMessageDialog(this, "Không có sự cố đã đóng nào cần xóa.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            problemRepository.clearResolvedProblems();
            refreshData();
        });

        rightActions.add(btnClearResolved);

        bar.add(leftActions, BorderLayout.WEST);
        bar.add(rightActions, BorderLayout.EAST);

        return bar;
    }

    public synchronized void refreshData() {
        allProblems.clear();
        allProblems.addAll(problemRepository.getAllProblems());
        applyFilters();

        long activeCount = allProblems.stream()
                .filter(p -> p.getStatus() != ProblemStatus.RESOLVED)
                .count();
        boolean dark = ThemeManager.isDarkMode();
        if (activeCount > 0) {
            lblSummary.setText(String.format("%d đang mở / %d tổng", activeCount, allProblems.size()));
            lblSummary.setForeground(dark ? new Color(0xF8, 0x71, 0x71) : new Color(0xDC, 0x26, 0x26));
            lblSummary.setBackground(dark ? new Color(0x45, 0x1A, 0x1A) : new Color(0xFE, 0xE2, 0xE2));
        } else {
            lblSummary.setText(allProblems.isEmpty() ? "Hệ thống ổn định (0 sự cố)" : String.format("0 đang mở / %d đã đóng", allProblems.size()));
            lblSummary.setForeground(dark ? new Color(0x34, 0xD3, 0x99) : new Color(0x15, 0x80, 0x3D));
            lblSummary.setBackground(dark ? new Color(0x06, 0x4E, 0x3B) : new Color(0xDC, 0xFC, 0xE7));
        }
    }

    private void applyFilters() {
        int sevIdx = cmbSeverityFilter.getSelectedIndex();
        int stIdx = cmbStatusFilter.getSelectedIndex();
        String query = txtSearch.getText().trim().toLowerCase();

        List<Problem> filtered = allProblems.stream().filter(p -> {
            if (sevIdx == 1 && p.getSeverity() != ProblemSeverity.DISASTER) return false;
            if (sevIdx == 2 && p.getSeverity() != ProblemSeverity.HIGH) return false;
            if (sevIdx == 3 && p.getSeverity() != ProblemSeverity.WARNING) return false;
            if (sevIdx == 4 && p.getSeverity() != ProblemSeverity.INFORMATION) return false;

            if (stIdx == 1 && p.getStatus() != ProblemStatus.ACTIVE) return false;
            if (stIdx == 2 && p.getStatus() != ProblemStatus.ACKNOWLEDGED) return false;
            if (stIdx == 3 && p.getStatus() != ProblemStatus.RESOLVED) return false;

            if (!query.isEmpty()) {
                boolean matchTarget = p.getTargetName() != null && p.getTargetName().toLowerCase().contains(query);
                boolean matchTitle = p.getTitle() != null && p.getTitle().toLowerCase().contains(query);
                boolean matchSource = p.getSource() != null && p.getSource().toLowerCase().contains(query);
                boolean matchAck = p.getAckComment() != null && p.getAckComment().toLowerCase().contains(query);
                if (!matchTarget && !matchTitle && !matchSource && !matchAck) return false;
            }
            return true;
        }).collect(Collectors.toList());

        tableModel.setProblems(filtered);
    }

    private void onAcknowledgeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một sự cố trong bảng để xác nhận (Acknowledge).", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Problem p = tableModel.getProblemAt(row);
        if (p == null) return;

        if (p.getStatus() == ProblemStatus.RESOLVED) {
            JOptionPane.showMessageDialog(this, "Sự cố này đã được giải quyết (Resolved), không cần xác nhận thêm.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JPanel pnl = new JPanel(new GridLayout(2, 2, 8, 8));
        JTextField txtUser = new JTextField(System.getProperty("user.name", "Admin"));
        JTextField txtComment = new JTextField("Đang xử lý khắc phục đường truyền");

        pnl.add(new JLabel("Người xác nhận (User):"));
        pnl.add(txtUser);
        pnl.add(new JLabel("Ghi chú xử lý (Comment):"));
        pnl.add(txtComment);

        int res = JOptionPane.showConfirmDialog(this, pnl, "Xác nhận sự cố #" + p.getId() + " - " + p.getTargetName(), JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            String user = txtUser.getText().trim();
            String comment = txtComment.getText().trim();
            if (user.isEmpty()) user = "Admin";
            problemRepository.acknowledgeProblem(p.getId(), user, comment);
            refreshData();
        }
    }

    private void showSelectedProblemDetails() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một sự cố trong bảng để xem chi tiết.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Problem p = tableModel.getProblemAt(row);
        if (p == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("Mã sự cố:      #").append(p.getId()).append("\n");
        sb.append("Mục tiêu:      ").append(p.getTargetName()).append(" (ID: ").append(p.getTargetId()).append(")\n");
        sb.append("Nguồn báo cáo: ").append(p.getSource() != null ? p.getSource() : "Server").append("\n");
        sb.append("Mức độ:        ").append(formatSeverityLabel(p.getSeverity())).append(" (").append(p.getSeverity().name()).append(")\n");
        sb.append("Trạng thái:    ").append(formatStatusLabel(p.getStatus())).append(" (").append(p.getStatus().name()).append(")\n");
        sb.append("Tiêu đề:       ").append(p.getTitle()).append("\n");
        sb.append("Chi tiết:      ").append(p.getDescription()).append("\n");
        sb.append("Bắt đầu:       ").append(p.getStartTime() != null ? p.getStartTime().format(FULL_TIME_FMT) : "?").append("\n");
        sb.append("Thời lượng:    ").append(p.getFormattedDuration()).append("\n");
        if (p.getResolvedTime() != null) {
            sb.append("Thời điểm đóng: ").append(p.getResolvedTime().format(FULL_TIME_FMT)).append("\n");
        }
        if (p.getAckUser() != null) {
            sb.append("Người xác nhận: ").append(p.getAckUser()).append("\n");
            sb.append("Ghi chú Ack:    ").append(p.getAckComment()).append("\n");
        }

        JTextArea ta = new JTextArea(sb.toString(), 12, 44);
        ta.setEditable(false);
        ta.setFont(ThemeManager.mono(Font.PLAIN, 12));
        ta.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Chi tiết sự cố #" + p.getId(), JOptionPane.INFORMATION_MESSAGE);
    }

    public int getActiveProblemsCount() {
        return (int) allProblems.stream()
                .filter(p -> p.getStatus() == ProblemStatus.ACTIVE || p.getStatus() == ProblemStatus.ACKNOWLEDGED)
                .count();
    }

    public boolean hasDisasterOrHighProblems() {
        return allProblems.stream()
                .anyMatch(p -> (p.getStatus() != ProblemStatus.RESOLVED) &&
                        (p.getSeverity() == ProblemSeverity.DISASTER || p.getSeverity() == ProblemSeverity.HIGH));
    }

    private static String formatSeverityLabel(ProblemSeverity sev) {
        if (sev == null) return "Thông tin";
        switch (sev) {
            case DISASTER: return "Khẩn cấp";
            case HIGH: return "Nghiêm trọng";
            case WARNING: return "Cảnh báo";
            case INFORMATION:
            default: return "Thông tin";
        }
    }

    private static String formatStatusLabel(ProblemStatus st) {
        if (st == null) return "Đang mở";
        switch (st) {
            case ACTIVE: return "Đang mở";
            case ACKNOWLEDGED: return "Đã xác nhận";
            case RESOLVED:
            default: return "Đã đóng";
        }
    }

    // Table Model (7 cột tối ưu tỷ lệ hiển thị, không bị cắt cụt "...")
    private static class ProblemTableModel extends AbstractTableModel {
        private final String[] columns = {
                "#", "Mức độ", "Mục tiêu", "Nội dung sự cố", "Bắt đầu", "Thời lượng", "Trạng thái"
        };
        private List<Problem> problems = new ArrayList<>();

        public void setProblems(List<Problem> list) {
            this.problems = list != null ? list : Collections.emptyList();
            fireTableDataChanged();
        }

        public Problem getProblemAt(int row) {
            if (row >= 0 && row < problems.size()) return problems.get(row);
            return null;
        }

        @Override public int getRowCount() { return problems.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            Problem p = problems.get(row);
            switch (col) {
                case 0: return "#" + p.getId();
                case 1: return p.getSeverity();
                case 2: return p.getTargetName();
                case 3:
                    if (p.getAckUser() != null && !p.getAckUser().isEmpty()) {
                        return p.getTitle() + " [Ack: " + p.getAckUser() + "]";
                    }
                    return p.getTitle();
                case 4: return p.getStartTime() != null ? p.getStartTime().format(SHORT_TIME_FMT) : "—";
                case 5: return p.getFormattedDuration();
                case 6: return p.getStatus();
                default: return "";
            }
        }
    }

    // Cell Renderer hiện đại với Badge bo góc cho Mức độ & Trạng thái + Tooltip đầy đủ
    private static class ProblemCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
            Problem p = null;
            if (tbl.getModel() instanceof ProblemTableModel) {
                p = ((ProblemTableModel) tbl.getModel()).getProblemAt(tbl.convertRowIndexToModel(row));
            }

            Color rowBg = isSelected ? ThemeManager.bgSelected() : ThemeManager.bgSurface();
            String tooltip = buildProblemTooltip(p);

            // Cột 1: Huy hiệu Mức độ (Severity Badge)
            if (col == 1 && value instanceof ProblemSeverity) {
                JComponent badge = createSeverityBadge((ProblemSeverity) value, rowBg);
                badge.setToolTipText(tooltip);
                return badge;
            }

            // Cột 6: Huy hiệu Trạng thái (Status Badge)
            if (col == 6 && value instanceof ProblemStatus) {
                JComponent badge = createProblemStatusBadge((ProblemStatus) value, rowBg);
                badge.setToolTipText(tooltip);
                return badge;
            }

            JLabel c = (JLabel) super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
            c.setBackground(rowBg);
            c.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(6), 0, UIScale.scale(6)));
            c.setToolTipText(tooltip);

            boolean isResolved = (p != null && p.getStatus() == ProblemStatus.RESOLVED);

            switch (col) {
                case 0: // ID
                    c.setHorizontalAlignment(SwingConstants.CENTER);
                    c.setFont(ThemeManager.mono(Font.PLAIN, 11));
                    c.setForeground(ThemeManager.textMuted());
                    break;
                case 2: // Mục tiêu
                    c.setHorizontalAlignment(SwingConstants.LEFT);
                    c.setFont(ThemeManager.sans(Font.BOLD, 12));
                    c.setForeground(isResolved ? ThemeManager.textSecondary() : ThemeManager.textPrimary());
                    break;
                case 3: // Nội dung sự cố
                    c.setHorizontalAlignment(SwingConstants.LEFT);
                    c.setFont(ThemeManager.sans(Font.PLAIN, 12));
                    c.setForeground(isResolved ? ThemeManager.textSecondary() : ThemeManager.textPrimary());
                    break;
                case 4: // Bắt đầu
                case 5: // Thời lượng
                    c.setHorizontalAlignment(SwingConstants.CENTER);
                    c.setFont(ThemeManager.mono(Font.PLAIN, 11));
                    c.setForeground(ThemeManager.textSecondary());
                    break;
                default:
                    c.setHorizontalAlignment(SwingConstants.LEFT);
                    c.setFont(ThemeManager.sans(Font.PLAIN, 12));
                    c.setForeground(ThemeManager.textPrimary());
                    break;
            }

            return c;
        }

        private String buildProblemTooltip(Problem p) {
            if (p == null) return null;
            StringBuilder sb = new StringBuilder("<html><b>Sự cố #").append(p.getId()).append(" — ").append(p.getTargetName()).append("</b>");
            sb.append("<br>• Nguồn báo cáo: ").append(p.getSource() != null ? p.getSource() : "Server");
            sb.append("<br>• Mức độ: ").append(formatSeverityLabel(p.getSeverity())).append(" | Trạng thái: ").append(formatStatusLabel(p.getStatus()));
            sb.append("<br>• Nội dung: ").append(p.getTitle());
            if (p.getStartTime() != null) {
                sb.append("<br>• Bắt đầu: ").append(p.getStartTime().format(FULL_TIME_FMT)).append(" (").append(p.getFormattedDuration()).append(")");
            }
            if (p.getAckUser() != null) {
                sb.append("<br>• Đã xác nhận bởi <b>").append(p.getAckUser()).append("</b>: ").append(p.getAckComment());
            }
            sb.append("</html>");
            return sb.toString();
        }

        private JComponent createSeverityBadge(ProblemSeverity sev, Color rowBg) {
            boolean dark = ThemeManager.isDarkMode();
            Color fg;
            Color bg;
            String text = formatSeverityLabel(sev);

            switch (sev) {
                case DISASTER:
                    fg = dark ? new Color(0xFC, 0xA5, 0xA5) : new Color(0x99, 0x1B, 0x1B);
                    bg = dark ? new Color(0x5F, 0x14, 0x14) : new Color(0xFE, 0xCA, 0xCA);
                    break;
                case HIGH:
                    fg = dark ? new Color(0xF8, 0x71, 0x71) : new Color(0xDC, 0x26, 0x26);
                    bg = dark ? new Color(0x45, 0x1A, 0x1A) : new Color(0xFE, 0xE2, 0xE2);
                    break;
                case WARNING:
                    fg = dark ? new Color(0xFB, 0xBF, 0x24) : new Color(0xB4, 0x53, 0x09);
                    bg = dark ? new Color(0x42, 0x2A, 0x0B) : new Color(0xFE, 0xF3, 0xC7);
                    break;
                case INFORMATION:
                default:
                    fg = dark ? new Color(0x60, 0xA5, 0xFA) : new Color(0x1D, 0x4E, 0xD8);
                    bg = dark ? new Color(0x1E, 0x29, 0x3B) : new Color(0xDB, 0xEA, 0xFE);
                    break;
            }
            return createPillComponent(text, fg, bg, rowBg);
        }

        private JComponent createProblemStatusBadge(ProblemStatus st, Color rowBg) {
            boolean dark = ThemeManager.isDarkMode();
            Color fg;
            Color bg;
            String text = formatStatusLabel(st);

            switch (st) {
                case ACTIVE:
                    fg = dark ? new Color(0xF8, 0x71, 0x71) : new Color(0xDC, 0x26, 0x26);
                    bg = dark ? new Color(0x45, 0x1A, 0x1A) : new Color(0xFE, 0xE2, 0xE2);
                    break;
                case ACKNOWLEDGED:
                    fg = dark ? new Color(0xFB, 0xBF, 0x24) : new Color(0xB4, 0x53, 0x09);
                    bg = dark ? new Color(0x42, 0x2A, 0x0B) : new Color(0xFE, 0xF3, 0xC7);
                    break;
                case RESOLVED:
                default:
                    fg = dark ? new Color(0x34, 0xD3, 0x99) : new Color(0x15, 0x80, 0x3D);
                    bg = dark ? new Color(0x06, 0x4E, 0x3B) : new Color(0xDC, 0xFC, 0xE7);
                    break;
            }
            return createPillComponent(text, fg, bg, rowBg);
        }

        private JComponent createPillComponent(String text, Color fg, Color bg, Color rowBg) {
            JPanel p = new JPanel(new GridBagLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    int arc = UIScale.scale(8);
                    int padX = UIScale.scale(5);
                    int padY = UIScale.scale(6);
                    int w = Math.max(10, getWidth() - (padX * 2));
                    int h = Math.max(10, getHeight() - (padY * 2));

                    g2.setColor(bg);
                    g2.fillRoundRect(padX, padY, w, h, arc, arc);

                    g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 65));
                    g2.drawRoundRect(padX, padY, w, h, arc, arc);
                    g2.dispose();
                }
            };
            p.setOpaque(true);
            p.setBackground(rowBg);

            JLabel lbl = new JLabel(text);
            lbl.setFont(ThemeManager.sans(Font.BOLD, 11));
            lbl.setForeground(fg);
            p.add(lbl);
            return p;
        }
    }
}

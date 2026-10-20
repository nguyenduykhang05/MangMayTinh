package com.networkalert.ui;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.MaintenanceScope;
import com.networkalert.model.MaintenanceWindow;
import com.networkalert.service.MaintenanceService;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Hộp thoại quản lý Cửa sổ bảo trì hệ thống (Maintenance Windows / Downtime)
 */
public class MaintenanceDialog extends JDialog {
    private final MaintenanceService maintenanceService;
    private final JTable table;
    private final DefaultTableModel tableModel;

    public MaintenanceDialog(Window parent) {
        super(parent, I18n.get("maintenance.title", "Quản lý Lịch bảo trì (Downtime)"), ModalityType.APPLICATION_MODAL);
        this.maintenanceService = MaintenanceService.getInstance();

        setLayout(new BorderLayout(0, UIScale.scale(10)));
        setSize(UIScale.scale(780), UIScale.scale(460));
        setLocationRelativeTo(parent);

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(16), UIScale.scale(4), UIScale.scale(16)));
        JLabel lblTitle = new JLabel(I18n.get("maintenance.title", "Cửa sổ bảo trì hệ thống (Downtime Maintenance)"));
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 15));
        lblTitle.setIcon(ThemeManager.getIcon("tool", 18));
        headerPanel.add(lblTitle, BorderLayout.WEST);

        JLabel lblSub = new JLabel("Trong thời gian bảo trì: Mục tiêu hiển thị MAINTENANCE, loại khỏi tính SLA, không gửi cảnh báo.");
        lblSub.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblSub.setForeground(ThemeManager.textMuted());
        headerPanel.add(lblSub, BorderLayout.SOUTH);
        add(headerPanel, BorderLayout.NORTH);

        // Table
        String[] columns = {
                I18n.get("maintenance.table_id", "ID"),
                I18n.get("maintenance.table_name", "Tên lịch bảo trì"),
                I18n.get("maintenance.table_scope", "Phạm vi"),
                I18n.get("maintenance.table_value", "Áp dụng cho"),
                I18n.get("maintenance.table_schedule", "Lịch biểu"),
                I18n.get("maintenance.table_status", "Trạng thái")
        };

        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(tableModel);
        table.setRowHeight(UIScale.scale(32));
        table.setFont(ThemeManager.sans(Font.PLAIN, 12));
        table.getTableHeader().setFont(ThemeManager.sans(Font.BOLD, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(40));
        table.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(180));
        table.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(90));
        table.getColumnModel().getColumn(3).setPreferredWidth(UIScale.scale(110));
        table.getColumnModel().getColumn(4).setPreferredWidth(UIScale.scale(200));
        table.getColumnModel().getColumn(5).setPreferredWidth(UIScale.scale(120));

        // Custom renderer for status
        table.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
                String val = String.valueOf(value);
                if (val.contains("Đang hiệu lực")) {
                    setForeground(new Color(0x25, 0x63, 0xEB)); // blue-600
                    setFont(ThemeManager.sans(Font.BOLD, 12));
                } else if (val.contains("Đã tắt")) {
                    setForeground(ThemeManager.textMuted());
                    setFont(ThemeManager.sans(Font.PLAIN, 12));
                } else {
                    setForeground(ThemeManager.textPrimary());
                    setFont(ThemeManager.sans(Font.PLAIN, 12));
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(16), 0, UIScale.scale(16)));
        add(scrollPane, BorderLayout.CENTER);

        // Buttons Footer
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), UIScale.scale(12)));
        footerPanel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(16), UIScale.scale(8), UIScale.scale(16)));

        JButton btnAdd = new JButton(I18n.get("maintenance.btn_add", "Thêm lịch bảo trì"), ThemeManager.getIcon("plus", 14));
        btnAdd.addActionListener(e -> showAddDialog());

        JButton btnToggle = new JButton(I18n.get("maintenance.btn_toggle", "Bật / Tắt"), ThemeManager.getIcon("sliders", 14));
        btnToggle.addActionListener(e -> toggleSelectedWindow());

        JButton btnDelete = new JButton(I18n.get("maintenance.btn_delete", "Xóa"), ThemeManager.getIcon("trash-2", 14));
        btnDelete.addActionListener(e -> deleteSelectedWindow());

        JButton btnClose = new JButton("Đóng");
        btnClose.addActionListener(e -> dispose());

        footerPanel.add(btnAdd);
        footerPanel.add(btnToggle);
        footerPanel.add(btnDelete);
        footerPanel.add(Box.createHorizontalStrut(UIScale.scale(15)));
        footerPanel.add(btnClose);

        add(footerPanel, BorderLayout.SOUTH);

        refreshData();
    }

    private void refreshData() {
        tableModel.setRowCount(0);
        List<MaintenanceWindow> list = maintenanceService.getAllWindows();
        for (MaintenanceWindow w : list) {
            String statusText;
            if (!w.isEnabled()) {
                statusText = "Đã tắt (Tạm dừng)";
            } else if (w.isActiveNow()) {
                statusText = "🟢 Đang hiệu lực (ACTIVE)";
            } else {
                statusText = "⚪ Chờ lịch";
            }

            tableModel.addRow(new Object[]{
                    w.getId(),
                    w.getName(),
                    w.getScopeType().name(),
                    w.getScopeValue() != null && !w.getScopeValue().isEmpty() ? w.getScopeValue() : "Toàn bộ",
                    w.getScheduleDisplay(),
                    statusText
            });
        }
    }

    private void toggleSelectedWindow() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một dòng cửa sổ bảo trì để chuyển trạng thái.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int id = (int) tableModel.getValueAt(row, 0);
        for (MaintenanceWindow w : maintenanceService.getAllWindows()) {
            if (w.getId() == id) {
                w.setEnabled(!w.isEnabled());
                break;
            }
        }
        refreshData();
    }

    private void deleteSelectedWindow() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một cửa sổ bảo trì để xóa.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int id = (int) tableModel.getValueAt(row, 0);
        int confirm = JOptionPane.showConfirmDialog(this, "Bạn có chắc chắn muốn xóa cửa sổ bảo trì #" + id + "?", "Xác nhận xóa", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            maintenanceService.removeWindow(id);
            refreshData();
        }
    }

    private void showAddDialog() {
        JDialog addDialog = new JDialog(this, "Thêm Cửa Sổ Bảo Trì (Maintenance Window)", true);
        addDialog.setSize(UIScale.scale(480), UIScale.scale(380));
        addDialog.setLocationRelativeTo(this);
        addDialog.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(15), UIScale.scale(20), UIScale.scale(15), UIScale.scale(20)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(UIScale.scale(6), UIScale.scale(6), UIScale.scale(6), UIScale.scale(6));
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField txtName = new JTextField("Bảo trì hệ thống", 20);
        JComboBox<MaintenanceScope> cmbScope = new JComboBox<>(MaintenanceScope.values());
        JTextField txtScopeValue = new JTextField(15);
        txtScopeValue.setToolTipText("ID mục tiêu, tên nhóm (group), hoặc ID Agent");

        JRadioButton rdoRecurring = new JRadioButton("Lặp lại hàng tuần (Recurring)", true);
        JRadioButton rdoOneOff = new JRadioButton("Một lần duy nhất (One-off)");
        ButtonGroup bg = new ButtonGroup();
        bg.add(rdoRecurring);
        bg.add(rdoOneOff);

        JComboBox<DayOfWeek> cmbDay = new JComboBox<>(DayOfWeek.values());
        cmbDay.setSelectedItem(DayOfWeek.SUNDAY);

        JTextField txtStartTime = new JTextField("02:00", 6);
        JTextField txtEndTime = new JTextField("04:00", 6);

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
        form.add(new JLabel("Tên cửa sổ:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(txtName, gbc);

        r++;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
        form.add(new JLabel("Phạm vi bảo trì:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(cmbScope, gbc);

        r++;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
        form.add(new JLabel("Mục tiêu/Nhóm/Agent:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(txtScopeValue, gbc);

        r++;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
        form.add(new JLabel("Kiểu lịch biểu:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        JPanel rdoPnl = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rdoPnl.add(rdoRecurring);
        rdoPnl.add(Box.createHorizontalStrut(10));
        rdoPnl.add(rdoOneOff);
        form.add(rdoPnl, gbc);

        r++;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
        form.add(new JLabel("Thứ trong tuần:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(cmbDay, gbc);

        r++;
        gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
        form.add(new JLabel("Khung giờ (HH:mm):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        JPanel timePnl = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        timePnl.add(txtStartTime);
        timePnl.add(new JLabel("đến"));
        timePnl.add(txtEndTime);
        form.add(timePnl, gbc);

        addDialog.add(form, BorderLayout.CENTER);

        JPanel btnPnl = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnSave = new JButton("Lưu cấu hình", ThemeManager.getIcon("check", 14));
        btnSave.addActionListener(e -> {
            String name = txtName.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(addDialog, "Vui lòng nhập tên cửa sổ bảo trì.", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }

            try {
                if (rdoRecurring.isSelected()) {
                    LocalTime st = LocalTime.parse(txtStartTime.getText().trim());
                    LocalTime et = LocalTime.parse(txtEndTime.getText().trim());
                    MaintenanceWindow mw = new MaintenanceWindow(
                            0, name, (MaintenanceScope) cmbScope.getSelectedItem(),
                            txtScopeValue.getText().trim(),
                            (DayOfWeek) cmbDay.getSelectedItem(), st, et
                    );
                    maintenanceService.addWindow(mw);
                } else {
                    LocalDateTime now = LocalDateTime.now();
                    MaintenanceWindow mw = new MaintenanceWindow(
                            0, name, (MaintenanceScope) cmbScope.getSelectedItem(),
                            txtScopeValue.getText().trim(),
                            now, now.plusHours(2)
                    );
                    maintenanceService.addWindow(mw);
                }
                refreshData();
                addDialog.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(addDialog, "Định dạng thời gian không hợp lệ (Ví dụ: 02:00, 04:30): " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton btnCancel = new JButton("Hủy");
        btnCancel.addActionListener(e -> addDialog.dispose());

        btnPnl.add(btnSave);
        btnPnl.add(btnCancel);
        addDialog.add(btnPnl, BorderLayout.SOUTH);

        addDialog.setVisible(true);
    }
}

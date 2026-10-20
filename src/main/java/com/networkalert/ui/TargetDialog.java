package com.networkalert.ui;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;

import javax.swing.*;
import java.awt.*;

/**
 * Hộp thoại Thêm mới hoặc Sửa thông tin máy chủ giám sát
 */
public class TargetDialog extends JDialog {
    private final JTextField txtName = new JTextField(20);
    private final JTextField txtHost = new JTextField(20);
    private final JSpinner spinPort = new JSpinner(new SpinnerNumberModel(80, 0, 65535, 1));
    private final JComboBox<ProtocolType> cmbProtocol = new JComboBox<>(ProtocolType.values());
    private final JSpinner spinInterval = new JSpinner(new SpinnerNumberModel(5, 1, 3600, 1));
    private final JSpinner spinTimeout = new JSpinner(new SpinnerNumberModel(1500, 100, 30000, 100));
    private final JTextField txtKeyword = new JTextField(20);
    private final JTextField txtPushToken = new JTextField(20);
    private final JCheckBox chkEnabled = new JCheckBox("Kích hoạt giám sát mục tiêu này ngay", true);

    private boolean confirmed = false;
    private ServerTarget target;

    public TargetDialog(Frame parent, ServerTarget existingTarget) {
        super(parent, existingTarget == null ? "Thêm Máy Chủ Mới" : "Chỉnh Sửa Máy Chủ", true);
        this.target = existingTarget != null ? existingTarget : new ServerTarget();

        initUi();
        populateFields();
        pack();
        setLocationRelativeTo(parent);
        setResizable(false);
    }

    private void initUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        // Tên
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Tên đại diện:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(txtName, gbc);

        // Host / IP
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Địa chỉ Host / IP:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(txtHost, gbc);

        // Protocol
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Giao thức kiểm tra:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(cmbProtocol, gbc);

        // Port
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Cổng dịch vụ (Port):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(spinPort, gbc);

        // Chu kỳ
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Chu kỳ kiểm tra (giây):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(spinInterval, gbc);

        // Timeout
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Thời gian chờ Timeout (ms):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(spinTimeout, gbc);

        // Từ khóa HTTP mong đợi
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Từ khóa phản hồi HTTP:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        txtKeyword.setToolTipText("Kiểm tra nội dung body của HTTP/HTTPS có chứa từ khóa này (Keyword matching)");
        form.add(txtKeyword, gbc);

        // Push Token
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Push Monitor Token:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        txtPushToken.setToolTipText("Mã token dùng cho POST /api/push/{token} (để trống sẽ tự sinh)");
        form.add(txtPushToken, gbc);

        // Trạng thái
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        form.add(chkEnabled, gbc);

        // Tự động gợi ý cổng khi chọn giao thức
        cmbProtocol.addActionListener(e -> {
            ProtocolType p = (ProtocolType) cmbProtocol.getSelectedItem();
            if (p != null) {
                switch (p) {
                    case HTTP: spinPort.setValue(80); break;
                    case HTTPS: spinPort.setValue(443); break;
                    case DNS: spinPort.setValue(53); break;
                    case ICMP: spinPort.setValue(0); break;
                    case TCP:
                        if ((int) spinPort.getValue() == 80 || (int) spinPort.getValue() == 0) {
                            spinPort.setValue(9000);
                        }
                        break;
                    case UDP:
                        if ((int) spinPort.getValue() == 80 || (int) spinPort.getValue() == 0) {
                            spinPort.setValue(9001);
                        }
                        break;
                }
            }
        });

        // Nút bấm
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton btnCancel = new JButton("Hủy bỏ");
        btnCancel.addActionListener(e -> dispose());

        JButton btnSave = new JButton("Lưu thay đổi");
        btnSave.setFont(btnSave.getFont().deriveFont(Font.BOLD));
        btnSave.addActionListener(e -> validateAndSave());

        buttonPanel.add(btnCancel);
        buttonPanel.add(btnSave);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);
    }

    private void populateFields() {
        if (target != null && target.getName() != null) {
            txtName.setText(target.getName());
            txtHost.setText(target.getHost());
            spinPort.setValue(target.getPort());
            cmbProtocol.setSelectedItem(target.getProtocol());
            spinInterval.setValue(target.getIntervalSeconds());
            spinTimeout.setValue(target.getTimeoutMs());
            txtKeyword.setText(target.getExpectedKeyword() != null ? target.getExpectedKeyword() : "");
            txtPushToken.setText(target.getPushToken() != null ? target.getPushToken() : "");
            chkEnabled.setSelected(target.isEnabled());
        }
    }

    private void validateAndSave() {
        String name = txtName.getText().trim();
        String host = txtHost.getText().trim();

        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên đại diện cho máy chủ!", "Lỗi nhập liệu", JOptionPane.ERROR_MESSAGE);
            txtName.requestFocus();
            return;
        }

        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập địa chỉ Host hoặc IP!", "Lỗi nhập liệu", JOptionPane.ERROR_MESSAGE);
            txtHost.requestFocus();
            return;
        }

        target.setName(name);
        target.setHost(host);
        target.setPort((Integer) spinPort.getValue());
        target.setProtocol((ProtocolType) cmbProtocol.getSelectedItem());
        target.setIntervalSeconds((Integer) spinInterval.getValue());
        target.setTimeoutMs((Integer) spinTimeout.getValue());

        String kw = txtKeyword.getText().trim();
        target.setExpectedKeyword(kw.isEmpty() ? null : kw);

        String pt = txtPushToken.getText().trim();
        if (target.getProtocol() == ProtocolType.PUSH && pt.isEmpty()) {
            target.setPushToken(com.networkalert.service.PushMonitorService.getInstance().generatePushToken());
        } else {
            target.setPushToken(pt.isEmpty() ? null : pt);
        }

        target.setEnabled(chkEnabled.isSelected());

        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public ServerTarget getTarget() {
        return target;
    }
}

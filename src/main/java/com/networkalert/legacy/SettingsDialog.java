package com.networkalert.legacy;

import com.networkalert.model.AlertConfig;
import com.networkalert.model.DatabaseConfig;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.service.AlertService;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;
import java.awt.*;

/**
 * Hộp thoại cấu hình hệ thống: Cảnh báo, Email SMTP, Telegram Bot, Khay hệ thống, và SQL Server
 */
public class SettingsDialog extends JDialog {
    private final AlertService alertService;
    private final DatabaseManager dbManager;

    // Tab Cảnh báo cơ bản & Tray
    private final JCheckBox chkPopup = new JCheckBox("Bật thông báo Popup Toast góc màn hình");
    private final JCheckBox chkSound = new JCheckBox("Bật âm thanh cảnh báo (Beep Alert)");
    private final JCheckBox chkMinimizeToTray = new JCheckBox("Thu nhỏ xuống Khay hệ thống (System Tray) khi bấm nút đóng (X)");
    private final JSpinner spinLatencyThreshold = new JSpinner(new SpinnerNumberModel(300, 10, 5000, 10));

    // Tab Telegram Bot (Thực tế)
    private final JCheckBox chkTelegram = new JCheckBox("Kích hoạt cảnh báo đẩy qua Telegram Bot API (Mobile 24/7)");
    private final JTextField txtTelegramToken = new JTextField(20);
    private final JTextField txtTelegramChatId = new JTextField(20);
    private final JLabel lblTelegramGuide = new JLabel("<html><body style='width: 380px; font-size: 11px; color: gray;'>" +
            "💡 Hướng dẫn tạo Bot: Chat với <b>@BotFather</b> trên Telegram để lấy <code>Bot Token</code>.<br>" +
            "Thêm bot vào nhóm hoặc chat với <b>@userinfobot</b> để lấy <code>Chat ID</code>." +
            "</body></html>");

    // Tab Email SMTP
    private final JCheckBox chkEmail = new JCheckBox("Bật gửi Email cảnh báo qua SMTP");
    private final JTextField txtSmtpHost = new JTextField(15);
    private final JSpinner spinSmtpPort = new JSpinner(new SpinnerNumberModel(587, 1, 65535, 1));
    private final JTextField txtSmtpUser = new JTextField(15);
    private final JPasswordField txtSmtpPass = new JPasswordField(15);
    private final JTextField txtSmtpFrom = new JTextField(15);
    private final JTextField txtSmtpTo = new JTextField(15);

    // Tab SQL Server
    private final JTextField txtDbServer = new JTextField(15);
    private final JTextField txtDbInstance = new JTextField(15);
    private final JTextField txtDbName = new JTextField(15);
    private final JCheckBox chkIntegratedAuth = new JCheckBox("Windows Authentication (integratedSecurity)");
    private final JTextField txtDbUser = new JTextField(15);
    private final JPasswordField txtDbPass = new JPasswordField(15);
    private final JLabel lblDbStatus = new JLabel("Trạng thái: Chưa kiểm tra");

    public SettingsDialog(Frame parent, AlertService alertService) {
        super(parent, "Cấu Hình Hệ Thống & Cảnh Báo Sản Xuất", true);
        this.alertService = alertService;
        this.dbManager = DatabaseManager.getInstance();

        initUi();
        loadCurrentSettings();
        pack();
        setLocationRelativeTo(parent);
        setResizable(false);
    }

    private void initUi() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Cảnh Báo & Khay Hệ Thống", createGeneralTab());
        tabbedPane.addTab("📱 Telegram Bot (Mobile)", createTelegramTab());
        tabbedPane.addTab("✉ Email SMTP", createEmailTab());
        tabbedPane.addTab("🗄 SQL Server", createDatabaseTab());

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton btnClose = new JButton("Đóng");
        btnClose.addActionListener(e -> dispose());

        JButton btnSave = new JButton("Lưu Cấu Hình");
        btnSave.setFont(btnSave.getFont().deriveFont(Font.BOLD));
        btnSave.addActionListener(e -> saveSettings());

        bottomPanel.add(btnClose);
        bottomPanel.add(btnSave);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabbedPane, BorderLayout.CENTER);
        getContentPane().add(bottomPanel, BorderLayout.SOUTH);
    }

    private JPanel createGeneralTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        panel.add(chkPopup, gbc);

        row++;
        gbc.gridy = row;
        panel.add(chkSound, gbc);

        row++;
        gbc.gridy = row;
        panel.add(chkMinimizeToTray, gbc);

        row++;
        gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JLabel("Ngưỡng cảnh báo trễ mạng (ms):"), gbc);
        gbc.gridx = 1;
        panel.add(spinLatencyThreshold, gbc);

        return panel;
    }

    private JPanel createTelegramTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        panel.add(chkTelegram, gbc);

        row++;
        gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JLabel("Telegram Bot Token:"), gbc);
        gbc.gridx = 1;
        panel.add(txtTelegramToken, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Telegram Chat ID / Group ID:"), gbc);
        gbc.gridx = 1;
        panel.add(txtTelegramChatId, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        panel.add(lblTelegramGuide, gbc);

        row++;
        gbc.gridy = row;
        JButton btnTestTelegram = new JButton("Gửi Thử Tin Nhắn Cảnh Báo Telegram");
        btnTestTelegram.addActionListener(e -> testTelegram());
        panel.add(btnTestTelegram, gbc);

        return panel;
    }

    private JPanel createEmailTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        panel.add(chkEmail, gbc);

        row++;
        gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JLabel("SMTP Host:"), gbc);
        gbc.gridx = 1;
        panel.add(txtSmtpHost, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("SMTP Port:"), gbc);
        gbc.gridx = 1;
        panel.add(spinSmtpPort, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Tài khoản SMTP:"), gbc);
        gbc.gridx = 1;
        panel.add(txtSmtpUser, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Mật khẩu / App Password:"), gbc);
        gbc.gridx = 1;
        panel.add(txtSmtpPass, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Email gửi (From):"), gbc);
        gbc.gridx = 1;
        panel.add(txtSmtpFrom, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Email nhận cảnh báo (To):"), gbc);
        gbc.gridx = 1;
        panel.add(txtSmtpTo, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        JButton btnTestEmail = new JButton("Gửi Thử Email Cảnh Báo (Test SMTP)");
        btnTestEmail.addActionListener(e -> testEmail());
        panel.add(btnTestEmail, gbc);

        return panel;
    }

    private JPanel createDatabaseTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("SQL Server Host:"), gbc);
        gbc.gridx = 1;
        panel.add(txtDbServer, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Instance Name:"), gbc);
        gbc.gridx = 1;
        panel.add(txtDbInstance, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Database Name:"), gbc);
        gbc.gridx = 1;
        panel.add(txtDbName, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        panel.add(chkIntegratedAuth, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JLabel("SQL Username:"), gbc);
        gbc.gridx = 1;
        panel.add(txtDbUser, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("SQL Password:"), gbc);
        gbc.gridx = 1;
        panel.add(txtDbPass, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        panel.add(lblDbStatus, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        JButton btnTestDb = new JButton("Kiểm Tra Kết Nối & Khởi Tạo Bảng SQL Server");
        btnTestDb.addActionListener(e -> testDatabase());
        panel.add(btnTestDb, gbc);

        chkIntegratedAuth.addActionListener(e -> {
            boolean isWinAuth = chkIntegratedAuth.isSelected();
            txtDbUser.setEnabled(!isWinAuth);
            txtDbPass.setEnabled(!isWinAuth);
        });

        return panel;
    }

    private void loadCurrentSettings() {
        AlertConfig ac = alertService.getConfig();
        chkPopup.setSelected(ac.isPopupEnabled());
        chkSound.setSelected(ac.isSoundEnabled());
        chkMinimizeToTray.setSelected(ac.isMinimizeToTrayOnClose());
        spinLatencyThreshold.setValue((int) ac.getLatencyThresholdMs());

        chkTelegram.setSelected(ac.isTelegramEnabled());
        txtTelegramToken.setText(ac.getTelegramBotToken());
        txtTelegramChatId.setText(ac.getTelegramChatId());

        chkEmail.setSelected(ac.isEmailEnabled());
        txtSmtpHost.setText(ac.getSmtpHost());
        spinSmtpPort.setValue(ac.getSmtpPort());
        txtSmtpUser.setText(ac.getSmtpUsername());
        txtSmtpPass.setText(ac.getSmtpPassword());
        txtSmtpFrom.setText(ac.getSmtpFrom());
        txtSmtpTo.setText(ac.getSmtpTo());

        DatabaseConfig dc = dbManager.getConfig();
        txtDbServer.setText(dc.getServer());
        txtDbInstance.setText(dc.getInstanceName());
        txtDbName.setText(dc.getDatabaseName());
        chkIntegratedAuth.setSelected(dc.isUseIntegratedSecurity());
        txtDbUser.setText(dc.getUsername());
        txtDbPass.setText(dc.getPassword());
        txtDbUser.setEnabled(!dc.isUseIntegratedSecurity());
        txtDbPass.setEnabled(!dc.isUseIntegratedSecurity());

        if (dbManager.isConnected()) {
            lblDbStatus.setText("Trạng thái: ĐÃ KẾT NỐI THÀNH CÔNG");
            lblDbStatus.setForeground(new Color(46, 204, 113));
        } else {
            lblDbStatus.setText("Trạng thái: CHƯA KẾT NỐI (Đang dùng Fallback)");
            lblDbStatus.setForeground(new Color(231, 76, 60));
        }
    }

    private void saveSettings() {
        AlertConfig ac = new AlertConfig();
        ac.setPopupEnabled(chkPopup.isSelected());
        ac.setSoundEnabled(chkSound.isSelected());
        ac.setMinimizeToTrayOnClose(chkMinimizeToTray.isSelected());
        ac.setLatencyThresholdMs((Integer) spinLatencyThreshold.getValue());

        ac.setTelegramEnabled(chkTelegram.isSelected());
        ac.setTelegramBotToken(txtTelegramToken.getText().trim());
        ac.setTelegramChatId(txtTelegramChatId.getText().trim());

        ac.setEmailEnabled(chkEmail.isSelected());
        ac.setSmtpHost(txtSmtpHost.getText().trim());
        ac.setSmtpPort((Integer) spinSmtpPort.getValue());
        ac.setSmtpUsername(txtSmtpUser.getText().trim());
        ac.setSmtpPassword(new String(txtSmtpPass.getPassword()).trim());
        ac.setSmtpFrom(txtSmtpFrom.getText().trim());
        ac.setSmtpTo(txtSmtpTo.getText().trim());

        alertService.saveConfig(ac);

        DatabaseConfig dc = dbManager.getConfig();
        dc.setServer(txtDbServer.getText().trim());
        dc.setInstanceName(txtDbInstance.getText().trim());
        dc.setDatabaseName(txtDbName.getText().trim());
        dc.setUseIntegratedSecurity(chkIntegratedAuth.isSelected());
        dc.setUsername(txtDbUser.getText().trim());
        dc.setPassword(new String(txtDbPass.getPassword()).trim());

        JOptionPane.showMessageDialog(this, "Đã lưu toàn bộ cấu hình thành công!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private void testTelegram() {
        String token = txtTelegramToken.getText().trim();
        String chatId = txtTelegramChatId.getText().trim();

        if (token.isEmpty() || chatId.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập đầy đủ Bot Token và Chat ID!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            try {
                alertService.getTelegramService().testTelegramConnection(token, chatId);
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    JOptionPane.showMessageDialog(this, "Gửi tin nhắn Telegram thành công! Vui lòng kiểm tra điện thoại.", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    JOptionPane.showMessageDialog(this, "Lỗi kết nối Telegram: " + ex.getMessage(), "Thất bại", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void testEmail() {
        AlertConfig testConfig = new AlertConfig();
        testConfig.setSmtpHost(txtSmtpHost.getText().trim());
        testConfig.setSmtpPort((Integer) spinSmtpPort.getValue());
        testConfig.setSmtpUsername(txtSmtpUser.getText().trim());
        testConfig.setSmtpPassword(new String(txtSmtpPass.getPassword()).trim());
        testConfig.setSmtpFrom(txtSmtpFrom.getText().trim());
        testConfig.setSmtpTo(txtSmtpTo.getText().trim());

        if (testConfig.getSmtpTo().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập email người nhận (To) để thử nghiệm!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            try {
                alertService.getEmailService().testSmtpConnection(testConfig);
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    JOptionPane.showMessageDialog(this, "Gửi email thử nghiệm thành công! Vui lòng kiểm tra hộp thư đến.", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    JOptionPane.showMessageDialog(this, "Lỗi gửi email: " + ex.getMessage(), "Thất bại", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void testDatabase() {
        DatabaseConfig dc = new DatabaseConfig();
        dc.setServer(txtDbServer.getText().trim());
        dc.setInstanceName(txtDbInstance.getText().trim());
        dc.setDatabaseName(txtDbName.getText().trim());
        dc.setUseIntegratedSecurity(chkIntegratedAuth.isSelected());
        dc.setUsername(txtDbUser.getText().trim());
        dc.setPassword(new String(txtDbPass.getPassword()).trim());

        dbManager.setConfig(dc);

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            boolean ok = dbManager.testConnection();
            if (ok) {
                dbManager.initializeTables();
            }
            SwingUtilities.invokeLater(() -> {
                setCursor(Cursor.getDefaultCursor());
                if (ok) {
                    lblDbStatus.setText("Trạng thái: ĐÃ KẾT NỐI THÀNH CÔNG");
                    lblDbStatus.setForeground(new Color(46, 204, 113));
                    JOptionPane.showMessageDialog(this, "Kết nối Microsoft SQL Server thành công và các bảng đã sẵn sàng!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    lblDbStatus.setText("Trạng thái: LỖI KẾT NỐI");
                    lblDbStatus.setForeground(new Color(231, 76, 60));
                    JOptionPane.showMessageDialog(this, "Không thể kết nối SQL Server:\n" + dbManager.getLastError(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                }
            });
        }).start();
    }
}

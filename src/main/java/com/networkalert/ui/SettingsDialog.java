package com.networkalert.ui;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.AlertConfig;
import com.networkalert.model.DatabaseConfig;
import com.networkalert.repository.DatabaseManager;
import com.networkalert.service.AlertService;
import com.networkalert.ui.components.ToggleSwitch;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Hộp thoại Cấu hình cảnh báo & CSDL chuẩn thiết kế UI_REDESIGN.md
 * Bao gồm 3 mục: "Ngưỡng và chu kỳ", "Thông báo", "Cơ sở dữ liệu"
 */
public class SettingsDialog extends JDialog {
    private final AlertService alertService;
    private final DatabaseManager dbManager;

    // Mục 1: Ngưỡng và chu kỳ
    private final JSpinner spinLatencyThreshold = new JSpinner(new SpinnerNumberModel(300, 10, 5000, 10));
    private final JSpinner spinTimeout = new JSpinner(new SpinnerNumberModel(1500, 100, 10000, 100));
    private final JSpinner spinMaxRetries = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
    private final JSpinner spinInterval = new JSpinner(new SpinnerNumberModel(5, 1, 300, 1));

    // Cấu hình Web API & Dashboard
    private final ToggleSwitch swWebLocalhostOnly = new ToggleSwitch(false);
    private final JTextField txtApiToken = new JTextField(20);

    // Mục 2: Thông báo
    private final ToggleSwitch swPopup = new ToggleSwitch(true);
    private final ToggleSwitch swSound = new ToggleSwitch(true);
    private final ToggleSwitch swMinimizeToTray = new ToggleSwitch(true);

    private final ToggleSwitch swEmail = new ToggleSwitch(false);
    private final JTextField txtSmtpHost = new JTextField(15);
    private final JSpinner spinSmtpPort = new JSpinner(new SpinnerNumberModel(587, 1, 65535, 1));
    private final JTextField txtSmtpUser = new JTextField(15);
    private final JPasswordField txtSmtpPass = new JPasswordField(15);
    private final JTextField txtSmtpFrom = new JTextField(15);
    private final JTextField txtSmtpTo = new JTextField(15);

    private final ToggleSwitch swTelegram = new ToggleSwitch(false);
    private final JTextField txtTelegramToken = new JTextField(18);
    private final JTextField txtTelegramChatId = new JTextField(18);

    // Mục 3: Cơ sở dữ liệu
    private final JTextField txtDbServer = new JTextField(15);
    private final JTextField txtDbInstance = new JTextField(15);
    private final JTextField txtDbName = new JTextField(15);
    private final ToggleSwitch swIntegratedAuth = new ToggleSwitch(false);
    private final JTextField txtDbUser = new JTextField(15);
    private final JPasswordField txtDbPass = new JPasswordField(15);
    private final JLabel lblDbStatus = new JLabel("Trạng thái: Chưa kiểm tra");

    public SettingsDialog(Frame parent, AlertService alertService) {
        super(parent, I18n.get("dialog.settings.title"), true);
        this.alertService = alertService;
        this.dbManager = DatabaseManager.getInstance();

        setSize(UIScale.scale(540), UIScale.scale(600));
        setResizable(false);
        setLocationRelativeTo(parent);

        initUi();
        loadSettings();
    }

    private void initUi() {
        JPanel contentPane = new JPanel(new BorderLayout(0, 16));
        contentPane.setBackground(ThemeManager.bgSurface());
        contentPane.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        setContentPane(contentPane);

        // Header Title
        JLabel lblTitle = new JLabel(getTitle());
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 16));
        lblTitle.setForeground(ThemeManager.textPrimary());
        contentPane.add(lblTitle, BorderLayout.NORTH);

        // Tabs
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(ThemeManager.sans(Font.BOLD, 12));
        tabbedPane.putClientProperty("JTabbedPane.tabHeight", UIScale.scale(36));

        tabbedPane.addTab(I18n.get("dialog.settings.tab_threshold"), createThresholdTab());
        tabbedPane.addTab(I18n.get("dialog.settings.tab_notification"), createNotificationTab());
        tabbedPane.addTab(I18n.get("dialog.settings.tab_database"), createDatabaseTab());

        contentPane.add(tabbedPane, BorderLayout.CENTER);

        // Bottom Actions
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bottomBar.setOpaque(false);

        JButton btnCancel = new JButton(I18n.get("dialog.target.cancel"));
        btnCancel.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnCancel.putClientProperty("JButton.buttonType", "toolBarButton");
        btnCancel.addActionListener(e -> dispose());

        JButton btnSave = new JButton(I18n.get("dialog.settings.save"));
        btnSave.setFont(ThemeManager.sans(Font.BOLD, 12));
        btnSave.setBackground(ThemeManager.accentFill());
        btnSave.setForeground(Color.WHITE);
        btnSave.setPreferredSize(new Dimension(UIScale.scale(120), UIScale.scale(32)));
        btnSave.addActionListener(e -> onSave());

        bottomBar.add(btnCancel);
        bottomBar.add(btnSave);
        contentPane.add(bottomBar, BorderLayout.SOUTH);

        // Key bindings
        JRootPane root = getRootPane();
        root.setDefaultButton(btnSave);
        root.registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private JPanel createThresholdTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 0, 6, 12);
        gbc.weightx = 1.0;

        int r = 0;
        addLabelAndComponent(p, gbc, r++, "Ngưỡng trễ cảnh báo (ms):", spinLatencyThreshold);
        addLabelAndComponent(p, gbc, r++, "Thời gian timeout mặc định (ms):", spinTimeout);
        addLabelAndComponent(p, gbc, r++, "Số lần thử lại trước khi báo Offline:", spinMaxRetries);
        addLabelAndComponent(p, gbc, r++, "Chu kỳ kiểm tra mặc định (giây):", spinInterval);

        gbc.gridx = 0; gbc.gridy = r++; gbc.gridwidth = 2;
        gbc.insets = new Insets(12, 0, 6, 12);
        JSeparator sep = new JSeparator();
        p.add(sep, gbc);

        gbc.insets = new Insets(4, 0, 4, 12);
        p.add(createSwitchRow("Chỉ cho phép Web Dashboard từ Localhost (127.0.0.1)", swWebLocalhostOnly), gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = r;
        JLabel lblToken = new JLabel("Web REST API Token:");
        lblToken.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblToken.setForeground(ThemeManager.textPrimary());
        p.add(lblToken, gbc);

        gbc.gridx = 1;
        JPanel tokenPanel = new JPanel(new BorderLayout(6, 0));
        tokenPanel.setOpaque(false);
        txtApiToken.setEditable(false);
        txtApiToken.setFont(ThemeManager.sans(Font.PLAIN, 11));
        tokenPanel.add(txtApiToken, BorderLayout.CENTER);

        JButton btnCopy = new JButton("Chép");
        btnCopy.setToolTipText("Sao chép Token vào Clipboard");
        btnCopy.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btnCopy.addActionListener(e -> {
            String val = txtApiToken.getText().trim();
            if (!val.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(val), null);
                JOptionPane.showMessageDialog(this, "Đã sao chép API Token vào Clipboard!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        tokenPanel.add(btnCopy, BorderLayout.EAST);
        p.add(tokenPanel, gbc);

        return p;
    }

    private JPanel createNotificationTab() {
        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setOpaque(false);
        main.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));

        // Switches
        main.add(createSwitchRow("Thông báo Toast góc màn hình", swPopup));
        main.add(createSwitchRow("Âm thanh cảnh báo (Beep Alert)", swSound));
        main.add(createSwitchRow("Thu nhỏ xuống khay hệ thống khi đóng (X)", swMinimizeToTray));
        main.add(Box.createVerticalStrut(UIScale.scale(10)));

        // SMTP Email
        JPanel pnlEmail = new JPanel(new GridBagLayout());
        pnlEmail.setBorder(BorderFactory.createTitledBorder("Email SMTP"));
        pnlEmail.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);

        int r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.gridwidth = 2;
        pnlEmail.add(createSwitchRow("Bật gửi Email SMTP", swEmail), gbc);

        gbc.gridwidth = 1;
        r++;
        addGridRow(pnlEmail, gbc, r++, "SMTP Host:", txtSmtpHost);
        addGridRow(pnlEmail, gbc, r++, "SMTP Port:", spinSmtpPort);
        addGridRow(pnlEmail, gbc, r++, "Tài khoản:", txtSmtpUser);
        addGridRow(pnlEmail, gbc, r++, "Mật khẩu:", txtSmtpPass);
        addGridRow(pnlEmail, gbc, r++, "Gửi từ (From):", txtSmtpFrom);
        addGridRow(pnlEmail, gbc, r++, "Gửi tới (To):", txtSmtpTo);

        gbc.gridx = 0; gbc.gridy = r++; gbc.gridwidth = 2;
        JButton btnTestEmail = new JButton("Kiểm tra gửi Email", ThemeManager.getIcon("zap", 16));
        btnTestEmail.addActionListener(e -> onTestEmail());
        pnlEmail.add(btnTestEmail, gbc);

        main.add(pnlEmail);
        main.add(Box.createVerticalStrut(UIScale.scale(10)));

        // Telegram Bot
        JPanel pnlTele = new JPanel(new GridBagLayout());
        pnlTele.setBorder(BorderFactory.createTitledBorder("Telegram Bot"));
        pnlTele.setOpaque(false);

        r = 0;
        gbc.gridx = 0; gbc.gridy = r; gbc.gridwidth = 2;
        pnlTele.add(createSwitchRow("Bật thông báo Telegram", swTelegram), gbc);

        gbc.gridwidth = 1;
        r++;
        addGridRow(pnlTele, gbc, r++, "Bot Token:", txtTelegramToken);
        addGridRow(pnlTele, gbc, r++, "Chat ID:", txtTelegramChatId);

        gbc.gridx = 0; gbc.gridy = r++; gbc.gridwidth = 2;
        JButton btnTestTelegram = new JButton("Kiểm tra gửi Telegram", ThemeManager.getIcon("zap", 16));
        btnTestTelegram.addActionListener(e -> onTestTelegram());
        pnlTele.add(btnTestTelegram, gbc);

        main.add(pnlTele);

        JScrollPane sp = new JScrollPane(main);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(sp, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel createDatabaseTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 4, 6, 4);

        int r = 0;
        addGridRow(p, gbc, r++, "SQL Server Host:", txtDbServer);
        addGridRow(p, gbc, r++, "Instance Name:", txtDbInstance);
        addGridRow(p, gbc, r++, "Database Name:", txtDbName);

        gbc.gridx = 0; gbc.gridy = r++; gbc.gridwidth = 2;
        p.add(createSwitchRow("Windows Authentication (integratedSecurity)", swIntegratedAuth), gbc);

        gbc.gridwidth = 1;
        addGridRow(p, gbc, r++, "Username:", txtDbUser);
        addGridRow(p, gbc, r++, "Password:", txtDbPass);

        gbc.gridx = 0; gbc.gridy = r++; gbc.gridwidth = 2;
        lblDbStatus.setFont(ThemeManager.sans(Font.BOLD, 12));
        p.add(lblDbStatus, gbc);

        gbc.gridy = r++;
        JButton btnTestDb = new JButton(I18n.get("dialog.settings.test_db"), ThemeManager.getIcon("zap", 16));
        btnTestDb.addActionListener(e -> onTestDatabase());
        p.add(btnTestDb, gbc);

        swIntegratedAuth.setOnToggle(winAuth -> {
            txtDbUser.setEnabled(!winAuth);
            txtDbPass.setEnabled(!winAuth);
        });

        return p;
    }

    private void addLabelAndComponent(JPanel p, GridBagConstraints gbc, int row, String label, JComponent comp) {
        gbc.gridx = 0; gbc.gridy = row;
        JLabel lbl = new JLabel(label);
        lbl.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lbl.setForeground(ThemeManager.textPrimary());
        p.add(lbl, gbc);

        gbc.gridx = 1;
        comp.setPreferredSize(new Dimension(UIScale.scale(120), UIScale.scale(30)));
        p.add(comp, gbc);
    }

    private void addGridRow(JPanel p, GridBagConstraints gbc, int row, String label, JComponent comp) {
        gbc.gridx = 0; gbc.gridy = row;
        JLabel lbl = new JLabel(label);
        lbl.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lbl.setForeground(ThemeManager.textSecondary());
        p.add(lbl, gbc);

        gbc.gridx = 1;
        comp.setPreferredSize(new Dimension(UIScale.scale(180), UIScale.scale(30)));
        p.add(comp, gbc);
    }

    private JPanel createSwitchRow(String text, ToggleSwitch sw) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        r.setOpaque(false);
        r.add(sw);
        JLabel lbl = new JLabel(text);
        lbl.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lbl.setForeground(ThemeManager.textPrimary());
        r.add(lbl);
        return r;
    }

    private void loadSettings() {
        AlertConfig ac = alertService.getConfig();
        swPopup.setSelectedWithoutAnimation(ac.isPopupEnabled());
        swSound.setSelectedWithoutAnimation(ac.isSoundEnabled());
        swMinimizeToTray.setSelectedWithoutAnimation(ac.isMinimizeToTrayOnClose());
        spinLatencyThreshold.setValue((int) ac.getLatencyThresholdMs());
        spinMaxRetries.setValue(ac.getMaxRetries());
        swWebLocalhostOnly.setSelectedWithoutAnimation(ac.isWebDashboardLocalhostOnly());
        txtApiToken.setText(ac.getApiToken() != null ? ac.getApiToken() : "");

        swEmail.setSelectedWithoutAnimation(ac.isEmailEnabled());
        txtSmtpHost.setText(ac.getSmtpHost());
        spinSmtpPort.setValue(ac.getSmtpPort());
        txtSmtpUser.setText(ac.getSmtpUsername());
        txtSmtpPass.setText(ac.getSmtpPassword());
        txtSmtpFrom.setText(ac.getSmtpFrom());
        txtSmtpTo.setText(ac.getSmtpTo());

        swTelegram.setSelectedWithoutAnimation(ac.isTelegramEnabled());
        txtTelegramToken.setText(ac.getTelegramBotToken());
        txtTelegramChatId.setText(ac.getTelegramChatId());

        DatabaseConfig dc = dbManager.getConfig();
        txtDbServer.setText(dc.getServer());
        txtDbInstance.setText(dc.getInstanceName());
        txtDbName.setText(dc.getDatabaseName());
        swIntegratedAuth.setSelectedWithoutAnimation(dc.isUseIntegratedSecurity());
        txtDbUser.setText(dc.getUsername());
        txtDbPass.setText(dc.getPassword());
        txtDbUser.setEnabled(!dc.isUseIntegratedSecurity());
        txtDbPass.setEnabled(!dc.isUseIntegratedSecurity());

        updateDbStatusLabel(dbManager.isConnected());
    }

    private void updateDbStatusLabel(boolean connected) {
        if (connected) {
            lblDbStatus.setText("● CSDL: Đã kết nối thành công");
            lblDbStatus.setForeground(ThemeManager.statusOnline());
        } else {
            lblDbStatus.setText("● CSDL: Mất kết nối (Đang dùng Fallback)");
            lblDbStatus.setForeground(ThemeManager.statusOffline());
        }
    }

    private void onTestDatabase() {
        DatabaseConfig dc = new DatabaseConfig();
        dc.setServer(txtDbServer.getText().trim());
        dc.setInstanceName(txtDbInstance.getText().trim());
        dc.setDatabaseName(txtDbName.getText().trim());
        dc.setUseIntegratedSecurity(swIntegratedAuth.isSelected());
        dc.setUsername(txtDbUser.getText().trim());
        dc.setPassword(new String(txtDbPass.getPassword()).trim());

        dbManager.setConfig(dc);

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            boolean ok = dbManager.testConnection();
            if (ok) dbManager.initializeTables();
            SwingUtilities.invokeLater(() -> {
                setCursor(Cursor.getDefaultCursor());
                updateDbStatusLabel(ok);
                if (ok) {
                    JOptionPane.showMessageDialog(this, "Kết nối SQL Server thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    String err = dbManager.getLastError();
                    if (err != null && err.contains("integrated authentication")) {
                        JOptionPane.showMessageDialog(this,
                                "Windows Authentication yêu cầu tệp thư viện gốc 'mssql-jdbc_auth.dll' của Microsoft.\n\n" +
                                "👉 Cách kết nối dễ nhất:\n" +
                                "1. Gạt TẮT công tắc 'Windows Authentication'.\n" +
                                "2. Nhập Username (ví dụ: 'sa') và Mật khẩu SQL Server của bạn.\n" +
                                "3. Bấm 'Kiểm tra kết nối'.",
                                "Hướng dẫn kết nối CSDL", JOptionPane.WARNING_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(this, "Không thể kết nối SQL Server:\n" + err, "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });
        }).start();
    }

    private void onTestEmail() {
        String host = txtSmtpHost.getText().trim();
        String user = txtSmtpUser.getText().trim();
        String pass = new String(txtSmtpPass.getPassword()).trim();
        String to = txtSmtpTo.getText().trim();
        String from = txtSmtpFrom.getText().trim();
        int port = (Integer) spinSmtpPort.getValue();

        if (host.isEmpty() || user.isEmpty() || to.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập đầy đủ SMTP Host, Tài khoản và Gửi tới (To)!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AlertConfig testConfig = new AlertConfig();
        testConfig.setEmailEnabled(true);
        testConfig.setSmtpHost(host);
        testConfig.setSmtpPort(port);
        testConfig.setSmtpUsername(user);
        testConfig.setSmtpPassword(pass);
        testConfig.setSmtpFrom(from.isEmpty() ? user : from);
        testConfig.setSmtpTo(to);

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            try {
                boolean ok = alertService.getEmailService().testSmtpConnection(testConfig);
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    if (ok) {
                        JOptionPane.showMessageDialog(this, "Gửi email kiểm tra thành công tới " + to + "!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(this, "Không thể gửi email kiểm tra. Vui lòng kiểm tra lại cấu hình SMTP!", "Thất bại", JOptionPane.ERROR_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    JOptionPane.showMessageDialog(this, "Lỗi gửi email kiểm tra:\n" + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void onTestTelegram() {
        String token = txtTelegramToken.getText().trim();
        String chatId = txtTelegramChatId.getText().trim();

        if (token.isEmpty() || chatId.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Bot Token và Chat ID của Telegram!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            try {
                boolean ok = alertService.getTelegramService().testTelegramConnection(token, chatId);
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    if (ok) {
                        JOptionPane.showMessageDialog(this, "Đã gửi tin nhắn kiểm tra thành công tới Telegram Chat ID: " + chatId + "!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(this, "Gửi tin nhắn Telegram thất bại! Vui lòng kiểm tra lại Bot Token và Chat ID.", "Thất bại", JOptionPane.ERROR_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    JOptionPane.showMessageDialog(this, "Lỗi kiểm tra kết nối Telegram:\n" + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void onSave() {
        AlertConfig ac = new AlertConfig();
        ac.setPopupEnabled(swPopup.isSelected());
        ac.setSoundEnabled(swSound.isSelected());
        ac.setMinimizeToTrayOnClose(swMinimizeToTray.isSelected());
        ac.setLatencyThresholdMs((Integer) spinLatencyThreshold.getValue());
        ac.setMaxRetries((Integer) spinMaxRetries.getValue());
        ac.setWebDashboardLocalhostOnly(swWebLocalhostOnly.isSelected());
        ac.setApiToken(txtApiToken.getText().trim());

        ac.setEmailEnabled(swEmail.isSelected());
        ac.setSmtpHost(txtSmtpHost.getText().trim());
        ac.setSmtpPort((Integer) spinSmtpPort.getValue());
        ac.setSmtpUsername(txtSmtpUser.getText().trim());
        ac.setSmtpPassword(new String(txtSmtpPass.getPassword()).trim());
        ac.setSmtpFrom(txtSmtpFrom.getText().trim());
        ac.setSmtpTo(txtSmtpTo.getText().trim());

        ac.setTelegramEnabled(swTelegram.isSelected());
        ac.setTelegramBotToken(txtTelegramToken.getText().trim());
        ac.setTelegramChatId(txtTelegramChatId.getText().trim());

        alertService.saveConfig(ac);

        DatabaseConfig dc = dbManager.getConfig();
        dc.setServer(txtDbServer.getText().trim());
        dc.setInstanceName(txtDbInstance.getText().trim());
        dc.setDatabaseName(txtDbName.getText().trim());
        dc.setUseIntegratedSecurity(swIntegratedAuth.isSelected());
        dc.setUsername(txtDbUser.getText().trim());
        dc.setPassword(new String(txtDbPass.getPassword()).trim());

        dbManager.saveConfig();
        new Thread(() -> {
            boolean ok = dbManager.testConnection();
            if (ok) dbManager.initializeTables();
        }).start();

        JOptionPane.showMessageDialog(this, "Đã lưu cấu hình thành công!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }
}

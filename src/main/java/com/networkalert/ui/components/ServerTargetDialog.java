package com.networkalert.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.CheckResult;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;
import com.networkalert.service.checker.CheckerFactory;
import com.networkalert.service.checker.IProtocolChecker;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Hộp thoại Thêm/Sửa máy chủ với thiết kế hiện đại, bố cục rõ ràng, chuẩn thẩm mỹ.
 */
public class ServerTargetDialog extends JDialog {
    private final JTextField txtName = new JTextField();
    private final JComboBox<ProtocolType> cmbProtocol = new JComboBox<>(ProtocolType.values());
    private final JTextField txtHost = new JTextField();
    private final JTextField txtPort = new JTextField();
    private final JComboBox<com.networkalert.model.TargetScope> cmbScope = new JComboBox<>(com.networkalert.model.TargetScope.values());
    private final JTextField txtGroup = new JTextField("Default");
    private final JTextField txtTags = new JTextField("");
    private final JTextField txtTimeout = new JTextField("1500");
    private final JTextField txtWarningThreshold = new JTextField("0");
    private final ToggleSwitch swEnabled = new ToggleSwitch(true);

    private final JLabel lblNameError = createErrorLabel();
    private final JLabel lblHostError = createErrorLabel();
    private final JLabel lblPortError = createErrorLabel();
    private final JLabel lblTestResult = new JLabel("");

    private final JButton btnSave = new JButton(I18n.get("dialog.target.save"));
    private final JButton btnCancel = new JButton(I18n.get("dialog.target.cancel"));
    private final JButton btnTestNow = new JButton("Kiểm tra kết nối", ThemeManager.getIcon("zap", 15));

    private boolean confirmed = false;
    private final ServerTarget editingTarget;

    public ServerTargetDialog(Frame owner, ServerTarget targetToEdit) {
        super(owner, targetToEdit == null ? I18n.get("dialog.target.title_add") : I18n.get("dialog.target.title_edit"), true);
        this.editingTarget = targetToEdit;

        setSize(UIScale.scale(540), UIScale.scale(720));
        setResizable(false);
        setLocationRelativeTo(owner);

        initUi();
        initData();
        setupEvents();
        validateFields();

        SwingUtilities.invokeLater(() -> {
            if (editingTarget == null) {
                txtName.requestFocusInWindow();
            } else {
                txtHost.requestFocusInWindow();
            }
        });
    }

    private static JLabel createErrorLabel() {
        JLabel lbl = new JLabel("");
        lbl.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lbl.setForeground(ThemeManager.statusOffline());
        lbl.setVisible(false);
        lbl.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(2), 0, 0));
        return lbl;
    }

    private void initUi() {
        JPanel contentPane = new JPanel(new BorderLayout(0, UIScale.scale(14)));
        contentPane.setBackground(ThemeManager.bgSurface());
        contentPane.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(18), UIScale.scale(24), UIScale.scale(18), UIScale.scale(24)));
        setContentPane(contentPane);

        // ==========================================
        // 1. HEADER: Icon badge + Title + Subtitle
        // ==========================================
        JPanel headerPanel = new JPanel(new BorderLayout(UIScale.scale(14), 0));
        headerPanel.setOpaque(false);

        // Icon Badge bo góc tròn
        JPanel iconBadge = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color badgeBg = ThemeManager.isDarkMode()
                        ? new Color(0x1E, 0x29, 0x3B)
                        : new Color(0xEE, 0xF2, 0xF6);
                g2.setColor(badgeBg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), UIScale.scale(12), UIScale.scale(12));
                g2.setColor(ThemeManager.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, UIScale.scale(12), UIScale.scale(12));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        iconBadge.setOpaque(false);
        iconBadge.setPreferredSize(new Dimension(UIScale.scale(42), UIScale.scale(42)));
        iconBadge.add(new JLabel(ThemeManager.getIcon(editingTarget == null ? "plus" : "server", 20)));
        headerPanel.add(iconBadge, BorderLayout.WEST);

        // Header Text
        JPanel headerText = new JPanel();
        headerText.setOpaque(false);
        headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS));

        JLabel lblTitle = new JLabel(getTitle());
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 17));
        lblTitle.setForeground(ThemeManager.textPrimary());

        String subtitleText = editingTarget == null
                ? "Thiết lập cấu hình kết nối và giám sát máy chủ mới"
                : "Cập nhật thông số kết nối, độ trễ và cảnh báo sự cố";
        JLabel lblSubtitle = new JLabel(subtitleText);
        lblSubtitle.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblSubtitle.setForeground(ThemeManager.textSecondary());

        headerText.add(lblTitle);
        headerText.add(Box.createVerticalStrut(UIScale.scale(3)));
        headerText.add(lblSubtitle);
        headerPanel.add(headerText, BorderLayout.CENTER);

        // Hairline separator dưới header
        JPanel headerWrapper = new JPanel(new BorderLayout(0, UIScale.scale(12)));
        headerWrapper.setOpaque(false);
        headerWrapper.add(headerPanel, BorderLayout.CENTER);
        JSeparator sep = new JSeparator();
        sep.setForeground(ThemeManager.border());
        sep.setBackground(ThemeManager.border());
        headerWrapper.add(sep, BorderLayout.SOUTH);

        contentPane.add(headerWrapper, BorderLayout.NORTH);

        // ==========================================
        // 2. CENTER FORM
        // ==========================================
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;

        int rowIdx = 0;

        // Row 1: Tên máy chủ
        JPanel pnlName = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlName.setOpaque(false);
        pnlName.add(createFieldLabel(I18n.get("dialog.target.name"), true), BorderLayout.NORTH);
        txtName.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        txtName.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        txtName.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Ví dụ: Wi-Fi Router / Default Gateway, Server Web…");
        txtName.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, ThemeManager.getIcon("server", 15));
        txtName.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        pnlName.add(txtName, BorderLayout.CENTER);
        pnlName.add(lblNameError, BorderLayout.SOUTH);

        gbc.gridy = rowIdx++;
        gbc.insets = new Insets(0, 0, UIScale.scale(10), 0);
        form.add(pnlName, gbc);

        // Row 2: Giao thức & Cổng (Port) trên cùng một hàng
        JPanel pnlProtoAndPort = new JPanel(new GridBagLayout());
        pnlProtoAndPort.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;

        // Cột 1: Giao thức
        JPanel pnlProto = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlProto.setOpaque(false);
        pnlProto.add(createFieldLabel(I18n.get("dialog.target.protocol"), false), BorderLayout.NORTH);
        cmbProtocol.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        cmbProtocol.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        cmbProtocol.setRenderer(createProtocolListCellRenderer());
        pnlProto.add(cmbProtocol, BorderLayout.CENTER);

        c.gridx = 0; c.gridy = 0; c.weightx = 0.58;
        c.insets = new Insets(0, 0, 0, UIScale.scale(12));
        pnlProtoAndPort.add(pnlProto, c);

        // Cột 2: Cổng (Port)
        JPanel pnlPort = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlPort.setOpaque(false);
        pnlPort.add(createFieldLabel(I18n.get("dialog.target.port"), false), BorderLayout.NORTH);
        txtPort.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        txtPort.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        txtPort.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "80");
        pnlPort.add(txtPort, BorderLayout.CENTER);
        pnlPort.add(lblPortError, BorderLayout.SOUTH);

        c.gridx = 1; c.gridy = 0; c.weightx = 0.42;
        c.insets = new Insets(0, 0, 0, 0);
        pnlProtoAndPort.add(pnlPort, c);

        gbc.gridy = rowIdx++;
        gbc.insets = new Insets(0, 0, UIScale.scale(10), 0);
        form.add(pnlProtoAndPort, gbc);

        // Row 3: Host / IP / URL
        JPanel pnlHost = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlHost.setOpaque(false);
        pnlHost.add(createFieldLabel(I18n.get("dialog.target.host"), true), BorderLayout.NORTH);
        txtHost.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        txtHost.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        txtHost.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Ví dụ: 10.12.0.254, google.com hoặc https://api.myweb.com");
        txtHost.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, ThemeManager.getIcon("globe", 15));
        txtHost.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        pnlHost.add(txtHost, BorderLayout.CENTER);
        pnlHost.add(lblHostError, BorderLayout.SOUTH);

        gbc.gridy = rowIdx++;
        gbc.insets = new Insets(0, 0, UIScale.scale(10), 0);
        form.add(pnlHost, gbc);

        // Row 4: Timeout & Ngưỡng cảnh báo trễ
        JPanel pnlTiming = new JPanel(new GridLayout(1, 2, UIScale.scale(12), 0));
        pnlTiming.setOpaque(false);

        JPanel pnlTimeout = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlTimeout.setOpaque(false);
        pnlTimeout.add(createFieldLabel(I18n.get("dialog.target.timeout"), false), BorderLayout.NORTH);
        txtTimeout.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        txtTimeout.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        txtTimeout.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "1500");
        pnlTimeout.add(txtTimeout, BorderLayout.CENTER);
        JLabel lblTimeoutHint = new JLabel("Thời gian tối đa chờ phản hồi");
        lblTimeoutHint.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblTimeoutHint.setForeground(ThemeManager.textMuted());
        pnlTimeout.add(lblTimeoutHint, BorderLayout.SOUTH);

        JPanel pnlThreshold = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlThreshold.setOpaque(false);
        pnlThreshold.add(createFieldLabel(I18n.get("dialog.target.warning_threshold"), false), BorderLayout.NORTH);
        txtWarningThreshold.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        txtWarningThreshold.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        txtWarningThreshold.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "0 = Tắt");
        pnlThreshold.add(txtWarningThreshold, BorderLayout.CENTER);
        JLabel lblThresholdHint = new JLabel("0 = Dùng ngưỡng mặc định");
        lblThresholdHint.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblThresholdHint.setForeground(ThemeManager.textMuted());
        pnlThreshold.add(lblThresholdHint, BorderLayout.SOUTH);

        pnlTiming.add(pnlTimeout);
        pnlTiming.add(pnlThreshold);

        gbc.gridy = rowIdx++;
        gbc.insets = new Insets(0, 0, UIScale.scale(12), 0);
        form.add(pnlTiming, gbc);

        // Row 4b: Scope & Nhóm mục tiêu
        JPanel pnlScopeAndGroup = new JPanel(new GridLayout(1, 2, UIScale.scale(12), 0));
        pnlScopeAndGroup.setOpaque(false);

        JPanel pnlScope = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlScope.setOpaque(false);
        pnlScope.add(createFieldLabel(I18n.get("dialog.target.scope", "Phạm vi đo kiểm"), false), BorderLayout.NORTH);
        cmbScope.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        cmbScope.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        pnlScope.add(cmbScope, BorderLayout.CENTER);
        JLabel lblScopeHint = new JLabel("SERVER: loopback/mock; MULTI: đo Quorum");
        lblScopeHint.setFont(ThemeManager.sans(Font.PLAIN, 10));
        lblScopeHint.setForeground(ThemeManager.textMuted());
        pnlScope.add(lblScopeHint, BorderLayout.SOUTH);

        JPanel pnlGroup = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        pnlGroup.setOpaque(false);
        pnlGroup.add(createFieldLabel(I18n.get("dialog.target.group", "Nhóm mục tiêu"), false), BorderLayout.NORTH);
        txtGroup.setPreferredSize(new Dimension(100, UIScale.scale(36)));
        txtGroup.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        txtGroup.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Default");
        pnlGroup.add(txtGroup, BorderLayout.CENTER);
        JLabel lblGroupHint = new JLabel("Phân nhóm bộ lọc và quản lý sự cố");
        lblGroupHint.setFont(ThemeManager.sans(Font.PLAIN, 10));
        lblGroupHint.setForeground(ThemeManager.textMuted());
        pnlGroup.add(lblGroupHint, BorderLayout.SOUTH);

        pnlScopeAndGroup.add(pnlScope);
        pnlScopeAndGroup.add(pnlGroup);

        gbc.gridy = rowIdx++;
        gbc.insets = new Insets(0, 0, UIScale.scale(12), 0);
        form.add(pnlScopeAndGroup, gbc);

        // Row 5: Card Trạng thái & Kiểm tra thử kết nối
        JPanel testCard = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ThemeManager.bgSurfaceAlt());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), UIScale.scale(12), UIScale.scale(12));
                g2.setColor(ThemeManager.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, UIScale.scale(12), UIScale.scale(12));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        testCard.setOpaque(false);
        testCard.setLayout(new GridBagLayout());
        testCard.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(12), UIScale.scale(14), UIScale.scale(12), UIScale.scale(14)));

        GridBagConstraints tc = new GridBagConstraints();
        tc.fill = GridBagConstraints.HORIZONTAL;
        tc.weightx = 1.0;
        tc.gridx = 0;

        // Hàng switch Bật giám sát
        JPanel switchRow = new JPanel(new BorderLayout(UIScale.scale(12), 0));
        switchRow.setOpaque(false);

        JPanel switchLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, UIScale.scale(2)));
        switchLeft.setOpaque(false);
        switchLeft.add(swEnabled);
        switchRow.add(switchLeft, BorderLayout.WEST);

        JPanel switchText = new JPanel(new GridLayout(2, 1, 0, UIScale.scale(2)));
        switchText.setOpaque(false);

        JLabel lblSwitchTitle = new JLabel(I18n.get("dialog.target.enabled"));
        lblSwitchTitle.setFont(ThemeManager.sans(Font.BOLD, 13));
        lblSwitchTitle.setForeground(ThemeManager.textPrimary());

        JLabel lblSwitchDesc = new JLabel("Tự động ping và kiểm tra kết nối định kỳ");
        lblSwitchDesc.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblSwitchDesc.setForeground(ThemeManager.textSecondary());

        switchText.add(lblSwitchTitle);
        switchText.add(lblSwitchDesc);
        switchRow.add(switchText, BorderLayout.CENTER);

        tc.gridy = 0;
        tc.insets = new Insets(0, 0, UIScale.scale(8), 0);
        testCard.add(switchRow, tc);

        // Đường phân cách mờ trong card
        JSeparator cardSep = new JSeparator();
        cardSep.setForeground(ThemeManager.border());
        cardSep.setBackground(ThemeManager.border());
        tc.gridy = 1;
        tc.insets = new Insets(0, 0, UIScale.scale(8), 0);
        testCard.add(cardSep, tc);

        // Hàng nút Kiểm tra thử và kết quả phản hồi
        JPanel testRow = new JPanel(new BorderLayout(UIScale.scale(12), 0));
        testRow.setOpaque(false);

        btnTestNow.setPreferredSize(new Dimension(UIScale.scale(145), UIScale.scale(32)));
        btnTestNow.setFont(ThemeManager.sans(Font.BOLD, 12));
        btnTestNow.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        btnTestNow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        testRow.add(btnTestNow, BorderLayout.WEST);

        lblTestResult.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblTestResult.setForeground(ThemeManager.textMuted());
        lblTestResult.setText("Nhấn nút để thử kết nối tới địa chỉ trên");
        testRow.add(lblTestResult, BorderLayout.CENTER);

        tc.gridy = 2;
        tc.insets = new Insets(0, 0, 0, 0);
        testCard.add(testRow, tc);

        gbc.gridy = rowIdx++;
        gbc.insets = new Insets(0, 0, 0, 0);
        form.add(testCard, gbc);

        // Spacer co giãn để hút phần thừa nếu có
        gbc.gridy = rowIdx++;
        gbc.weighty = 1.0;
        form.add(Box.createGlue(), gbc);

        contentPane.add(form, BorderLayout.CENTER);

        // ==========================================
        // 3. FOOTER ACTIONS: Hủy & Lưu máy chủ
        // ==========================================
        JPanel bottomBar = new JPanel(new BorderLayout(UIScale.scale(12), 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.border()),
                BorderFactory.createEmptyBorder(UIScale.scale(12), 0, 0, 0)
        ));

        // Phím tắt gợi ý bên trái
        JLabel lblShortcut = new JLabel("Enter: Lưu  •  Esc: Hủy");
        lblShortcut.setFont(ThemeManager.sans(Font.PLAIN, 11));
        lblShortcut.setForeground(ThemeManager.textMuted());
        bottomBar.add(lblShortcut, BorderLayout.WEST);

        // Nút hành động bên phải
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), 0));
        btnPanel.setOpaque(false);

        btnCancel.setFont(ThemeManager.sans(Font.PLAIN, 12));
        btnCancel.setPreferredSize(new Dimension(UIScale.scale(95), UIScale.scale(36)));
        btnCancel.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        btnCancel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnSave.setFont(ThemeManager.sans(Font.BOLD, 12));
        btnSave.setBackground(ThemeManager.accentFill());
        btnSave.setForeground(Color.WHITE);
        String btnStyle = ThemeManager.isDarkMode()
                ? "background: #3B82F6; foreground: #FFFFFF; hoverBackground: #2563EB; pressedBackground: #1D4ED8; arc: 8"
                : "background: #2563EB; foreground: #FFFFFF; hoverBackground: #1D4ED8; pressedBackground: #1E40AF; arc: 8";
        btnSave.putClientProperty(FlatClientProperties.STYLE, btnStyle);
        btnSave.setPreferredSize(new Dimension(UIScale.scale(130), UIScale.scale(36)));
        btnSave.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnPanel.add(btnCancel);
        btnPanel.add(btnSave);
        bottomBar.add(btnPanel, BorderLayout.EAST);

        contentPane.add(bottomBar, BorderLayout.SOUTH);
    }

    private JPanel createFieldLabel(String text, boolean required) {
        JPanel pnl = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pnl.setOpaque(false);
        JLabel lbl = new JLabel(text);
        lbl.setFont(ThemeManager.sans(Font.BOLD, 12));
        lbl.setForeground(ThemeManager.textPrimary());
        pnl.add(lbl);

        if (required) {
            JLabel req = new JLabel(" *");
            req.setFont(ThemeManager.sans(Font.BOLD, 12));
            req.setForeground(ThemeManager.statusOffline());
            pnl.add(req);
        }
        return pnl;
    }

    private ListCellRenderer<Object> createProtocolListCellRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ProtocolType) {
                    ProtocolType p = (ProtocolType) value;
                    label.setText(p.name() + " " + getProtocolDescription(p));
                    label.setIcon(new ProtocolDotIcon(ThemeManager.getProtocolColor(p), UIScale.scale(8)));
                    label.setIconTextGap(UIScale.scale(8));
                    label.setFont(ThemeManager.sans(Font.PLAIN, 12));
                    label.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
                }
                return label;
            }
        };
    }

    private String getProtocolDescription(ProtocolType p) {
        switch (p) {
            case ICMP: return "(Ping)";
            case HTTP: return "(Web 80)";
            case HTTPS: return "(SSL 443)";
            case TCP: return "(Socket)";
            case UDP: return "(Datagram)";
            case DNS: return "(Phân giải 53)";
            default: return "";
        }
    }

    private static class ProtocolDotIcon implements Icon {
        private final Color color;
        private final int size;

        public ProtocolDotIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            int circleY = y + (getIconHeight() - size) / 2;
            g2.fillOval(x, circleY, size, size);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size + UIScale.scale(4);
        }
    }

    private void initData() {
        if (editingTarget != null) {
            txtName.setText(editingTarget.getName());
            cmbProtocol.setSelectedItem(editingTarget.getProtocol());
            txtHost.setText(editingTarget.getHost());

            if (editingTarget.getProtocol() == ProtocolType.ICMP) {
                txtPort.setText("0");
                txtPort.setEnabled(false);
                txtPort.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Không dùng (ICMP)");
            } else {
                txtPort.setText(String.valueOf(editingTarget.getPort()));
                txtPort.setEnabled(true);
            }

            txtTimeout.setText(String.valueOf(editingTarget.getTimeoutMs()));
            txtWarningThreshold.setText(String.valueOf(editingTarget.getWarningThresholdMs()));
            cmbScope.setSelectedItem(editingTarget.getScope() != null ? editingTarget.getScope() : com.networkalert.model.TargetScope.MULTI);
            txtGroup.setText(editingTarget.getGroup() != null ? editingTarget.getGroup() : "Default");
            txtTags.setText(editingTarget.getTags() != null ? editingTarget.getTags() : "");
            swEnabled.setSelectedWithoutAnimation(editingTarget.isEnabled());
        } else {
            cmbProtocol.setSelectedItem(ProtocolType.HTTP);
            txtPort.setText("80");
            txtTimeout.setText("1500");
            txtWarningThreshold.setText("0");
            cmbScope.setSelectedItem(com.networkalert.model.TargetScope.MULTI);
            txtGroup.setText("Default");
            txtTags.setText("");
            swEnabled.setSelectedWithoutAnimation(true);
        }
    }

    private void setupEvents() {
        // Tự động xử lý Cổng (Port) khi đổi Giao thức
        cmbProtocol.addActionListener(e -> {
            ProtocolType p = (ProtocolType) cmbProtocol.getSelectedItem();
            if (p == ProtocolType.ICMP) {
                txtPort.setText("0");
                txtPort.setEnabled(false);
                txtPort.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Không dùng (ICMP)");
            } else {
                txtPort.setEnabled(true);
                txtPort.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Cổng");
                String currentPort = txtPort.getText().trim();
                if (currentPort.isEmpty() || "0".equals(currentPort) || "80".equals(currentPort) || "443".equals(currentPort) || "53".equals(currentPort)) {
                    if (p == ProtocolType.HTTP) {
                        txtPort.setText("80");
                    } else if (p == ProtocolType.HTTPS) {
                        txtPort.setText("443");
                    } else if (p == ProtocolType.DNS) {
                        txtPort.setText("53");
                    }
                }
            }
            validateFields();
        });
        cmbScope.addActionListener(e -> validateFields());

        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { validateFields(); }
            public void removeUpdate(DocumentEvent e) { validateFields(); }
            public void changedUpdate(DocumentEvent e) { validateFields(); }
        };
        txtName.getDocument().addDocumentListener(dl);
        txtHost.getDocument().addDocumentListener(dl);
        txtPort.getDocument().addDocumentListener(dl);
        txtTimeout.getDocument().addDocumentListener(dl);
        txtWarningThreshold.getDocument().addDocumentListener(dl);

        // Phím Esc = Hủy, Enter = Lưu
        btnCancel.addActionListener(e -> dispose());
        btnSave.addActionListener(e -> onSave());

        btnTestNow.addActionListener(e -> onTestConnection());

        JRootPane root = getRootPane();
        root.setDefaultButton(btnSave);
        root.registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void validateFields() {
        boolean valid = true;

        String name = txtName.getText().trim();
        if (name.isEmpty()) {
            txtName.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            lblNameError.setText("Tên máy chủ không được để trống");
            lblNameError.setVisible(true);
            valid = false;
        } else if (name.length() > 128) {
            txtName.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            lblNameError.setText("Tên không được vượt quá 128 ký tự (Hiện tại: " + name.length() + ")");
            lblNameError.setVisible(true);
            valid = false;
        } else {
            txtName.putClientProperty(FlatClientProperties.OUTLINE, null);
            lblNameError.setVisible(false);
        }

        String host = txtHost.getText().trim();
        com.networkalert.model.TargetScope selectedScope = (com.networkalert.model.TargetScope) cmbScope.getSelectedItem();
        if (host.isEmpty()) {
            txtHost.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            lblHostError.setText("Địa chỉ Host / IP không được để trống");
            lblHostError.setVisible(true);
            valid = false;
        } else if (!com.networkalert.util.NetworkValidationUtil.isValidHost(host)) {
            txtHost.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            lblHostError.setText("Địa chỉ không hợp lệ (IPv4, IPv6 hoặc Hostname RFC 1123)");
            lblHostError.setVisible(true);
            valid = false;
        } else if (selectedScope == com.networkalert.model.TargetScope.MULTI &&
                com.networkalert.util.NetworkValidationUtil.isPrivateOrLocalAddress(host)) {
            txtHost.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            String desc = com.networkalert.util.NetworkValidationUtil.getPrivateAddressTypeDescription(host);
            lblHostError.setText("IP riêng [" + desc + "] cấm gán MULTI (E11.1)!");
            lblHostError.setVisible(true);
            valid = false;
        } else {
            txtHost.putClientProperty(FlatClientProperties.OUTLINE, null);
            lblHostError.setVisible(false);
        }

        ProtocolType proto = (ProtocolType) cmbProtocol.getSelectedItem();
        if (proto != ProtocolType.ICMP) {
            try {
                int port = Integer.parseInt(txtPort.getText().trim());
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException();
                }
                txtPort.putClientProperty(FlatClientProperties.OUTLINE, null);
                lblPortError.setVisible(false);
            } catch (Exception ex) {
                txtPort.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
                lblPortError.setText("Cổng phải là số từ 1 đến 65535");
                lblPortError.setVisible(true);
                valid = false;
            }
        } else {
            txtPort.putClientProperty(FlatClientProperties.OUTLINE, null);
            lblPortError.setVisible(false);
        }

        // Kiểm tra Timeout
        try {
            int to = Integer.parseInt(txtTimeout.getText().trim());
            if (to < 100 || to > 60000) {
                throw new NumberFormatException();
            }
            txtTimeout.putClientProperty(FlatClientProperties.OUTLINE, null);
        } catch (Exception ex) {
            txtTimeout.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            valid = false;
        }

        // Kiểm tra ngưỡng cảnh báo trễ
        try {
            long th = Long.parseLong(txtWarningThreshold.getText().trim());
            if (th < 0) {
                throw new NumberFormatException();
            }
            txtWarningThreshold.putClientProperty(FlatClientProperties.OUTLINE, null);
        } catch (Exception ex) {
            txtWarningThreshold.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            valid = false;
        }

        txtName.repaint();
        txtHost.repaint();
        txtPort.repaint();
        txtTimeout.repaint();
        txtWarningThreshold.repaint();

        btnSave.setEnabled(valid);
        btnTestNow.setEnabled(!host.isEmpty());
    }

    private void onTestConnection() {
        btnTestNow.setEnabled(false);
        lblTestResult.setText("● Đang kết nối thử nghiệm…");
        lblTestResult.setForeground(ThemeManager.textSecondary());

        String host = txtHost.getText().trim();
        int port = 80;
        try { port = Integer.parseInt(txtPort.getText().trim()); } catch (Exception ignored) {}
        ProtocolType proto = (ProtocolType) cmbProtocol.getSelectedItem();
        int timeout = 1500;
        try { timeout = Integer.parseInt(txtTimeout.getText().trim()); } catch (Exception ignored) {}

        ServerTarget temp = new ServerTarget(0, "Test", host, port, proto, 5, timeout, true);

        SwingWorker<CheckResult, Void> worker = new SwingWorker<>() {
            @Override
            protected CheckResult doInBackground() {
                IProtocolChecker checker = CheckerFactory.getChecker(proto);
                return checker.check(temp);
            }

            @Override
            protected void done() {
                try {
                    CheckResult res = get();
                    if (res.isSuccess()) {
                        lblTestResult.setText(String.format("● Thành công: Phản hồi trong %d ms", res.getLatencyMs()));
                        lblTestResult.setForeground(ThemeManager.statusOnline());
                    } else {
                        String msg = res.getMessage() != null ? res.getMessage() : "Không phản hồi";
                        if (msg.length() > 36) {
                            msg = msg.substring(0, 33) + "…";
                        }
                        lblTestResult.setText(String.format("● Thất bại: %s", msg));
                        lblTestResult.setForeground(ThemeManager.statusOffline());
                    }
                } catch (Exception ex) {
                    lblTestResult.setText("● Lỗi kiểm tra: " + ex.getMessage());
                    lblTestResult.setForeground(ThemeManager.statusOffline());
                } finally {
                    btnTestNow.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private void onSave() {
        this.confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public ServerTarget getTarget() {
        String name = txtName.getText().trim();
        String host = txtHost.getText().trim();
        ProtocolType proto = (ProtocolType) cmbProtocol.getSelectedItem();
        int port = 80;
        if (proto == ProtocolType.ICMP) {
            port = 0;
        } else {
            try { port = Integer.parseInt(txtPort.getText().trim()); } catch (Exception ignored) {}
        }
        int timeout = 1500;
        try { timeout = Integer.parseInt(txtTimeout.getText().trim()); } catch (Exception ignored) {}
        long warningThreshold = 0;
        try { warningThreshold = Long.parseLong(txtWarningThreshold.getText().trim()); } catch (Exception ignored) {}
        boolean enabled = swEnabled.isSelected();

        com.networkalert.model.TargetScope scope = (com.networkalert.model.TargetScope) cmbScope.getSelectedItem();
        String group = txtGroup.getText().trim();
        String tags = txtTags.getText().trim();

        if (editingTarget != null) {
            editingTarget.setName(name);
            editingTarget.setHost(host);
            editingTarget.setPort(port);
            editingTarget.setProtocol(proto);
            editingTarget.setTimeoutMs(timeout);
            editingTarget.setWarningThresholdMs(warningThreshold);
            editingTarget.setScope(scope);
            editingTarget.setGroup(group.isEmpty() ? "Default" : group);
            editingTarget.setTags(tags);
            editingTarget.setEnabled(enabled);
            return editingTarget;
        } else {
            ServerTarget newTarget = new ServerTarget(0, name, host, port, proto, 5, timeout, enabled);
            newTarget.setWarningThresholdMs(warningThreshold);
            newTarget.setScope(scope);
            newTarget.setGroup(group.isEmpty() ? "Default" : group);
            newTarget.setTags(tags);
            return newTarget;
        }
    }
}

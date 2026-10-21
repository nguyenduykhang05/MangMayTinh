package com.networkalert.ui;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.RealNetworkInfo;
import com.networkalert.server.AgentManager;
import com.networkalert.service.RealNetworkService;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.InetAddress;
import java.util.Map;

/**
 * Hộp thoại Cấp quyền và Đăng ký Agent mới (Agent Enrollment Dialog)
 * Sinh Enrollment Token dùng một lần có hạn (15 phút).
 * Agent kết nối lần đầu đổi token lấy Dedicated Secret riêng để có thể thu hồi từng trạm độc lập.
 */
public class AgentEnrollmentDialog extends JDialog {
    private final AgentManager agentManager;
    private final JTextField txtToken = new JTextField(16);
    private final JTextArea txtCmd = new JTextArea(3, 40);
    private final DefaultTableModel secretsModel;
    private final JTable secretsTable;
    private String currentToken = "";

    public AgentEnrollmentDialog(Window parent, int serverPort) {
        this(parent, serverPort, null);
    }

    public AgentEnrollmentDialog(Window parent, int serverPort, AgentManager customAgentManager) {
        super(parent, "Thêm Trạm Agent Máy Thật (Agent Enrollment)", ModalityType.APPLICATION_MODAL);
        this.agentManager = customAgentManager != null ? customAgentManager : AgentManager.getInstance();

        setLayout(new BorderLayout(0, UIScale.scale(12)));
        setSize(UIScale.scale(640), UIScale.scale(480));
        setLocationRelativeTo(parent);

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(16), 0, UIScale.scale(16)));
        JLabel lblTitle = new JLabel("Cấp phép Trạm Agent Đo Kiểm (Agent Enrollment)");
        lblTitle.setFont(ThemeManager.sans(Font.BOLD, 15));
        lblTitle.setIcon(ThemeManager.getIcon("radio", 18));
        headerPanel.add(lblTitle, BorderLayout.WEST);

        JLabel lblSub = new JLabel("Mã Enrollment dùng một lần có hạn 15 phút. Agent lần đầu sẽ tự động đổi lấy Secret riêng.");
        lblSub.setFont(ThemeManager.sans(Font.PLAIN, 12));
        lblSub.setForeground(ThemeManager.textMuted());
        headerPanel.add(lblSub, BorderLayout.SOUTH);
        add(headerPanel, BorderLayout.NORTH);

        // Center: Token box + Command box + Secrets table
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(16), 0, UIScale.scale(16)));

        // Box 1: Token Generation
        JPanel tokenBox = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(8), UIScale.scale(4)));
        tokenBox.setBorder(BorderFactory.createTitledBorder("Mã Enrollment dùng một lần"));

        txtToken.setFont(ThemeManager.mono(Font.BOLD, 14));
        txtToken.setEditable(false);
        txtToken.setBackground(ThemeManager.bgSurface());

        JButton btnGen = new JButton("Tạo mã mới", ThemeManager.getIcon("refresh-cw", 14));
        btnGen.addActionListener(e -> generateNewToken(serverPort));

        JButton btnCopy = new JButton("Sao chép", ThemeManager.getIcon("copy", 14));
        btnCopy.addActionListener(e -> {
            if (!currentToken.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(currentToken), null);
                JOptionPane.showMessageDialog(this, "Đã sao chép Enrollment Token vào bộ nhớ tạm!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        tokenBox.add(new JLabel("Mã cấp phép:"));
        tokenBox.add(txtToken);
        tokenBox.add(btnGen);
        tokenBox.add(btnCopy);
        centerPanel.add(tokenBox);

        // Box 2: Command snippet
        JPanel cmdBox = new JPanel(new BorderLayout());
        cmdBox.setBorder(BorderFactory.createTitledBorder("Lệnh khởi chạy Agent mẫu"));
        txtCmd.setFont(ThemeManager.mono(Font.PLAIN, 12));
        txtCmd.setEditable(false);
        txtCmd.setLineWrap(true);
        txtCmd.setBackground(ThemeManager.bgSurface());
        cmdBox.add(new JScrollPane(txtCmd), BorderLayout.CENTER);

        JButton btnCopyCmd = new JButton("Sao chép toàn bộ lệnh", ThemeManager.getIcon("copy", 14));
        btnCopyCmd.addActionListener(e -> {
            String cmd = txtCmd.getText().trim();
            if (!cmd.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cmd), null);
                JOptionPane.showMessageDialog(this, "Đã sao chép lệnh khởi chạy vào bộ nhớ tạm!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JPanel cmdBtnPnl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        cmdBtnPnl.add(btnCopyCmd);
        cmdBox.add(cmdBtnPnl, BorderLayout.SOUTH);
        centerPanel.add(cmdBox);

        // Box 3: Table of Dedicated Secrets
        JPanel secBox = new JPanel(new BorderLayout());
        secBox.setBorder(BorderFactory.createTitledBorder("Danh sách Secret riêng đã cấp (Dedicated Secrets)"));

        String[] cols = {"Agent ID", "Dedicated Secret", "Hành động"};
        secretsModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        secretsTable = new JTable(secretsModel);
        secretsTable.setRowHeight(UIScale.scale(28));
        secretsTable.setFont(ThemeManager.sans(Font.PLAIN, 12));
        secretsTable.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(140));
        secretsTable.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(240));
        secretsTable.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(100));

        JScrollPane secScroll = new JScrollPane(secretsTable);
        secScroll.setPreferredSize(new Dimension(500, UIScale.scale(100)));
        secBox.add(secScroll, BorderLayout.CENTER);

        JButton btnRevoke = new JButton("Thu hồi Secret đã chọn", ThemeManager.getIcon("trash-2", 14));
        btnRevoke.addActionListener(e -> revokeSelectedSecret());
        JPanel secBtnPnl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        secBtnPnl.add(btnRevoke);
        secBox.add(secBtnPnl, BorderLayout.SOUTH);

        centerPanel.add(secBox);
        add(centerPanel, BorderLayout.CENTER);

        // Footer
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(8), UIScale.scale(12)));
        JButton btnClose = new JButton("Đóng");
        btnClose.addActionListener(e -> dispose());
        footer.add(btnClose);
        add(footer, BorderLayout.SOUTH);

        generateNewToken(serverPort);
        refreshSecrets();
    }

    private void generateNewToken(int serverPort) {
        if (agentManager != null) {
            AgentManager.EnrollmentToken et = agentManager.generateEnrollmentToken(15);
            this.currentToken = et.getToken();
        } else {
            this.currentToken = "ENROLL-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        txtToken.setText(currentToken);

        String localIp = "127.0.0.1";
        RealNetworkInfo netInfo = RealNetworkService.getInstance().getNetworkInfo();
        if (netInfo != null && netInfo.getLocalIp() != null && !netInfo.getLocalIp().isEmpty()) {
            localIp = netInfo.getLocalIp();
        } else {
            try {
                localIp = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ignored) {}
        }

        int port = serverPort > 0 ? serverPort : 9876;
        String cmd = String.format("java -jar NCAS.jar agent -s %s -p %d --id agent-may-that-01 --enroll %s",
                localIp, port, currentToken);
        txtCmd.setText(cmd);
    }

    private void refreshSecrets() {
        secretsModel.setRowCount(0);
        if (agentManager != null) {
            Map<String, String> map = agentManager.getAllAgentSecrets();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                secretsModel.addRow(new Object[]{entry.getKey(), entry.getValue(), "Có thể thu hồi"});
            }
        }
    }

    private void revokeSelectedSecret() {
        int row = secretsTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một Agent để thu hồi Secret.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String aId = (String) secretsModel.getValueAt(row, 0);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc muốn thu hồi Secret của Agent '" + aId + "'?\nAgent này sẽ bị ngắt kết nối ngay lập tức.",
                "Xác nhận thu hồi", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION && agentManager != null) {
            agentManager.revokeAgentSecret(aId);
            refreshSecrets();
            JOptionPane.showMessageDialog(this, "Đã thu hồi Secret của Agent '" + aId + "'!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}

package com.networkalert.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;
import com.networkalert.util.LoggerUtil;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel Nhật ký hoạt động thời gian thực (Live System Log)
 * Chuẩn đặc tả mục 5.6: JTextPane, StyledDocument, lọc mức độ, tìm kiếm, tự cuộn, tối đa 1.000 dòng.
 */
public class LiveLogPanel extends JPanel {
    private static final int MAX_LOG_LINES = 1000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextPane textPane;
    private final StyledDocument doc;
    private final JScrollPane scrollPane;

    private final JTextField txtSearch = new JTextField();
    private final JCheckBox chkAutoScroll = new JCheckBox(I18n.get("log.autoscroll"), true);
    private final ButtonGroup levelGroup = new ButtonGroup();
    private final JToggleButton btnAll = new JToggleButton(I18n.get("log.filter.all"), true);
    private final JToggleButton btnInfo = new JToggleButton(I18n.get("log.filter.info"), false);
    private final JToggleButton btnWarn = new JToggleButton(I18n.get("log.filter.warn"), false);
    private final JToggleButton btnError = new JToggleButton(I18n.get("log.filter.error"), false);

    private final JButton btnCopy = new JButton();
    private final JButton btnClear = new JButton();
    private final JButton btnCollapse = new JButton();

    private Timer searchTimer;
    private final List<LogEntry> allEntries = new ArrayList<>();
    private boolean isUserScrolling = false;
    private Runnable onToggleCollapseCallback;

    public LiveLogPanel(Runnable onToggleCollapse) {
        this.onToggleCollapseCallback = onToggleCollapse;

        setLayout(new BorderLayout());
        setOpaque(false);

        // 1. HEADER (Cao 36 px)
        JPanel header = createHeaderBar();
        add(header, BorderLayout.NORTH);

        // 2. TEXT PANE & STYLED DOCUMENT
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setBackground(ThemeManager.bgSurface());
        textPane.setFont(ThemeManager.mono(Font.PLAIN, 12));
        textPane.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(12), UIScale.scale(8), UIScale.scale(12)));

        doc = textPane.getStyledDocument();

        scrollPane = new JScrollPane(textPane);
        scrollPane.setBorder(BorderFactory.createLineBorder(ThemeManager.border(), 1));
        scrollPane.getViewport().setBackground(ThemeManager.bgSurface());

        // Lắng nghe cuộn chuột để tự động tạm dừng / khôi phục Tự cuộn
        setupScrollDetection();

        add(scrollPane, BorderLayout.CENTER);

        // Đăng ký lắng nghe từ LoggerUtil
        LoggerUtil.addListener(logLine -> SwingUtilities.invokeLater(() -> appendRawLog(logLine)));
    }

    private JPanel createHeaderBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(32)));
        bar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(6), UIScale.scale(4), UIScale.scale(6)));

        // Trái: Tiêu đề + Các nút lọc cấp độ (Tất cả, Thông tin, Cảnh báo, Lỗi)
        JPanel leftGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(5), 0));
        leftGroup.setOpaque(false);

        com.formdev.flatlaf.extras.FlatSVGIcon logIcon = ThemeManager.getIcon("list", 14);
        if (logIcon == null) logIcon = ThemeManager.getIcon("activity", 14);
        JLabel title = new JLabel(I18n.get("log.title"), logIcon, SwingConstants.LEFT);
        title.setFont(ThemeManager.sans(Font.BOLD, 13));
        title.setForeground(ThemeManager.textPrimary());
        title.setIconTextGap(UIScale.scale(6));
        leftGroup.add(title);
        leftGroup.add(Box.createHorizontalStrut(UIScale.scale(4)));

        setupLevelBtn(btnAll);
        setupLevelBtn(btnInfo);
        setupLevelBtn(btnWarn);
        setupLevelBtn(btnError);

        levelGroup.add(btnAll);
        levelGroup.add(btnInfo);
        levelGroup.add(btnWarn);
        levelGroup.add(btnError);

        leftGroup.add(btnAll);
        leftGroup.add(btnInfo);
        leftGroup.add(btnWarn);
        leftGroup.add(btnError);

        // Phải: Ô tìm kiếm + Tự cuộn + Nút công cụ (Copy, Clear, Thu gọn)
        JPanel rightGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(6), 0));
        rightGroup.setOpaque(false);

        txtSearch.setPreferredSize(new Dimension(UIScale.scale(130), UIScale.scale(26)));
        txtSearch.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Tìm trong nhật ký...");
        txtSearch.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, ThemeManager.getIcon("search", 13));
        txtSearch.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        txtSearch.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        txtSearch.addActionListener(e -> rebuildDocument());
        txtSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { triggerSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { triggerSearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { triggerSearch(); }
            private void triggerSearch() {
                if (searchTimer != null && searchTimer.isRunning()) searchTimer.stop();
                searchTimer = new Timer(150, ev -> rebuildDocument());
                searchTimer.setRepeats(false);
                searchTimer.start();
            }
        });

        chkAutoScroll.setFont(ThemeManager.sans(Font.PLAIN, 11));
        chkAutoScroll.setOpaque(false);
        chkAutoScroll.setForeground(ThemeManager.textSecondary());
        chkAutoScroll.setMargin(new Insets(0, 2, 0, 2));

        btnCopy.setIcon(ThemeManager.getIcon("copy", 14));
        btnCopy.putClientProperty("JButton.buttonType", "toolBarButton");
        btnCopy.setToolTipText(I18n.get("log.copy"));
        btnCopy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCopy.addActionListener(e -> copyLogsToClipboard());

        btnClear.setIcon(ThemeManager.getIcon("eraser", 14));
        btnClear.putClientProperty("JButton.buttonType", "toolBarButton");
        btnClear.setToolTipText(I18n.get("log.clear"));
        btnClear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnClear.addActionListener(e -> clearLogs());

        btnCollapse.setIcon(ThemeManager.getIcon("chevron-down", 14));
        btnCollapse.putClientProperty("JButton.buttonType", "toolBarButton");
        btnCollapse.setToolTipText("Thu gọn / Mở rộng");
        btnCollapse.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCollapse.addActionListener(e -> {
            if (onToggleCollapseCallback != null) onToggleCollapseCallback.run();
        });

        rightGroup.add(txtSearch);
        rightGroup.add(chkAutoScroll);
        rightGroup.add(btnCopy);
        rightGroup.add(btnClear);
        rightGroup.add(btnCollapse);

        bar.add(leftGroup, BorderLayout.WEST);
        bar.add(rightGroup, BorderLayout.EAST);
        return bar;
    }

    private void setupLevelBtn(JToggleButton btn) {
        btn.setFont(ThemeManager.sans(Font.PLAIN, 11));
        btn.setPreferredSize(new Dimension(UIScale.scale(52), UIScale.scale(24)));
        btn.setMargin(new Insets(UIScale.scale(1), UIScale.scale(3), UIScale.scale(1), UIScale.scale(3)));
        btn.putClientProperty(FlatClientProperties.STYLE, "arc: 4");
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> rebuildDocument());
    }

    private void setupScrollDetection() {
        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        vBar.addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                if (e.getValueIsAdjusting()) {
                    // Người dùng đang kéo thanh cuộn
                    int max = vBar.getMaximum() - vBar.getVisibleAmount();
                    boolean atBottom = (vBar.getValue() >= max - 15);
                    if (!atBottom) {
                        chkAutoScroll.setSelected(false);
                    } else {
                        chkAutoScroll.setSelected(true);
                    }
                }
            }
        });
    }

    public void appendRawLog(String rawLine) {
        // Phân tích định dạng: [HH:mm:ss] [LEVEL] Message
        String timeStr = LocalTime.now().format(TIME_FMT);
        String level = "INFO";
        String message = rawLine;

        if (rawLine != null) {
            if (rawLine.contains("[WARN ]") || rawLine.contains("[WARN]")) {
                level = "WARN";
            } else if (rawLine.contains("[ERROR]") || rawLine.contains("Lỗi") || rawLine.contains("OFFLINE")) {
                level = "ERROR";
            } else if (rawLine.contains("phục hồi") || rawLine.contains("online trở lại")) {
                level = "RECOVERY";
            }

            // Loại bỏ timestamp cũ nếu có
            if (rawLine.startsWith("[20") && rawLine.indexOf(']') > 0) {
                int firstClose = rawLine.indexOf(']');
                int secondClose = rawLine.indexOf(']', firstClose + 1);
                if (secondClose > 0 && secondClose + 2 < rawLine.length()) {
                    message = rawLine.substring(secondClose + 2).trim();
                }
            }
        }

        LogEntry entry = new LogEntry(timeStr, level, message);
        allEntries.add(entry);

        if (allEntries.size() > MAX_LOG_LINES) {
            allEntries.remove(0);
        }

        if (matchesFilter(entry)) {
            insertEntryToDoc(entry);
            if (chkAutoScroll.isSelected()) {
                scrollToBottom();
            }
        }
    }

    private boolean matchesFilter(LogEntry entry) {
        String query = txtSearch.getText().trim().toLowerCase();
        if (!query.isEmpty() && !entry.message.toLowerCase().contains(query)) {
            return false;
        }

        if (btnInfo.isSelected() && !"INFO".equals(entry.level)) return false;
        if (btnWarn.isSelected() && !"WARN".equals(entry.level)) return false;
        if (btnError.isSelected() && !"ERROR".equals(entry.level) && !"RECOVERY".equals(entry.level)) return false;

        return true;
    }

    private void insertEntryToDoc(LogEntry entry) {
        try {
            Style defaultStyle = textPane.getStyle(StyleContext.DEFAULT_STYLE);

            // 1. Timestamp (text.muted)
            Style timeStyle = textPane.addStyle("TimeStyle", defaultStyle);
            StyleConstants.setForeground(timeStyle, ThemeManager.textMuted());
            StyleConstants.setFontFamily(timeStyle, ThemeManager.mono(Font.PLAIN, 12).getFamily());
            StyleConstants.setFontSize(timeStyle, 12);
            doc.insertString(doc.getLength(), entry.time + "  ", timeStyle);

            // 2. Mức độ (76 px tương đương khoảng 10 ký tự, in đậm)
            String levelLabel;
            Color levelColor;
            Color bgTint = null;

            if ("WARN".equals(entry.level)) {
                levelLabel = String.format("%-11s", "Cảnh báo");
                levelColor = ThemeManager.statusWarning();
                bgTint = new Color(levelColor.getRed(), levelColor.getGreen(), levelColor.getBlue(), 20);
            } else if ("ERROR".equals(entry.level)) {
                levelLabel = String.format("%-11s", "Lỗi");
                levelColor = ThemeManager.statusOffline();
                bgTint = new Color(levelColor.getRed(), levelColor.getGreen(), levelColor.getBlue(), 20);
            } else if ("RECOVERY".equals(entry.level)) {
                levelLabel = String.format("%-11s", "Phục hồi");
                levelColor = ThemeManager.statusOnline();
                bgTint = new Color(levelColor.getRed(), levelColor.getGreen(), levelColor.getBlue(), 20);
            } else {
                levelLabel = String.format("%-11s", "Thông tin");
                levelColor = ThemeManager.textSecondary();
            }

            Style levelStyle = textPane.addStyle("LevelStyle", defaultStyle);
            StyleConstants.setForeground(levelStyle, levelColor);
            StyleConstants.setFontFamily(levelStyle, ThemeManager.sans(Font.BOLD, 12).getFamily());
            StyleConstants.setFontSize(levelStyle, 12);
            StyleConstants.setBold(levelStyle, true);
            if (bgTint != null) StyleConstants.setBackground(levelStyle, bgTint);
            doc.insertString(doc.getLength(), levelLabel, levelStyle);

            // 3. Nội dung (text.primary, font Sans tiếng Việt, tên trong nháy đơn in đậm)
            insertMessageWithHighlightedQuotes(entry.message, defaultStyle, bgTint);
            doc.insertString(doc.getLength(), "\n", defaultStyle);

        } catch (BadLocationException ignored) {}
    }

    private void insertMessageWithHighlightedQuotes(String message, Style baseStyle, Color bgTint) throws BadLocationException {
        Style normStyle = textPane.addStyle("NormMsg", baseStyle);
        StyleConstants.setForeground(normStyle, ThemeManager.textPrimary());
        StyleConstants.setFontFamily(normStyle, ThemeManager.sans(Font.PLAIN, 12).getFamily());
        StyleConstants.setFontSize(normStyle, 12);
        if (bgTint != null) StyleConstants.setBackground(normStyle, bgTint);

        Style boldStyle = textPane.addStyle("BoldMsg", baseStyle);
        StyleConstants.setForeground(boldStyle, ThemeManager.textPrimary());
        StyleConstants.setFontFamily(boldStyle, ThemeManager.sans(Font.BOLD, 12).getFamily());
        StyleConstants.setFontSize(boldStyle, 12);
        StyleConstants.setBold(boldStyle, true);
        if (bgTint != null) StyleConstants.setBackground(boldStyle, bgTint);

        int pos = 0;
        while (pos < message.length()) {
            int q1 = message.indexOf('\'', pos);
            if (q1 >= 0) {
                int q2 = message.indexOf('\'', q1 + 1);
                if (q2 > q1) {
                    if (q1 > pos) {
                        doc.insertString(doc.getLength(), message.substring(pos, q1), normStyle);
                    }
                    doc.insertString(doc.getLength(), message.substring(q1, q2 + 1), boldStyle);
                    pos = q2 + 1;
                    continue;
                }
            }
            doc.insertString(doc.getLength(), message.substring(pos), normStyle);
            break;
        }
    }

    private void rebuildDocument() {
        try {
            doc.remove(0, doc.getLength());
            for (LogEntry e : allEntries) {
                if (matchesFilter(e)) {
                    insertEntryToDoc(e);
                }
            }
            if (chkAutoScroll.isSelected()) {
                scrollToBottom();
            }
        } catch (BadLocationException ignored) {}
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            vBar.setValue(vBar.getMaximum());
        });
    }

    private void copyLogsToClipboard() {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : allEntries) {
            if (matchesFilter(e)) {
                sb.append(e.time).append("  [").append(e.level).append("]  ").append(e.message).append("\n");
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    public void clearLogs() {
        allEntries.clear();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private static class LogEntry {
        final String time;
        final String level;
        final String message;

        LogEntry(String time, String level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }
}

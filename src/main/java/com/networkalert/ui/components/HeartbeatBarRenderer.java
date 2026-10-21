package com.networkalert.ui.components;

import com.formdev.flatlaf.util.UIScale;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.ServerTarget.HeartbeatRecord;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * TableCellRenderer vẽ dải 60 ô kết quả nhịp tim (Heartbeat Bar) phong cách Uptime Kuma.
 * <p>
 * Hiển thị 60 khối nhỏ thể hiện trạng thái gần nhất kèm độ trễ ms thời gian thực.
 * Tự vẽ hoàn toàn bằng Java 2D Graphics, KHÔNG tạo 60 component con mỗi dòng để đảm bảo hiệu năng 60 FPS.
 * </p>
 */
public class HeartbeatBarRenderer extends JPanel implements TableCellRenderer {
    private ServerTarget target;
    private Color rowBg;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM");
    private static final int TOTAL_SLOTS = 30;

    public HeartbeatBarRenderer() {
        setOpaque(true);
        setToolTipText(""); // Kích hoạt ToolTipManager của Swing
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
        if (value instanceof ServerTarget) {
            this.target = (ServerTarget) value;
        } else if (table.getModel() instanceof ServerTableModel) {
            int modelRow = table.convertRowIndexToModel(row);
            this.target = ((ServerTableModel) table.getModel()).getTargetAt(modelRow);
        } else {
            this.target = null;
        }

        if (isSelected) {
            this.rowBg = ThemeManager.bgSelected();
        } else if (target != null && target.getStatus() == ServerStatus.WARNING) {
            this.rowBg = ThemeManager.isDarkMode() ? new Color(0x33, 0x27, 0x14) : new Color(0xFF, 0xFB, 0xEB);
        } else if (target != null && target.getStatus() == ServerStatus.OFFLINE) {
            this.rowBg = ThemeManager.isDarkMode() ? new Color(0x3B, 0x1A, 0x1A) : new Color(0xFE, 0xF2, 0xF2);
        } else {
            this.rowBg = ThemeManager.bgSurface();
        }

        setBackground(this.rowBg);
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(rowBg != null ? rowBg : ThemeManager.bgSurface());
        g2.fillRect(0, 0, w, h);

        int padX = UIScale.scale(8);
        int barH = UIScale.scale(18);
        int padY = Math.max(2, (h - barH) / 2);

        int availW = w - (padX * 2);
        if (availW <= 10) {
            g2.dispose();
            return;
        }

        // Tính toán kích thước 60 ô
        float slotWithGap = (float) availW / TOTAL_SLOTS;
        float gap = Math.max(1.0f, slotWithGap * 0.2f);
        float slotW = Math.max(1.5f, slotWithGap - gap);

        List<HeartbeatRecord> heartbeats = (target != null) ? target.getHeartbeats() : Collections.emptyList();
        int recordsCount = heartbeats.size();
        int emptySlots = Math.max(0, TOTAL_SLOTS - recordsCount);

        Color emptyColor = ThemeManager.isDarkMode() ? new Color(0x1E, 0x29, 0x3B) : new Color(0xCB, 0xD5, 0xE1);

        // Vẽ 30 thanh nhịp tim
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            float x = padX + (i * slotWithGap);
            int rx = Math.round(x);
            int rw = Math.max(1, Math.round(slotW));

            if (i < emptySlots) {
                // Ô chưa có dữ liệu đo
                g2.setColor(emptyColor);
                g2.fillRoundRect(rx, padY, rw, barH, 2, 2);
            } else {
                int recordIndex = i - emptySlots;
                if (recordIndex < recordsCount) {
                    HeartbeatRecord rec = heartbeats.get(recordIndex);
                    Color barColor = getColorForStatus(rec.getStatus());
                    g2.setColor(barColor);
                    g2.fillRoundRect(rx, padY, rw, barH, 2, 2);
                }
            }
        }

        g2.dispose();
    }

    private Color getColorForStatus(ServerStatus status) {
        if (status == null) return ThemeManager.textMuted();
        switch (status) {
            case ONLINE:
                return ThemeManager.statusOnline();
            case WARNING:
                return ThemeManager.statusWarning();
            case OFFLINE:
                return ThemeManager.statusOffline();
            case MAINTENANCE:
                return ThemeManager.statusMaintenance();
            case UNREACHABLE:
                return new Color(0x94, 0xA3, 0xB8);
            case PAUSED:
            case UNCHECKED:
            default:
                return ThemeManager.textMuted();
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (target == null) return null;

        int padX = UIScale.scale(8);
        int availW = getWidth() - (padX * 2);
        if (availW <= 0) return null;

        float slotWithGap = (float) availW / TOTAL_SLOTS;
        int mouseX = event.getX() - padX;
        if (mouseX < 0 || mouseX >= availW) return null;

        int slot = (int) (mouseX / slotWithGap);
        if (slot < 0 || slot >= TOTAL_SLOTS) return null;

        List<HeartbeatRecord> heartbeats = target.getHeartbeats();
        int recordsCount = heartbeats.size();
        int emptySlots = Math.max(0, TOTAL_SLOTS - recordsCount);

        if (slot < emptySlots) {
            return "<html><div style='padding:2px;'><i>Chưa có dữ liệu kiểm tra cho thời điểm này</i></div></html>";
        }

        int recordIndex = slot - emptySlots;
        if (recordIndex >= 0 && recordIndex < recordsCount) {
            HeartbeatRecord rec = heartbeats.get(recordIndex);
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(rec.getTimestamp()), ZoneId.systemDefault());

            String statusLabel = rec.getStatus() != null ? rec.getStatus().name() : "N/A";
            String latencyStr = rec.getLatencyMs() >= 0 ? (rec.getLatencyMs() + " ms") : "Không phản hồi";
            String station = rec.getStationId() != null ? rec.getStationId() : "Server";
            String msg = (rec.getMessage() != null && !rec.getMessage().isEmpty()) ? rec.getMessage() : "OK";

            return String.format(
                    "<html><div style='padding:4px; font-family:sans-serif;'>" +
                            "<b>Thời gian:</b> %s<br>" +
                            "<b>Trạm đo:</b> %s<br>" +
                            "<b>Trạng thái:</b> %s<br>" +
                            "<b>Độ trễ:</b> %s<br>" +
                            "<b>Thông điệp:</b> %s" +
                            "</div></html>",
                    time.format(TIME_FMT),
                    station,
                    statusLabel,
                    latencyStr,
                    msg
            );
        }

        return null;
    }
}

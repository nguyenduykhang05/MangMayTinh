package com.networkalert.ui.components;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.ui.theme.I18n;

import javax.swing.table.AbstractTableModel;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * TableModel chuyên biệt cho danh sách máy chủ, hỗ trợ cập nhật mượt mà
 * Thiết kế chuẩn theo bố cục hiển thị 10 cột:
 * 0: # (STT / Checkbox)
 * 1: Máy chủ
 * 2: Địa chỉ
 * 3: Giao thức
 * 4: Trạm đo
 * 5: Trạng thái
 * 6: Độ trễ
 * 7: Nhịp phản hồi (Heartbeat Bar 60 slots)
 * 8: Khả dụng
 * 9: Giám sát (Công tắc bật/tắt)
 */
public class ServerTableModel extends AbstractTableModel {
    private final String[] columnNames = {
            I18n.get("table.col.index", "#"),
            I18n.get("table.col.name", "Máy chủ"),
            I18n.get("table.col.address", "Địa chỉ"),
            I18n.get("table.col.protocol", "Giao thức"),
            I18n.get("table.col.agent", "Trạm đo"),
            I18n.get("table.col.status", "Trạng thái"),
            I18n.get("table.col.latency", "Độ trễ"),
            I18n.get("table.col.heartbeat", "Nhịp phản hồi"),
            I18n.get("table.col.uptime", "Khả dụng"),
            I18n.get("table.col.monitor", "Giám sát")
    };

    private final List<ServerTarget> targets = new ArrayList<>();

    public void setTargets(List<ServerTarget> newTargets) {
        this.targets.clear();
        if (newTargets != null) {
            this.targets.addAll(newTargets);
        }
        fireTableDataChanged();
    }

    public void addTarget(ServerTarget target) {
        targets.add(target);
        int newRow = targets.size() - 1;
        fireTableRowsInserted(newRow, newRow);
    }

    public void removeTarget(int targetId) {
        int index = -1;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getId() == targetId) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            targets.remove(index);
            fireTableRowsDeleted(index, index);
        }
    }

    public int updateTarget(ServerTarget target) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getId() == target.getId()) {
                targets.set(i, target);
                fireTableRowsUpdated(i, i);
                return i;
            }
        }
        return -1;
    }

    public ServerTarget getTargetAt(int row) {
        if (row >= 0 && row < targets.size()) {
            return targets.get(row);
        }
        return null;
    }

    public List<ServerTarget> getAllTargets() {
        return new ArrayList<>(targets);
    }

    @Override
    public int getRowCount() {
        return targets.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        switch (columnIndex) {
            case 0: return Integer.class;
            case 1: case 2: return String.class;
            case 3: return ProtocolType.class;
            case 4: return String.class;
            case 5: return ServerStatus.class;
            case 6: return String.class;
            case 7: return ServerTarget.class;
            case 8: return String.class;
            case 9: return Boolean.class;
            default: return Object.class;
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        // Chỉ cột Giám sát (công tắc bật/tắt) là có thể tương tác trực tiếp
        return columnIndex == 9;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= targets.size()) return null;
        ServerTarget t = targets.get(rowIndex);

        switch (columnIndex) {
            case 0:
                return rowIndex + 1;
            case 1:
                return t.getName();
            case 2:
                // ICMP chỉ hiển thị host, các giao thức khác host:port
                return (t.getProtocol() == ProtocolType.ICMP)
                        ? t.getHost()
                        : (t.getHost() + ":" + t.getPort());
            case 3:
                return t.getProtocol();
            case 4:
                return t.getAssignedAgent() != null ? t.getAssignedAgent() : "";
            case 5:
                return t.getStatus();
            case 6:
                if (!t.isEnabled() || t.getStatus() == ServerStatus.PAUSED) {
                    return "—";
                }
                return t.getCurrentLatency() >= 0 ? (t.getCurrentLatency() + " ms") : "—";
            case 7:
                return t; // Trả về ServerTarget để HeartbeatBarRenderer tự vẽ dải 60 ô
            case 8:
                return String.format("%.1f%%", t.getUptime24h()).replace('.', ',');
            case 9:
                return t.isEnabled();
            default:
                return null;
        }
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (columnIndex == 9 && rowIndex >= 0 && rowIndex < targets.size()) {
            ServerTarget t = targets.get(rowIndex);
            boolean newEnabled = Boolean.TRUE.equals(aValue);
            t.setEnabled(newEnabled);
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }
}

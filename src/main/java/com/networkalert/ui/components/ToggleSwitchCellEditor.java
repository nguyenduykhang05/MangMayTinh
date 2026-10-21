package com.networkalert.ui.components;

import com.networkalert.model.ServerTarget;
import com.networkalert.ui.theme.ThemeManager;

import javax.swing.*;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.util.EventObject;
import java.util.function.BiConsumer;

/**
 * CellEditor cho cột công tắc Giám sát (bật/tắt ngay lập tức khi click)
 */
public class ToggleSwitchCellEditor extends AbstractCellEditor implements TableCellEditor {
    private final JPanel panel;
    private final ToggleSwitch toggleSwitch;
    private boolean currentValue;
    private BiConsumer<ServerTarget, Boolean> onToggleCallback;
    private ServerTarget currentTarget;

    public ToggleSwitchCellEditor(BiConsumer<ServerTarget, Boolean> onToggle) {
        this.onToggleCallback = onToggle;
        this.panel = new JPanel(new GridBagLayout());
        this.panel.setOpaque(true);

        this.toggleSwitch = new ToggleSwitch();
        this.toggleSwitch.setOnToggle(val -> {
            this.currentValue = val;
            if (currentTarget != null && onToggleCallback != null) {
                onToggleCallback.accept(currentTarget, val);
            }
            stopCellEditing();
        });

        panel.add(toggleSwitch);
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        currentValue = Boolean.TRUE.equals(value);
        toggleSwitch.setSelectedWithoutAnimation(currentValue);

        panel.setBackground(isSelected ? ThemeManager.bgSelected() : ThemeManager.bgSurface());

        int modelRow = table.convertRowIndexToModel(row);
        if (table.getModel() instanceof ServerTableModel) {
            currentTarget = ((ServerTableModel) table.getModel()).getTargetAt(modelRow);
        }

        return panel;
    }

    @Override
    public Object getCellEditorValue() {
        return currentValue;
    }

    @Override
    public boolean isCellEditable(EventObject e) {
        return true;
    }
}

package com.networkalert;

import com.networkalert.ui.theme.I18n;
import com.networkalert.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ThemeTest {
    @Test
    public void testThemeTokensAndI18n() {
        assertNotNull(ThemeManager.bgWindow());
        assertNotNull(ThemeManager.statusOnline());
        assertNotNull(ThemeManager.statusOffline());
        assertNotNull(ThemeManager.statusWarning());
        assertNotNull(ThemeManager.accent());

        assertEquals("Giám sát & cảnh báo kết nối mạng", I18n.get("app.title"));
        assertEquals("Thêm máy chủ", I18n.get("toolbar.add_server"));
        assertEquals("Online", I18n.get("status.online"));
    }
}

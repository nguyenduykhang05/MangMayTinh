package com.networkalert.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.networkalert.model.ProtocolType;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;

/**
 * Quản lý Design Tokens, Font chữ, Bộ Icon SVG và Chuyển đổi Dark/Light mode
 */
public final class ThemeManager {
    private static final String PREF_DARK_MODE = "dark_mode_enabled";
    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);

    private static boolean darkMode = true;
    private static String sansFamily = "Segoe UI";
    private static String monoFamily = "Consolas";

    private static final Map<String, FlatSVGIcon> ICON_CACHE = new ConcurrentHashMap<>();

    private ThemeManager() {}

    /**
     * Khởi tạo Theme trước khi hiển thị bất kỳ JFrame nào
     */
    public static void setup() {
        initFonts();

        // Đăng ký custom properties từ package com.networkalert.theme
        FlatLaf.registerCustomDefaultsSource("com.networkalert.theme");

        // Đọc cấu hình theme đã lưu (mặc định Dark Mode)
        darkMode = PREFS.getBoolean(PREF_DARK_MODE, true);

        // Thiết lập Font mặc định cho toàn bộ ứng dụng (Đảm bảo tiếng Việt không lỗi font)
        Font baseFont = sans(Font.PLAIN, 13);
        Font boldFont = sans(Font.BOLD, 13);
        FontUIResource baseFontRes = new FontUIResource(baseFont);

        UIManager.put("defaultFont", baseFontRes);
        UIManager.put("Label.font", baseFontRes);
        UIManager.put("Button.font", baseFontRes);
        UIManager.put("ToggleButton.font", baseFontRes);
        UIManager.put("RadioButton.font", baseFontRes);
        UIManager.put("CheckBox.font", baseFontRes);
        UIManager.put("ComboBox.font", baseFontRes);
        UIManager.put("TextField.font", baseFontRes);
        UIManager.put("PasswordField.font", baseFontRes);
        UIManager.put("TextArea.font", baseFontRes);
        UIManager.put("TextPane.font", baseFontRes);
        UIManager.put("EditorPane.font", baseFontRes);
        UIManager.put("Table.font", baseFontRes);
        UIManager.put("TableHeader.font", new FontUIResource(boldFont));
        UIManager.put("TabbedPane.font", new FontUIResource(boldFont));
        UIManager.put("ToolTip.font", baseFontRes);
        UIManager.put("TitledBorder.font", new FontUIResource(boldFont));
        UIManager.put("Menu.font", baseFontRes);
        UIManager.put("MenuItem.font", baseFontRes);

        if (darkMode) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
    }

    private static void initFonts() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        boolean loadedSans = false;
        boolean loadedMono = false;

        // Thử nạp IBM Plex Sans từ tài nguyên nếu có
        try (InputStream is = ThemeManager.class.getResourceAsStream("/fonts/IBMPlexSans-Regular.ttf")) {
            if (is != null) {
                Font f = Font.createFont(Font.TRUETYPE_FONT, is);
                if (testVietnameseSupport(f)) {
                    ge.registerFont(f);
                    sansFamily = f.getFamily();
                    loadedSans = true;
                }
            }
        } catch (Exception ignored) {}

        // Thử nạp IBM Plex Mono từ tài nguyên nếu có
        try (InputStream is = ThemeManager.class.getResourceAsStream("/fonts/IBMPlexMono-Regular.ttf")) {
            if (is != null) {
                Font f = Font.createFont(Font.TRUETYPE_FONT, is);
                if (testVietnameseSupport(f)) {
                    ge.registerFont(f);
                    monoFamily = f.getFamily();
                    loadedMono = true;
                }
            }
        } catch (Exception ignored) {}

        // Ưu tiên font hệ thống Windows hỗ trợ tiếng Việt tuyệt đối
        if (!loadedSans) {
            String[] testSans = {"Segoe UI", "Tahoma", "Arial", "Inter", "IBM Plex Sans", "SansSerif"};
            for (String name : testSans) {
                Font testF = new Font(name, Font.PLAIN, 13);
                if (testVietnameseSupport(testF)) {
                    sansFamily = name;
                    break;
                }
            }
        }

        if (!loadedMono) {
            String[] testMono = {"Cascadia Mono", "Cascadia Code", "Segoe UI", "Consolas", "Courier New", "Monospaced"};
            for (String name : testMono) {
                Font testF = new Font(name, Font.PLAIN, 12);
                if (testVietnameseSupport(testF)) {
                    monoFamily = name;
                    break;
                }
            }
        }
    }

    private static boolean testVietnameseSupport(Font font) {
        // Chuỗi chứa các ký tự có dấu phức tạp trong tiếng Việt (dạng Unicode Escape an toàn 100%)
        String testString = "\u1EAF \u1EB1 \u1EB3 \u1EB5 \u1EB7 \u1EBF \u1EC1 \u1EC3 \u1EC5 \u1EC7 \u1ED1 \u1ED3 \u1ED5 \u1ED7 \u1ED9 \u1EDB \u1EDD \u1EDF \u1EE1 \u1EE3 \u1EE9 \u1EEB \u1EED \u1EEF \u1EF1 \u0111 \u0110";
        return font.canDisplayUpTo(testString) == -1;
    }

    public static Font sans(int style, float size) {
        return new Font(sansFamily, style, (int) size).deriveFont(size);
    }

    public static Font mono(int style, float size) {
        return new Font(monoFamily, style, (int) size).deriveFont(size);
    }

    public static boolean isDarkMode() {
        return darkMode;
    }

    /**
     * Chuyển đổi giao diện Sáng / Tối mượt mà không cần khởi động lại ứng dụng
     */
    public static void toggleTheme(Window window) {
        setDarkMode(!darkMode, window);
    }

    public static void setDarkMode(boolean dark, Window window) {
        if (darkMode == dark) return;
        darkMode = dark;
        PREFS.putBoolean(PREF_DARK_MODE, darkMode);

        FlatAnimatedLafChange.showSnapshot();
        if (darkMode) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        FlatLaf.updateUI();
        FlatAnimatedLafChange.hideSnapshotWithAnimation();

        if (window != null) {
            window.repaint();
        }
    }

    /**
     * Nạp và tạo cache FlatSVGIcon từ resources/icons
     */
    public static FlatSVGIcon getIcon(String name, int size) {
        String key = name + "_" + size;
        return ICON_CACHE.computeIfAbsent(key, k -> {
            try {
                return new FlatSVGIcon("icons/" + name + ".svg", size, size);
            } catch (Exception e) {
                System.err.println("Không thể nạp icon: icons/" + name + ".svg (" + e.getMessage() + ")");
                return null;
            }
        });
    }

    public static FlatSVGIcon getIcon(String name) {
        return getIcon(name, 16);
    }

    // ==========================================
    // DESIGN TOKENS (Mục 3.1)
    // ==========================================
    public static Color getColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    public static Color bgWindow() {
        return getColor("App.bg.window", darkMode ? new Color(0x0B, 0x11, 0x1E) : new Color(0xF8, 0xFA, 0xFC));
    }

    public static Color bgSurface() {
        return getColor("App.bg.surface", darkMode ? new Color(0x13, 0x1D, 0x2E) : new Color(0xFF, 0xFF, 0xFF));
    }

    public static Color bgSurfaceAlt() {
        return getColor("App.bg.surfaceAlt", darkMode ? new Color(0x1C, 0x27, 0x3D) : new Color(0xF1, 0xF5, 0xF9));
    }

    public static Color bgHover() {
        return getColor("App.bg.hover", darkMode ? new Color(0x23, 0x33, 0x4D) : new Color(0xE2, 0xE8, 0xF0));
    }

    public static Color bgSelected() {
        return getColor("App.bg.selected", darkMode ? new Color(0x1D, 0x32, 0x54) : new Color(0xDB, 0xEA, 0xFE));
    }

    public static Color border() {
        return getColor("App.border", darkMode ? new Color(0x23, 0x30, 0x48) : new Color(0xE2, 0xE8, 0xF0));
    }

    public static Color borderStrong() {
        return getColor("App.border.strong", darkMode ? new Color(0x33, 0x44, 0x60) : new Color(0xCB, 0xD5, 0xE1));
    }

    public static Color textPrimary() {
        return getColor("App.text.primary", darkMode ? new Color(0xF1, 0xF5, 0xF9) : new Color(0x0F, 0x17, 0x2A));
    }

    public static Color textSecondary() {
        return getColor("App.text.secondary", darkMode ? new Color(0x94, 0xA3, 0xB8) : new Color(0x47, 0x55, 0x69));
    }

    public static Color textMuted() {
        return getColor("App.text.muted", darkMode ? new Color(0x64, 0x74, 0x8B) : new Color(0x94, 0xA3, 0xB8));
    }

    public static Color accent() {
        return getColor("App.accent", darkMode ? new Color(0x3B, 0x82, 0xF6) : new Color(0x25, 0x63, 0xEB));
    }

    public static Color accentFill() {
        return getColor("App.accent.fill", darkMode ? new Color(0x3B, 0x82, 0xF6) : new Color(0x25, 0x63, 0xEB));
    }

    public static Color statusOnline() {
        return getColor("App.status.online", darkMode ? new Color(0x10, 0xB9, 0x81) : new Color(0x05, 0x96, 0x69));
    }

    public static Color statusWarning() {
        return getColor("App.status.warning", darkMode ? new Color(0xF5, 0x9E, 0x0B) : new Color(0xD9, 0x77, 0x06));
    }

    public static Color statusOffline() {
        return getColor("App.status.offline", darkMode ? new Color(0xEF, 0x44, 0x44) : new Color(0xDC, 0x26, 0x26));
    }

    public static Color statusNeutral() {
        return getColor("App.status.neutral", new Color(0x64, 0x74, 0x8B));
    }

    public static Color statusUnreachable() {
        return getColor("App.status.unreachable", darkMode ? new Color(0x94, 0xA3, 0xB8) : new Color(0x64, 0x74, 0x8B));
    }

    public static Color statusMaintenance() {
        return getColor("App.status.maintenance", darkMode ? new Color(0x3B, 0x82, 0xF6) : new Color(0x25, 0x63, 0xEB));
    }

    public static Color statusMaintenanceText() {
        return getColor("App.status.maintenanceText", darkMode ? new Color(0x60, 0xA5, 0xFA) : new Color(0x1D, 0x4E, 0xD8));
    }

    public static Color statusOnlineText() {
        return getColor("App.status.onlineText", darkMode ? new Color(0x34, 0xD3, 0x99) : new Color(0x06, 0x5F, 0x46));
    }

    public static Color statusWarningText() {
        return getColor("App.status.warningText", darkMode ? new Color(0xFB, 0xBF, 0x24) : new Color(0x92, 0x40, 0x0E));
    }

    public static Color statusOfflineText() {
        return getColor("App.status.offlineText", darkMode ? new Color(0xF8, 0x71, 0x71) : new Color(0x99, 0x1B, 0x1B));
    }

    /**
     * Màu chấm giao thức thanh lịch (TCP, HTTP, HTTPS, UDP, ICMP, DNS)
     */
    public static Color getProtocolColor(ProtocolType type) {
        if (type == null) return statusNeutral();
        switch (type) {
            case TCP:
                return darkMode ? new Color(0x60, 0xA5, 0xFA) : new Color(0x25, 0x63, 0xEB);
            case HTTP:
                return darkMode ? new Color(0x34, 0xD3, 0x99) : new Color(0x05, 0x96, 0x69);
            case HTTPS:
                return darkMode ? new Color(0x2D, 0xD4, 0xBF) : new Color(0x0D, 0x94, 0x88);
            case UDP:
                return darkMode ? new Color(0x81, 0x8C, 0xF8) : new Color(0x4F, 0x46, 0xE5);
            case ICMP:
                return darkMode ? new Color(0xA7, 0x8B, 0xFA) : new Color(0x7C, 0x3A, 0xED);
            case DNS:
                return darkMode ? new Color(0xFB, 0xBF, 0x24) : new Color(0xD9, 0x77, 0x06);
            default:
                return statusNeutral();
        }
    }
}

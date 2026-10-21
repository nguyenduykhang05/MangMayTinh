package com.networkalert.ui.theme;

import com.networkalert.util.LoggerUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Quản lý ngôn ngữ và chuỗi hiển thị ứng dụng (Tiếng Việt & English)
 */
public final class I18n {
    private static ResourceBundle bundle;
    private static Locale currentLocale = new Locale("vi", "VN");

    static {
        loadBundle(currentLocale);
    }

    private I18n() {}

    public static synchronized void setLocale(Locale locale) {
        if (locale != null) {
            currentLocale = locale;
            loadBundle(locale);
        }
    }

    public static synchronized Locale getLocale() {
        return currentLocale;
    }

    public static synchronized void loadBundle(Locale locale) {
        String lang = locale.getLanguage();
        String resourcePath = "/com/networkalert/i18n/messages_" + (lang.equalsIgnoreCase("en") ? "en" : "vi") + ".properties";
        try (InputStream stream = I18n.class.getResourceAsStream(resourcePath)) {
            if (stream != null) {
                try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    bundle = new PropertyResourceBundle(reader);
                }
            } else {
                bundle = ResourceBundle.getBundle("com.networkalert.i18n.messages_" + lang, locale);
            }
        } catch (Exception e) {
            LoggerUtil.error("Không thể nạp ResourceBundle messages: " + e.getMessage());
        }
    }

    public static String get(String key) {
        if (bundle != null && bundle.containsKey(key)) {
            return bundle.getString(key);
        }
        return key;
    }

    public static String get(String key, Object... args) {
        String pattern = get(key);
        try {
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }
}

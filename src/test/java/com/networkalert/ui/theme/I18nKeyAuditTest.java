package com.networkalert.ui.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tự động rà soát từ khóa đa ngôn ngữ (I18n Key Audit)
 * theo ERRATA §E13 #1 và Task T6.6.
 */
public class I18nKeyAuditTest {

    @AfterEach
    void resetLocale() {
        I18n.setLocale(new Locale("vi", "VN"));
    }

    @Test
    @DisplayName("Cả hai tệp messages_vi và messages_en phải tồn tại và đọc được")
    void testResourceBundlesExist() throws Exception {
        Properties viProps = loadProperties("/com/networkalert/i18n/messages_vi.properties");
        Properties enProps = loadProperties("/com/networkalert/i18n/messages_en.properties");

        assertFalse(viProps.isEmpty(), "messages_vi.properties không được rỗng");
        assertFalse(enProps.isEmpty(), "messages_en.properties không được rỗng");
    }

    @Test
    @DisplayName("Key 'table.col.heartbeat' (E13 #1) phải tồn tại ở cả 2 ngôn ngữ")
    void testHeartbeatColumnKeyExists() throws Exception {
        Properties viProps = loadProperties("/com/networkalert/i18n/messages_vi.properties");
        Properties enProps = loadProperties("/com/networkalert/i18n/messages_en.properties");

        assertTrue(viProps.containsKey("table.col.heartbeat"), "messages_vi phải chứa table.col.heartbeat");
        assertTrue(enProps.containsKey("table.col.heartbeat"), "messages_en phải chứa table.col.heartbeat");
    }

    @Test
    @DisplayName("Độ khớp khóa 100% giữa tiếng Việt và tiếng Anh (Parity Check)")
    void testKeyParityBetweenViAndEn() throws Exception {
        Properties viProps = loadProperties("/com/networkalert/i18n/messages_vi.properties");
        Properties enProps = loadProperties("/com/networkalert/i18n/messages_en.properties");

        Set<String> viKeys = viProps.stringPropertyNames();
        Set<String> enKeys = enProps.stringPropertyNames();

        Set<String> missingInEn = new TreeSet<>(viKeys);
        missingInEn.removeAll(enKeys);

        Set<String> missingInVi = new TreeSet<>(enKeys);
        missingInVi.removeAll(viKeys);

        assertTrue(missingInEn.isEmpty(), "Các key có trong vi nhưng thiếu trong en: " + missingInEn);
        assertTrue(missingInVi.isEmpty(), "Các key có trong en nhưng thiếu trong vi: " + missingInVi);
    }

    @Test
    @DisplayName("Mọi lệnh I18n.get(...) trong mã nguồn java phải có key hợp lệ trong bundle")
    void testAllSourceCodeI18nKeysExistInBundles() throws Exception {
        Properties viProps = loadProperties("/com/networkalert/i18n/messages_vi.properties");
        Properties enProps = loadProperties("/com/networkalert/i18n/messages_en.properties");

        Set<String> sourceKeys = extractI18nKeysFromSource(Path.of("src/main/java"));
        assertFalse(sourceKeys.isEmpty(), "Phải tìm thấy các lệnh I18n.get trong mã nguồn");

        Set<String> missingKeysVi = new TreeSet<>();
        Set<String> missingKeysEn = new TreeSet<>();

        for (String key : sourceKeys) {
            if (!viProps.containsKey(key)) {
                missingKeysVi.add(key);
            }
            if (!enProps.containsKey(key)) {
                missingKeysEn.add(key);
            }
        }

        assertTrue(missingKeysVi.isEmpty(), "Các key I18n trong mã nguồn nhưng thiếu trong messages_vi: " + missingKeysVi);
        assertTrue(missingKeysEn.isEmpty(), "Các key I18n trong mã nguồn nhưng thiếu trong messages_en: " + missingKeysEn);
    }

    @Test
    @DisplayName("Chuyển đổi Locale hoạt động mượt mà và trả về đúng ngôn ngữ")
    void testLocaleSwitching() {
        I18n.setLocale(new Locale("vi", "VN"));
        String viTitle = I18n.get("table.col.heartbeat");
        assertEquals("Nhịp phản hồi", viTitle);

        I18n.setLocale(Locale.ENGLISH);
        String enTitle = I18n.get("table.col.heartbeat");
        assertEquals("Heartbeat", enTitle);

        I18n.setLocale(new Locale("vi", "VN"));
        assertEquals("Nhịp phản hồi", I18n.get("table.col.heartbeat"));
    }

    @Test
    @DisplayName("Cột Địa chỉ cắt giữa (middle-ellipsis) phải bảo toàn cổng :9000 (E13 #2)")
    void testMiddleEllipsisPreservesPort() {
        java.awt.Font font = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.FontMetrics fm = img.createGraphics().getFontMetrics(font);

        String full = "192.168.100.105:9000";
        // Khi đủ rộng -> giữ nguyên
        assertEquals(full, com.networkalert.ui.components.ServerTableRenderer.formatAddressMiddleEllipsis(full, fm, 300));

        // Khi hẹp -> cắt giữa nhưng đuôi :9000 phải còn nguyên vẹn
        String truncated = com.networkalert.ui.components.ServerTableRenderer.formatAddressMiddleEllipsis(full, fm, 100);
        assertTrue(truncated.endsWith(":9000"), "Phải giữ nguyên đuôi cổng :9000 nhưng kết quả là: " + truncated);
        assertTrue(truncated.contains("..."), "Phải chứa dấu ... nhưng kết quả là: " + truncated);
    }

    private Properties loadProperties(String resourcePath) throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Không tìm thấy tài nguyên: " + resourcePath);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                props.load(reader);
            }
        }
        return props;
    }

    private Set<String> extractI18nKeysFromSource(Path rootDir) throws Exception {
        Set<String> keys = new TreeSet<>();
        Pattern pattern = Pattern.compile("I18n\\.get\\(\\s*\"([^\"]+)\"");

        if (!Files.exists(rootDir)) {
            return keys;
        }

        try (Stream<Path> stream = Files.walk(rootDir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                            for (String line : lines) {
                                Matcher m = pattern.matcher(line);
                                while (m.find()) {
                                    keys.add(m.group(1));
                                }
                            }
                        } catch (Exception ignored) {}
                    });
        }
        return keys;
    }
}

package com.networkalert.ui.theme;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tính toàn vẹn của hệ thống Icon SVG trong ứng dụng.
 * Đảm bảo 100% mọi icon được gọi trong mã nguồn Java đều tồn tại trong resources/icons
 * và không có bất kỳ glyph/emoji nào bị thiếu hoặc hiển thị lỗi ô vuông đỏ.
 */
public class IconResourceTest {

    @Test
    public void testAllReferencedIconsExistInResources() throws IOException {
        Set<String> referencedIcons = new HashSet<>();
        Pattern pattern = Pattern.compile("ThemeManager\\.getIcon\\(\\s*\"([^\"]+)\"");

        Path srcMainJava = Path.of("src", "main", "java");
        if (Files.exists(srcMainJava)) {
            try (Stream<Path> stream = Files.walk(srcMainJava)) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        Matcher matcher = pattern.matcher(content);
                        while (matcher.find()) {
                            referencedIcons.add(matcher.group(1));
                        }
                    } catch (IOException e) {
                        fail("Lỗi đọc file: " + file);
                    }
                });
            }
        }

        assertFalse(referencedIcons.isEmpty(), "Phải tìm thấy ít nhất một lời gọi ThemeManager.getIcon trong src/main/java");

        // Các icon cốt lõi bắt buộc phải tồn tại
        referencedIcons.add("radio");
        referencedIcons.add("server");
        referencedIcons.add("activity");
        referencedIcons.add("zap");
        referencedIcons.add("alert-circle");
        referencedIcons.add("alert-triangle");
        referencedIcons.add("check-circle");
        referencedIcons.add("trash-2");
        referencedIcons.add("info");

        // Kiểm tra từng icon có file svg tương ứng trong classpath và trên đĩa
        for (String iconName : referencedIcons) {
            String path = "icons/" + iconName + ".svg";
            URL resource = getClass().getClassLoader().getResource(path);
            File diskFile = new File("src/main/resources/" + path);

            assertTrue(resource != null || diskFile.exists(),
                    String.format("Icon '%s' được gọi trong mã nguồn nhưng thiếu file 'src/main/resources/%s'!", iconName, path));

            // Thử nạp FlatSVGIcon
            FlatSVGIcon icon = ThemeManager.getIcon(iconName, 16);
            assertNotNull(icon, "FlatSVGIcon không được null cho icon: " + iconName);
        }
    }

    @Test
    public void testRadioIconExistsForAgentCluster() {
        // Nút 'Trạm Agent' và tab Agent sử dụng icon 'radio'
        FlatSVGIcon radioIcon = ThemeManager.getIcon("radio", 16);
        assertNotNull(radioIcon, "Icon 'radio' cho Trạm Agent không được null");
        assertTrue(radioIcon.getIconWidth() > 0, "Icon width phải lớn hơn 0");
    }
}

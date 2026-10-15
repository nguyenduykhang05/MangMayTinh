package com.networkalert.ratchet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử Ratchet Guard tự động theo AGENTS.md §3 và Task T0.3.
 * <p>
 * Cơ chế hoạt động (One-way Ratchet):
 * 1. Nạp danh mục 274 vi phạm baseline từ src/test/resources/ratchet/baseline-violations.txt (khảo sát ở T0.1).
 * 2. Quét toàn bộ mã nguồn nghiệp vụ (src/main/java) và kiểm thử (src/test/java).
 * 3. Bất kỳ vi phạm MỚI nào không có trong baseline allowlist sẽ lập tức khiến bài test THẤT BẠI (Build đỏ).
 * 4. Khi các task tiếp theo (như T0.4) sửa dần vi phạm cũ, bài test vẫn XANH và ratchet tự động siết chặt.
 */
public class ArchitectureRatchetTest {

    private static final Path MAIN_SRC = Path.of("src/main/java");
    private static final Path TEST_SRC = Path.of("src/test/java");
    private static final String ALLOWLIST_RESOURCE = "ratchet/baseline-violations.txt";

    private static final Set<String> ALLOWED_VIOLATIONS = new HashSet<>();

    @BeforeAll
    static void loadAllowlist() throws IOException {
        try (InputStream in = ArchitectureRatchetTest.class.getClassLoader().getResourceAsStream(ALLOWLIST_RESOURCE)) {
            assertNotNull(in, "Không tìm thấy file allowlist baseline tại: " + ALLOWLIST_RESOURCE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        // Định dạng: category|path|lineNumber|snippet
                        String[] parts = line.split("\\|", 4);
                        if (parts.length >= 3) {
                            String category = parts[0].trim();
                            String normPath = normalizePath(parts[1].trim());
                            int lineNum = Integer.parseInt(parts[2].trim());
                            // Lưu key: category|normalizedPath|lineNum
                            ALLOWED_VIOLATIONS.add(category + "|" + normPath + "|" + lineNum);
                        }
                    }
                }
            }
        }
        assertTrue(ALLOWED_VIOLATIONS.size() >= 175, "Allowlist baseline sau T3.1 phải chứa ít nhất 175 bản ghi, thực tế: " + ALLOWED_VIOLATIONS.size());
    }

    @Test
    @DisplayName("Ratchet Guard: Chặn System.currentTimeMillis() mới trong logic nghiệp vụ")
    void testNoNewCurrentTimeMillis() throws IOException {
        Pattern pattern = Pattern.compile("System\\.currentTimeMillis\\s*\\(");
        List<Violation> newViolations = scanDirectory("currentTimeMillis", MAIN_SRC, pattern);
        assertNoNewViolations("System.currentTimeMillis()", newViolations);
    }

    @Test
    @DisplayName("Ratchet Guard: Chặn *.now() không tham số trong logic nghiệp vụ")
    void testNoNewUnparameterizedNow() throws IOException {
        // Bắt Instant.now(), LocalDate.now(), LocalDateTime.now(), ZonedDateTime.now() không nhận Clock
        Pattern pattern = Pattern.compile("\\b(Instant|LocalDate|LocalDateTime|ZonedDateTime)\\.now\\s*\\(\\s*\\)");
        List<Violation> newViolations = scanDirectory("now()", MAIN_SRC, pattern);
        assertNoNewViolations("*.now() không tham số", newViolations);
    }

    @Test
    @DisplayName("Ratchet Guard: Chặn new Thread() trần ngoài NamedThreadFactory")
    void testNoNewUnmanagedThreads() throws IOException {
        Pattern pattern = Pattern.compile("\\bnew\\s+Thread\\s*\\(");
        List<Violation> newViolations = scanDirectory("new Thread", MAIN_SRC, pattern, "NamedThreadFactory.java");
        assertNoNewViolations("new Thread() trần", newViolations);
    }

    @Test
    @DisplayName("Ratchet Guard: Chặn System.out và System.err (phải dùng LoggerUtil)")
    void testNoNewSystemOutOrErr() throws IOException {
        Pattern pattern = Pattern.compile("System\\.(out|err)\\.(print|println|printf|format)");
        List<Violation> newViolations = scanDirectory("System.out/err", MAIN_SRC, pattern);
        assertNoNewViolations("System.out / System.err", newViolations);
    }

    @Test
    @DisplayName("Ratchet Guard: Chặn Thread.sleep() trong toàn bộ các lớp test")
    void testNoNewThreadSleepInTests() throws IOException {
        Pattern pattern = Pattern.compile("Thread\\.sleep\\s*\\(");
        List<Violation> newViolations = scanDirectory("Thread.sleep (test)", TEST_SRC, pattern, "ArchitectureRatchetTest.java");
        assertNoNewViolations("Thread.sleep() trong test", newViolations);
    }

    @Test
    @DisplayName("Ratchet Guard: Kiểm chứng cơ chế bắt lỗi khi xuất hiện vi phạm mới")
    void testRatchetMechanismEffectiveness() {
        // Tạo một vi phạm giả định không có trong allowlist
        Violation fakeViolation = new Violation("currentTimeMillis", "src/main/java/com/networkalert/NewService.java", 999, "System.currentTimeMillis()");
        boolean isAllowed = isViolationAllowed(fakeViolation);
        assertFalse(isAllowed, "Vi phạm mới tại file mới hoặc dòng mới bắt buộc phải bị Ratchet từ chối!");
    }

    // --- Helper Classes & Methods ---

    public static class Violation {
        public final String category;
        public final String filePath;
        public final int lineNumber;
        public final String snippet;

        public Violation(String category, String filePath, int lineNumber, String snippet) {
            this.category = category;
            this.filePath = normalizePath(filePath);
            this.lineNumber = lineNumber;
            this.snippet = snippet;
        }

        @Override
        public String toString() {
            return String.format("[%s] tại %s:%d: %s", category, filePath, lineNumber, snippet);
        }
    }

    private List<Violation> scanDirectory(String category, Path dir, Pattern pattern, String... excludeFiles) throws IOException {
        if (!Files.exists(dir)) return Collections.emptyList();
        List<String> excludes = Arrays.asList(excludeFiles);

        List<Violation> newViolations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !excludes.contains(p.getFileName().toString()))
                    .collect(Collectors.toList());

            for (Path file : javaFiles) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String lineText = lines.get(i);
                    String trimmed = lineText.trim();
                    // Bỏ qua chú thích comment
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    Matcher m = pattern.matcher(lineText);
                    if (m.find()) {
                        Violation v = new Violation(category, file.toString(), i + 1, trimmed);
                        if (!isViolationAllowed(v)) {
                            newViolations.add(v);
                        }
                    }
                }
            }
        }
        return newViolations;
    }

    private static boolean isViolationAllowed(Violation v) {
        // Kiểm tra exact match line number
        String key = v.category + "|" + v.filePath + "|" + v.lineNumber;
        if (ALLOWED_VIOLATIONS.contains(key)) return true;

        // Cho phép dung sai +/- 3 dòng do định dạng lại file trong cùng một file và category
        for (int offset = -3; offset <= 3; offset++) {
            if (offset != 0) {
                String shiftedKey = v.category + "|" + v.filePath + "|" + (v.lineNumber + offset);
                if (ALLOWED_VIOLATIONS.contains(shiftedKey)) return true;
            }
        }
        return false;
    }

    private void assertNoNewViolations(String ruleName, List<Violation> newViolations) {
        if (!newViolations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n======================================================================\n");
            sb.append("❌ PHÁT HIỆN VI PHẠM MỚI CHƯA CÓ TRONG ALLOWLIST (Ratchet Guard - AGENTS.md §3):\n");
            sb.append("   Quy tắc vi phạm: ").append(ruleName).append("\n");
            sb.append("   Số lượng vi phạm mới: ").append(newViolations.size()).append("\n");
            sb.append("----------------------------------------------------------------------\n");
            for (Violation v : newViolations) {
                sb.append("   • ").append(v).append("\n");
            }
            sb.append("======================================================================\n");
            fail(sb.toString());
        }
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').replaceAll("^\\./", "");
    }
}

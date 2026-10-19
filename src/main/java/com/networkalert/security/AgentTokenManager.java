package com.networkalert.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.networkalert.util.LoggerUtil;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý kho định danh và Token xác thực của các Agent trên NCAS Server.
 * <p>
 * Thực thi các yêu cầu nghiêm ngặt theo ERRATA §E2.2:
 * <ul>
 *   <li>Token 256-bit (32 bytes ngẫu nhiên từ {@link SecureRandom})</li>
 *   <li>Salt ngẫu nhiên 16 bytes sinh riêng cho từng Agent</li>
 *   <li>Băm an toàn {@code H = SHA-256(salt || token)}, chỉ lưu H và salt trong file dữ liệu</li>
 *   <li>So sánh thời gian hằng số (Constant-time) qua {@link MessageDigest#isEqual(byte[], byte[])} để chống Timing Attack</li>
 *   <li>CLI hỗ trợ: create (in token đúng 1 lần), revoke, list (không bao giờ lộ token/hash)</li>
 * </ul>
 * </p>
 */
public class AgentTokenManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Path DEFAULT_STORAGE_PATH = Path.of("data/agents.json");

    private final Path storagePath;
    private final Clock clock;
    private final Map<String, AgentTokenEntry> tokens = new ConcurrentHashMap<>();

    public AgentTokenManager() {
        this(DEFAULT_STORAGE_PATH, Clock.systemUTC());
    }

    public AgentTokenManager(Path storagePath, Clock clock) {
        this.storagePath = storagePath != null ? storagePath : DEFAULT_STORAGE_PATH;
        this.clock = clock != null ? clock : Clock.systemUTC();
        loadFromStorage();
    }

    /**
     * Tạo mới token 256-bit cho một Agent với vai trò mặc định "agent".
     *
     * @param agentId Mã định danh Agent
     * @return Chuỗi token ngẫu nhiên bản rõ (chỉ trả về và in một lần duy nhất)
     */
    public synchronized String createToken(String agentId) {
        return createToken(agentId, "agent");
    }

    /**
     * Tạo mới token 256-bit cho Agent với vai trò chỉ định.
     */
    public synchronized String createToken(String agentId, String role) {
        if (agentId == null || agentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent ID không được để trống!");
        }
        String cleanId = agentId.trim();

        // 1. Sinh Salt ngẫu nhiên 16 bytes
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        String saltHex = bytesToHex(salt);

        // 2. Sinh Token ngẫu nhiên 256 bit (32 bytes)
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String rawToken = bytesToHex(tokenBytes);

        // 3. Tính H = SHA-256(salt || rawToken)
        byte[] hash = computeHashBytes(salt, rawToken);
        String hashHex = bytesToHex(hash);

        // 4. Lưu bản ghi
        AgentTokenEntry entry = new AgentTokenEntry(cleanId, saltHex, hashHex, role, clock.millis());
        tokens.put(cleanId, entry);
        saveToStorage();

        LoggerUtil.info(String.format("🔐 [AgentTokenManager] Đã tạo thành công Token mới cho Agent '%s' (Vai trò: %s)",
                cleanId, role));
        return rawToken;
    }

    /**
     * Thu hồi quyền truy cập (Revoke) của một Agent.
     */
    public synchronized boolean revokeToken(String agentId) {
        if (agentId == null) return false;
        String cleanId = agentId.trim();
        AgentTokenEntry entry = tokens.get(cleanId);
        if (entry != null && !entry.isRevoked()) {
            entry.setRevoked(true);
            entry.setRevokedAt(clock.millis());
            saveToStorage();
            LoggerUtil.warn(String.format("🚫 [AgentTokenManager] Đã thu hồi Token của Agent '%s'", cleanId));
            return true;
        }
        return false;
    }

    /**
     * Xác thực Token bản rõ của Agent chống Timing Attack bằng {@link MessageDigest#isEqual(byte[], byte[])}.
     */
    public boolean verifyToken(String agentId, String rawToken) {
        if (agentId == null || rawToken == null) return false;
        AgentTokenEntry entry = tokens.get(agentId.trim());
        if (entry == null || entry.isRevoked()) {
            return false;
        }

        byte[] saltBytes = hexToBytes(entry.getSaltHex());
        byte[] expectedHash = hexToBytes(entry.getHashHex());
        byte[] computedHash = computeHashBytes(saltBytes, rawToken);

        return MessageDigest.isEqual(expectedHash, computedHash);
    }

    /**
     * Kiểm tra giá trị băm đã tính sẵn có khớp với giá trị băm đã lưu trong kho hay không.
     */
    public boolean verifyHash(String agentId, byte[] candidateHash) {
        if (agentId == null || candidateHash == null) return false;
        AgentTokenEntry entry = tokens.get(agentId.trim());
        if (entry == null || entry.isRevoked()) {
            return false;
        }
        byte[] expectedHash = hexToBytes(entry.getHashHex());
        return MessageDigest.isEqual(expectedHash, candidateHash);
    }

    public boolean hasToken(String agentId) {
        if (agentId == null) return false;
        AgentTokenEntry entry = tokens.get(agentId.trim());
        return entry != null && !entry.isRevoked();
    }

    public AgentTokenEntry getEntry(String agentId) {
        if (agentId == null) return null;
        return tokens.get(agentId.trim());
    }

    /**
     * Đăng ký thủ công một bản ghi băm (Hỗ trợ cấu hình trước hoặc nạp từ môi trường lab/test).
     */
    public synchronized void registerPrecalculatedToken(String agentId, String saltHex, String hashHex, String role) {
        String cleanId = agentId.trim();
        AgentTokenEntry entry = new AgentTokenEntry(cleanId, saltHex, hashHex, role, clock.millis());
        tokens.put(cleanId, entry);
        saveToStorage();
    }

    /**
     * Danh sách siêu dữ liệu token an toàn (không chứa token/hash).
     */
    public List<AgentTokenInfo> listTokens() {
        List<AgentTokenInfo> list = new ArrayList<>();
        for (AgentTokenEntry e : tokens.values()) {
            list.add(e.toInfo());
        }
        list.sort(Comparator.comparing(AgentTokenInfo::getAgentId));
        return Collections.unmodifiableList(list);
    }

    // --- Tính toán mã băm an toàn SHA-256 ---

    public static byte[] computeHashBytes(byte[] salt, String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (salt != null) {
                md.update(salt);
            }
            if (rawToken != null) {
                md.update(rawToken.getBytes(StandardCharsets.UTF_8));
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Không tìm thấy thuật toán SHA-256 trong JVM!", e);
        }
    }

    public static String computeHashHex(String saltHex, String rawToken) {
        byte[] saltBytes = (saltHex != null && !saltHex.isEmpty()) ? hexToBytes(saltHex) : new byte[0];
        byte[] hash = computeHashBytes(saltBytes, rawToken);
        return bytesToHex(hash);
    }

    // --- Đọc và ghi dữ liệu ra tệp JSON an toàn ---

    private synchronized void loadFromStorage() {
        tokens.clear();
        if (!Files.exists(storagePath)) {
            return;
        }
        try {
            String json = Files.readString(storagePath, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, AgentTokenEntry>>() {}.getType();
            Map<String, AgentTokenEntry> loaded = GSON.fromJson(json, type);
            if (loaded != null) {
                tokens.putAll(loaded);
            }
        } catch (Exception e) {
            LoggerUtil.warn("[AgentTokenManager] Không thể nạp kho token từ " + storagePath + ": " + e.getMessage());
        }
    }

    private synchronized void saveToStorage() {
        try {
            Path parent = storagePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String json = GSON.toJson(tokens);
            // Ghi file tạm rồi di chuyển nguyên tử (Atomic replace)
            Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), "agents-", ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, storagePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                // Fallback nếu OS không hỗ trợ atomic move
                Files.move(temp, storagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LoggerUtil.error("[AgentTokenManager] Lỗi lưu kho token vào " + storagePath + ": " + e.getMessage());
        }
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        String clean = hex.replaceAll("\\s+", "");
        int len = clean.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Chuỗi hex không hợp lệ (chiều dài lẻ): " + clean);
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return data;
    }
}

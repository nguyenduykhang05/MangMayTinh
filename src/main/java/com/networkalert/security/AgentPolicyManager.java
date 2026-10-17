package com.networkalert.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.networkalert.util.LoggerUtil;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Quản lý và thực thi chính sách an ninh cục bộ của Agent (Agent Security Policy Manager).
 * <p>
 * Áp dụng theo ERRATA §E2.6 nhằm ngăn chặn trường hợp Server bị xâm nhập hoặc cấu hình sai
 * biến Agent thành công cụ quét mạng nội bộ (SSRF / Internal Port Scanning).
 * </p>
 */
public class AgentPolicyManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DEFAULT_POLICY_PATH = Path.of("data/agent-policy.json");

    public static class ValidationResult {
        private final boolean allowed;
        private final String reason;

        public ValidationResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, "Mục tiêu hợp lệ theo chính sách Agent");
        }

        public static ValidationResult reject(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }

    private final Path policyPath;
    private AgentPolicy policy;

    public AgentPolicyManager() {
        this(DEFAULT_POLICY_PATH);
    }

    public AgentPolicyManager(Path policyPath) {
        this.policyPath = policyPath != null ? policyPath : DEFAULT_POLICY_PATH;
        loadPolicy();
    }

    public AgentPolicyManager(AgentPolicy policy) {
        this.policyPath = null;
        this.policy = policy != null ? policy : new AgentPolicy();
    }

    public synchronized void loadPolicy() {
        if (policyPath != null && Files.exists(policyPath)) {
            try {
                String json = Files.readString(policyPath, StandardCharsets.UTF_8);
                AgentPolicy loaded = GSON.fromJson(json, AgentPolicy.class);
                if (loaded != null) {
                    this.policy = loaded;
                    return;
                }
            } catch (Exception e) {
                LoggerUtil.warn("[AgentPolicyManager] Lỗi đọc " + policyPath + ": " + e.getMessage() + ". Dùng chính sách mặc định.");
            }
        }
        this.policy = new AgentPolicy();
    }

    public synchronized void savePolicy() {
        if (policyPath == null) return;
        try {
            Path parent = policyPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(policyPath, GSON.toJson(policy), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LoggerUtil.warn("[AgentPolicyManager] Lỗi lưu chính sách ra " + policyPath + ": " + e.getMessage());
        }
    }

    /**
     * Kiểm tra một mục tiêu Server phân công có tuân thủ chính sách cục bộ của Agent hay không.
     */
    public ValidationResult validateTarget(int targetId, String host, int port, String protocol, int intervalSeconds, int currentTargetCount) {
        if (policy == null) {
            return ValidationResult.ok();
        }

        // 1. Kiểm tra trần số lượng mục tiêu tối đa (maxTargets)
        if (policy.getMaxTargets() > 0 && currentTargetCount >= policy.getMaxTargets()) {
            return ValidationResult.reject(String.format("Vượt quá số lượng mục tiêu tối đa cho phép của Agent (%d/%d)",
                    currentTargetCount, policy.getMaxTargets()));
        }

        // 2. Kiểm tra chu kỳ đo tối thiểu (minIntervalSeconds)
        if (policy.getMinIntervalSeconds() > 0 && intervalSeconds < policy.getMinIntervalSeconds()) {
            return ValidationResult.reject(String.format("Chu kỳ đo %d giây nhỏ hơn mức tối thiểu cho phép %d giây",
                    intervalSeconds, policy.getMinIntervalSeconds()));
        }

        // 3. Kiểm tra danh sách giao thức cho phép (allowedProtocols)
        if (protocol != null && policy.getAllowedProtocols() != null && !policy.getAllowedProtocols().isEmpty()) {
            boolean protoAllowed = false;
            for (String p : policy.getAllowedProtocols()) {
                if (p.equalsIgnoreCase(protocol)) {
                    protoAllowed = true;
                    break;
                }
            }
            if (!protoAllowed) {
                return ValidationResult.reject(String.format("Giao thức '%s' không nằm trong danh sách cho phép của Agent %s",
                        protocol, policy.getAllowedProtocols()));
            }
        }

        // 4. Kiểm tra danh sách dải IP/CIDR cấm (disallowedCidrs, ví dụ metadata 169.254.169.254/32)
        if (host != null && policy.getDisallowedCidrs() != null && !policy.getDisallowedCidrs().isEmpty()) {
            for (String cidr : policy.getDisallowedCidrs()) {
                if (matchesCidr(host.trim(), cidr.trim())) {
                    return ValidationResult.reject(String.format("Địa chỉ mục tiêu '%s' vi phạm dải IP cấm của Agent: %s",
                            host, cidr));
                }
            }
        }

        return ValidationResult.ok();
    }

    /**
     * So khớp địa chỉ IP mục tiêu với dải mạng CIDR (IPv4).
     */
    public static boolean matchesCidr(String hostOrIp, String cidr) {
        if (hostOrIp == null || cidr == null) return false;
        try {
            // Trường hợp CIDR không có dấu gạch chéo mask (ví dụ "169.254.169.254")
            if (!cidr.contains("/")) {
                cidr = cidr + "/32";
            }
            String[] parts = cidr.split("/");
            String networkIp = parts[0];
            int prefixLen = Integer.parseInt(parts[1]);

            int hostInt = ipToInt(hostOrIp);
            int networkInt = ipToInt(networkIp);
            int mask = prefixLen == 0 ? 0 : 0xFFFFFFFF << (32 - prefixLen);

            return (hostInt & mask) == (networkInt & mask);
        } catch (Exception e) {
            // Nếu hostOrIp là tên miền (ví dụ google.com), thử so khớp chuỗi trực tiếp
            return hostOrIp.equalsIgnoreCase(cidr.replace("/32", ""));
        }
    }

    private static int ipToInt(String ip) throws Exception {
        InetAddress inet = InetAddress.getByName(ip);
        byte[] bytes = inet.getAddress();
        if (bytes.length != 4) {
            throw new IllegalArgumentException("Chỉ hỗ trợ dải IPv4 CIDR");
        }
        int val = 0;
        for (byte b : bytes) {
            val = (val << 8) | (b & 0xFF);
        }
        return val;
    }

    public AgentPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(AgentPolicy policy) {
        this.policy = policy;
    }
}

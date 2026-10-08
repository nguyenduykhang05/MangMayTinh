package com.networkalert.validation;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetScope;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NetworkValidationUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bộ xác thực dữ liệu mục tiêu giám sát (Target Validator) theo ERRATA §E11.
 * <p>
 * Thực thi các ràng buộc:
 * <ul>
 *   <li><b>E11.1 Scope MULTI vs IP riêng:</b> Chặn gán Scope MULTI cho RFC 1918, CGNAT, Loopback, Link-Local, ULA.</li>
 *   <li><b>E11.2 Chuẩn hóa đầu vào Target:</b>
 *     <ul>
 *       <li>Host: IP hợp lệ hoặc hostname RFC 1123.</li>
 *       <li>Cổng: 1–65535 (hoặc 0 đối với ICMP/Ping).</li>
 *       <li>Chu kỳ: interval >= 1 giây.</li>
 *       <li>Hạn chờ: timeout <= 0.8 * interval (tối đa 80% chu kỳ).</li>
 *       <li>Tên: <= 128 ký tự, không chứa ký tự điều khiển.</li>
 *       <li>Quan hệ phụ thuộc cha-con: Không có chu trình vòng (DAG check).</li>
 *     </ul>
 *   </li>
 * </ul>
 * </p>
 */
public class TargetValidator {

    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(List<String> errors, List<String> warnings) {
            this.errors = errors != null ? Collections.unmodifiableList(errors) : Collections.emptyList();
            this.warnings = warnings != null ? Collections.unmodifiableList(warnings) : Collections.emptyList();
            this.valid = this.errors.isEmpty();
        }

        public static ValidationResult success() {
            return new ValidationResult(Collections.emptyList(), Collections.emptyList());
        }

        public static ValidationResult successWithWarning(String warning) {
            return new ValidationResult(Collections.emptyList(), Collections.singletonList(warning));
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(Collections.singletonList(error), Collections.emptyList());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(errors, Collections.emptyList());
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }

        public String getFirstError() {
            return errors.isEmpty() ? null : errors.get(0);
        }

        @Override
        public String toString() {
            if (valid) {
                return warnings.isEmpty() ? "VALID" : "VALID with warnings: " + String.join("; ", warnings);
            }
            return "INVALID: " + String.join("; ", errors);
        }
    }

    /**
     * Xác thực toàn diện một mục tiêu với danh sách tất cả mục tiêu hiện có
     */
    public static ValidationResult validate(ServerTarget target, Collection<ServerTarget> allTargets) {
        if (target == null) {
            return ValidationResult.failure("Đối tượng mục tiêu không được null!");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Tên mục tiêu (Name)
        String name = target.getName();
        if (name == null || name.trim().isEmpty()) {
            errors.add("Tên mục tiêu không được để trống!");
        } else {
            if (name.length() > 128) {
                errors.add("Tên mục tiêu không được vượt quá 128 ký tự (Hiện tại: " + name.length() + ")!");
            }
            if (hasControlCharacters(name)) {
                errors.add("Tên mục tiêu không được chứa ký tự điều khiển (control characters)!");
            }
        }

        // 2. Địa chỉ Host
        String host = target.getHost();
        if (host == null || host.trim().isEmpty()) {
            errors.add("Địa chỉ máy chủ (Host) không được để trống!");
        } else {
            String trimmedHost = host.trim();
            if (!NetworkValidationUtil.isValidHost(trimmedHost)) {
                errors.add("Địa chỉ Host '" + host + "' không hợp lệ (Phải là IPv4, IPv6 hoặc Hostname chuẩn RFC 1123)!");
            }
        }

        // 3. Cổng mạng (Port)
        int port = target.getPort();
        ProtocolType protocol = target.getProtocol();
        if (protocol == ProtocolType.ICMP) {
            if (port < 0 || port > 65535) {
                errors.add("Cổng mạng giao thức ICMP phải trong khoảng 0-65535 (Mặc định 0)!");
            }
        } else {
            if (port < 1 || port > 65535) {
                errors.add("Cổng mạng giao thức " + protocol + " phải nằm trong khoảng 1-65535 (Hiện tại: " + port + ")!");
            }
        }

        // 4. Chu kỳ kiểm tra (Interval)
        int interval = target.getIntervalSeconds();
        if (interval < 1) {
            errors.add("Chu kỳ đo kiểm (Interval) phải >= 1 giây (Hiện tại: " + interval + "s)!");
        } else if (interval < 5 && (protocol == ProtocolType.HTTPS || protocol == ProtocolType.DNS || protocol == ProtocolType.HTTP)) {
            warnings.add("Chu kỳ " + interval + "s là khá dày đối với giao thức " + protocol + " (Khuyến nghị sàn >= 5s)!");
        }

        // 5. Thời gian chờ phản hồi (Timeout)
        int timeoutMs = target.getTimeoutMs();
        if (timeoutMs <= 0) {
            errors.add("Thời gian chờ phản hồi (Timeout) phải > 0 ms!");
        } else if (interval >= 1) {
            long maxAllowedTimeoutMs = (long) (interval * 1000L * 0.8);
            if (timeoutMs > maxAllowedTimeoutMs) {
                errors.add(String.format("Thời gian chờ phản hồi (%d ms) vượt quá ngưỡng cho phép 80%% chu kỳ (%d ms cho chu kỳ %ds) theo ERRATA §E11.2!",
                        timeoutMs, maxAllowedTimeoutMs, interval));
            }
        }

        // 6. Ràng buộc Scope MULTI vs IP riêng / cục bộ (ERRATA §E11.1)
        TargetScope scope = target.getScope();
        if (scope == TargetScope.MULTI && host != null && !host.trim().isEmpty()) {
            if (NetworkValidationUtil.isPrivateOrLocalAddress(host)) {
                String typeDesc = NetworkValidationUtil.getPrivateAddressTypeDescription(host);
                errors.add(String.format("Địa chỉ '%s' là địa chỉ mạng riêng/cục bộ [%s]. Không được gán Scope MULTI (Đa trạm) theo ERRATA §E11.1 vì Quorum giữa các mạng khác nhau là vô nghĩa! Vui lòng chọn Scope AGENT hoặc SERVER.",
                        host.trim(), typeDesc));
            }
        }

        // 7. Kiểm tra chu trình phụ thuộc cha-con (Parent Cycle Detection)
        Integer parentId = target.getParentId();
        if (parentId != null && parentId > 0) {
            if (target.getId() > 0 && parentId.intValue() == target.getId()) {
                errors.add("Mục tiêu không thể tự làm mục tiêu cha của chính nó!");
            } else if (allTargets != null && !allTargets.isEmpty()) {
                Map<Integer, ServerTarget> targetMap = allTargets.stream()
                        .collect(Collectors.toMap(ServerTarget::getId, t -> t, (a, b) -> a));

                Set<Integer> visited = new HashSet<>();
                if (target.getId() > 0) {
                    visited.add(target.getId());
                }

                Integer currentParentId = parentId;
                while (currentParentId != null && currentParentId > 0) {
                    if (visited.contains(currentParentId)) {
                        errors.add("Phát hiện chu trình vòng lặp quan hệ phụ thuộc cha-con liên quan mục tiêu #" + currentParentId + "!");
                        break;
                    }
                    visited.add(currentParentId);
                    ServerTarget parent = targetMap.get(currentParentId);
                    if (parent == null) {
                        break; // Cha không nằm trong danh sách (có thể ở database khác), dừng duyệt
                    }
                    currentParentId = parent.getParentId();
                }
            }
        }

        return new ValidationResult(errors, warnings);
    }

    /**
     * Tự động sửa chữa phạm vi Scope của mục tiêu nếu vi phạm quy tắc IP riêng với MULTI (§E11.1)
     *
     * @param target Mục tiêu cần kiểm tra và sửa
     * @return true nếu đã sửa đổi, false nếu không vi phạm
     */
    public static boolean autoRepairScope(ServerTarget target) {
        if (target == null) return false;
        String host = target.getHost();
        if (target.getScope() == TargetScope.MULTI && host != null && NetworkValidationUtil.isPrivateOrLocalAddress(host)) {
            TargetScope repairedScope = isLoopbackOrLocalhost(host) ? TargetScope.SERVER : TargetScope.AGENT;
            String typeDesc = NetworkValidationUtil.getPrivateAddressTypeDescription(host);
            LoggerUtil.warn(String.format("⚠️ [AutoRepair] Mục tiêu #%d '%s' (%s) thuộc dải riêng [%s] nhưng mang Scope MULTI. Tự động chuyển về Scope %s theo ERRATA §E11.1.",
                    target.getId(), target.getName(), host.trim(), typeDesc, repairedScope.name()));
            target.setScope(repairedScope);
            return true;
        }
        return false;
    }

    private static boolean isLoopbackOrLocalhost(String host) {
        if (host == null) return false;
        String trimmed = host.trim().toLowerCase();
        return "127.0.0.1".equals(trimmed) || "localhost".equals(trimmed) || "::1".equals(trimmed) || trimmed.startsWith("127.");
    }

    private static boolean hasControlCharacters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}

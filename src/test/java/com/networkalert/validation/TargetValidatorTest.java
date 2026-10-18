package com.networkalert.validation;

import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetScope;
import com.networkalert.util.NetworkValidationUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Bộ kiểm thử xác thực Target & Scope MULTI vs RFC 1918 (ERRATA §E11 & Task T1.8)")
class TargetValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // RFC 1918 10.0.0.0/8
            "10.0.0.1", "10.254.1.99", "10.128.0.1",
            // RFC 1918 172.16.0.0/12
            "172.16.0.1", "172.20.10.5", "172.31.255.254",
            // RFC 1918 192.168.0.0/16
            "192.168.1.1", "192.168.100.1", "192.168.0.254",
            // CGNAT RFC 6598 100.64.0.0/10
            "100.64.0.1", "100.100.100.100", "100.127.255.254",
            // Loopback
            "127.0.0.1", "127.1.2.3", "localhost",
            // Link-Local RFC 3927
            "169.254.1.1", "169.254.254.254"
    })
    @DisplayName("Chặn tuyệt đối TargetScope.MULTI đối với địa chỉ riêng/cục bộ IPv4")
    void testRejectMultiScopeOnPrivateIPv4(String privateIp) {
        assertTrue(NetworkValidationUtil.isPrivateOrLocalAddress(privateIp),
                "Địa chỉ phải được nhận diện là riêng/cục bộ: " + privateIp);

        ServerTarget target = new ServerTarget(1, "Test Private", privateIp, 80, ProtocolType.HTTP, 10, 5000, true);
        target.setScope(TargetScope.MULTI);

        TargetValidator.ValidationResult result = TargetValidator.validate(target, Collections.singletonList(target));
        assertFalse(result.isValid(), "Target mang Scope MULTI với IP riêng phải bị từ chối!");
        assertTrue(result.getFirstError().contains("Scope MULTI"));

        // Nhưng nếu gán AGENT hoặc SERVER thì phải hợp lệ
        target.setScope(TargetScope.AGENT);
        assertTrue(TargetValidator.validate(target, Collections.singletonList(target)).isValid(),
                "Target IP riêng gán AGENT phải hợp lệ!");

        target.setScope(TargetScope.SERVER);
        assertTrue(TargetValidator.validate(target, Collections.singletonList(target)).isValid(),
                "Target IP riêng gán SERVER phải hợp lệ!");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // IPv6 Loopback
            "::1",
            // IPv6 Link-Local
            "fe80::1", "fe80::abcd:1234",
            // IPv6 ULA fc00::/7
            "fc00::1", "fd12:3456:789a::1"
    })
    @DisplayName("Chặn tuyệt đối TargetScope.MULTI đối với địa chỉ riêng/cục bộ IPv6")
    void testRejectMultiScopeOnPrivateIPv6(String privateIp6) {
        assertTrue(NetworkValidationUtil.isPrivateOrLocalAddress(privateIp6),
                "IPv6 phải được nhận diện là riêng/cục bộ: " + privateIp6);

        ServerTarget target = new ServerTarget(1, "Test Private IPv6", privateIp6, 80, ProtocolType.HTTP, 10, 5000, true);
        target.setScope(TargetScope.MULTI);

        TargetValidator.ValidationResult result = TargetValidator.validate(target, Collections.singletonList(target));
        assertFalse(result.isValid());
        assertTrue(result.getFirstError().contains("Scope MULTI"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Public IPv4 ngoài các dải riêng
            "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.167.1.1", "100.63.1.1", "100.128.1.1",
            // Public IPv6
            "2606:4700:4700::1111", "2001:4860:4860::8888",
            // Hostnames RFC 1123
            "google.com", "cloudflare.com", "vnexpress.net", "api.github.com", "gateway.company.vn"
    })
    @DisplayName("Cho phép TargetScope.MULTI đối với địa chỉ công cộng và Hostname toàn cục")
    void testAllowMultiScopeOnPublicOrGlobal(String publicHost) {
        assertFalse(NetworkValidationUtil.isPrivateOrLocalAddress(publicHost),
                "Địa chỉ phải là toàn cục: " + publicHost);

        ServerTarget target = new ServerTarget(1, "Test Public Target", publicHost, 443, ProtocolType.HTTPS, 10, 5000, true);
        target.setScope(TargetScope.MULTI);

        TargetValidator.ValidationResult result = TargetValidator.validate(target, Collections.singletonList(target));
        assertTrue(result.isValid(), "Địa chỉ toàn cục phải được phép gán Scope MULTI: " + result.getErrors());
    }

    @Test
    @DisplayName("Validate Hostname theo chuẩn RFC 1123")
    void testRfc1123HostnameValidation() {
        assertTrue(NetworkValidationUtil.isValidRfc1123Hostname("example.com"));
        assertTrue(NetworkValidationUtil.isValidRfc1123Hostname("sub.domain-test.vn"));
        assertTrue(NetworkValidationUtil.isValidRfc1123Hostname("a.b.c"));
        assertTrue(NetworkValidationUtil.isValidRfc1123Hostname("server1"));
        assertTrue(NetworkValidationUtil.isValidRfc1123Hostname("google.com.")); // FQDN có dot đuôi

        assertFalse(NetworkValidationUtil.isValidRfc1123Hostname(""));
        assertFalse(NetworkValidationUtil.isValidRfc1123Hostname("   "));
        assertFalse(NetworkValidationUtil.isValidRfc1123Hostname("domain_with_underscore.com"));
        assertFalse(NetworkValidationUtil.isValidRfc1123Hostname("-starts-with-dash.com"));
        assertFalse(NetworkValidationUtil.isValidRfc1123Hostname("ends-with-dash-.com"));
        assertFalse(NetworkValidationUtil.isValidRfc1123Hostname("space in name.com"));
        assertFalse(NetworkValidationUtil.isValidRfc1123Hostname("a..b.com"));
    }

    @Test
    @DisplayName("Validate tên mục tiêu: độ dài <= 128 và không có ký tự điều khiển")
    void testTargetNameValidation() {
        ServerTarget target = new ServerTarget(1, "Tên bình thường", "8.8.8.8", 0, ProtocolType.ICMP, 10, 2000, true);
        assertTrue(TargetValidator.validate(target, Collections.singletonList(target)).isValid());

        // Quá 128 ký tự
        String longName = "A".repeat(129);
        target.setName(longName);
        TargetValidator.ValidationResult r1 = TargetValidator.validate(target, Collections.singletonList(target));
        assertFalse(r1.isValid());
        assertTrue(r1.getFirstError().contains("128 ký tự"));

        // Chứa ký tự điều khiển (control chars)
        target.setName("Target\nName\r\0Test");
        TargetValidator.ValidationResult r2 = TargetValidator.validate(target, Collections.singletonList(target));
        assertFalse(r2.isValid());
        assertTrue(r2.getFirstError().contains("ký tự điều khiển"));
    }

    @Test
    @DisplayName("Validate Cổng mạng (Port): ICMP cho phép 0, các giao thức khác phải 1-65535")
    void testPortValidation() {
        // ICMP với port 0 hợp lệ
        ServerTarget icmp = new ServerTarget(1, "Ping", "8.8.8.8", 0, ProtocolType.ICMP, 10, 2000, true);
        assertTrue(TargetValidator.validate(icmp, Collections.singletonList(icmp)).isValid());

        // TCP với port 0 không hợp lệ
        ServerTarget tcp = new ServerTarget(2, "TCP", "8.8.8.8", 0, ProtocolType.TCP, 10, 2000, true);
        assertFalse(TargetValidator.validate(tcp, Collections.singletonList(tcp)).isValid());

        // Port âm hoặc vượt quá 65535
        tcp.setPort(-1);
        assertFalse(TargetValidator.validate(tcp, Collections.singletonList(tcp)).isValid());
        tcp.setPort(65536);
        assertFalse(TargetValidator.validate(tcp, Collections.singletonList(tcp)).isValid());

        // Port hợp lệ
        tcp.setPort(80);
        assertTrue(TargetValidator.validate(tcp, Collections.singletonList(tcp)).isValid());
    }

    @Test
    @DisplayName("Validate Chu kỳ (Interval >= 1s) và Hạn chờ (Timeout <= 0.8 * interval)")
    void testIntervalAndTimeoutBounds() {
        ServerTarget target = new ServerTarget(1, "Web", "google.com", 443, ProtocolType.HTTPS, 10, 5000, true);

        // Interval = 0 -> không hợp lệ
        target.setIntervalSeconds(0);
        TargetValidator.ValidationResult r1 = TargetValidator.validate(target, Collections.singletonList(target));
        assertFalse(r1.isValid());
        assertTrue(r1.getFirstError().contains("Interval"));

        // Interval = 10s: Ngưỡng 80% là 8000ms
        target.setIntervalSeconds(10);
        target.setTimeoutMs(8000);
        assertTrue(TargetValidator.validate(target, Collections.singletonList(target)).isValid(),
                "Timeout = 8000ms đúng bằng 80% của 10s phải hợp lệ");

        // Timeout = 8001ms (> 80%) -> Vi phạm §E11.2
        target.setTimeoutMs(8001);
        TargetValidator.ValidationResult r2 = TargetValidator.validate(target, Collections.singletonList(target));
        assertFalse(r2.isValid());
        assertTrue(r2.getFirstError().contains("vượt quá ngưỡng cho phép 80%"));

        // Interval dày < 5s cho HTTPS phát sinh cảnh báo
        target.setIntervalSeconds(3);
        target.setTimeoutMs(2000);
        TargetValidator.ValidationResult r3 = TargetValidator.validate(target, Collections.singletonList(target));
        assertTrue(r3.isValid());
        assertFalse(r3.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("Phát hiện chu trình vòng lặp quan hệ phụ thuộc cha-con (Parent Cycle DAG check)")
    void testParentCycleDetection() {
        ServerTarget t1 = new ServerTarget(1, "Target 1", "8.8.8.8", 0, ProtocolType.ICMP, 10, 2000, true);
        ServerTarget t2 = new ServerTarget(2, "Target 2", "1.1.1.1", 53, ProtocolType.DNS, 10, 2000, true);
        ServerTarget t3 = new ServerTarget(3, "Target 3", "google.com", 443, ProtocolType.HTTPS, 10, 2000, true);

        // Tự làm cha của chính mình
        t1.setParentId(1);
        TargetValidator.ValidationResult r1 = TargetValidator.validate(t1, Arrays.asList(t1, t2, t3));
        assertFalse(r1.isValid());
        assertTrue(r1.getFirstError().contains("tự làm mục tiêu cha"));

        // Quan hệ vòng 2 chiều: T1 -> T2 -> T1
        t1.setParentId(2);
        t2.setParentId(1);
        TargetValidator.ValidationResult r2 = TargetValidator.validate(t1, Arrays.asList(t1, t2, t3));
        assertFalse(r2.isValid());
        assertTrue(r2.getFirstError().contains("chu trình vòng lặp"));

        // Quan hệ vòng 3 chiều: T1 -> T2 -> T3 -> T1
        t1.setParentId(2);
        t2.setParentId(3);
        t3.setParentId(1);
        TargetValidator.ValidationResult r3 = TargetValidator.validate(t1, Arrays.asList(t1, t2, t3));
        assertFalse(r3.isValid());
        assertTrue(r3.getFirstError().contains("chu trình vòng lặp"));

        // Cây phân cấp hợp lệ không có vòng: T1 -> T2 -> T3 -> 0
        t3.setParentId(0);
        TargetValidator.ValidationResult r4 = TargetValidator.validate(t1, Arrays.asList(t1, t2, t3));
        assertTrue(r4.isValid(), "Cây phụ thuộc tuyến tính phải hợp lệ: " + r4.getErrors());
    }

    @Test
    @DisplayName("Tự động sửa chữa (Auto-repair) Scope MULTI cho IP riêng thành AGENT hoặc SERVER")
    void testAutoRepairScope() {
        // 1. Default Gateway 192.168.100.1 mang Scope MULTI -> tự chuyển sang AGENT
        ServerTarget gw = new ServerTarget(999, "Wi-Fi Router / Default Gateway", "192.168.100.1", 0, ProtocolType.ICMP, 3, 1000, true);
        gw.setScope(TargetScope.MULTI);

        boolean repaired1 = TargetValidator.autoRepairScope(gw);
        assertTrue(repaired1);
        assertEquals(TargetScope.AGENT, gw.getScope(), "IP riêng 192.168.x.x phải được chuyển về AGENT");

        // 2. Loopback 127.0.0.1 mang Scope MULTI -> tự chuyển sang SERVER
        ServerTarget loopback = new ServerTarget(10, "Mock Server", "127.0.0.1", 9000, ProtocolType.TCP, 3, 1000, true);
        loopback.setScope(TargetScope.MULTI);

        boolean repaired2 = TargetValidator.autoRepairScope(loopback);
        assertTrue(repaired2);
        assertEquals(TargetScope.SERVER, loopback.getScope(), "Loopback phải được chuyển về SERVER");

        // 3. Public IP 8.8.8.8 mang Scope MULTI -> giữ nguyên MULTI
        ServerTarget publicDns = new ServerTarget(2, "Google DNS", "8.8.8.8", 0, ProtocolType.ICMP, 10, 2000, true);
        publicDns.setScope(TargetScope.MULTI);

        boolean repaired3 = TargetValidator.autoRepairScope(publicDns);
        assertFalse(repaired3);
        assertEquals(TargetScope.MULTI, publicDns.getScope(), "Public IP giữ nguyên Scope MULTI");
    }
}

package com.networkalert.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Tiện ích thẩm định mạng, phân loại dải IP và kiểm tra tính hợp lệ của Host theo ERRATA §E11.
 * <p>
 * Hỗ trợ phân biệt:
 * <ul>
 *   <li><b>RFC 1918 IPv4:</b> 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16</li>
 *   <li><b>CGNAT (RFC 6598):</b> 100.64.0.0/10</li>
 *   <li><b>Loopback:</b> 127.0.0.0/8, ::1, localhost</li>
 *   <li><b>Link-Local:</b> 169.254.0.0/16, fe80::/10</li>
 *   <li><b>IPv6 ULA (RFC 4193):</b> fc00::/7 (fc00:: - fdff:ffff:...)</li>
 *   <li><b>Hostname RFC 1123:</b> Nhãn 1-63 ký tự, không bắt đầu/kết thúc bằng dấu gạch ngang, tổng chiều dài <= 253</li>
 * </ul>
 * </p>
 */
public final class NetworkValidationUtil {

    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern RFC1123_LABEL_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$");

    private NetworkValidationUtil() {}

    /**
     * Kiểm tra xem chuỗi có phải là địa chỉ IPv4 hợp lệ hay không
     */
    public static boolean isValidIPv4(String ip) {
        if (ip == null || ip.trim().isEmpty()) return false;
        var matcher = IPV4_PATTERN.matcher(ip.trim());
        if (!matcher.matches()) return false;
        try {
            for (int i = 1; i <= 4; i++) {
                int octet = Integer.parseInt(matcher.group(i));
                if (octet < 0 || octet > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Kiểm tra xem chuỗi có phải là địa chỉ IPv6 hợp lệ hay không
     */
    public static boolean isValidIPv6(String ip) {
        if (ip == null || ip.trim().isEmpty()) return false;
        String trimmed = ip.trim();
        // Bỏ ngoặc vuông nếu có dạng [::1]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (!trimmed.contains(":")) return false;
        try {
            InetAddress addr = InetAddress.getByName(trimmed);
            return addr instanceof Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Kiểm tra xem hostname có tuân thủ chuẩn RFC 1123 hay không
     */
    public static boolean isValidRfc1123Hostname(String hostname) {
        if (hostname == null || hostname.trim().isEmpty()) return false;
        String trimmed = hostname.trim();
        if (trimmed.length() > 253) return false;
        // Bỏ dấu chấm đuôi nếu là FQDN tuyệt đối (e.g. google.com.)
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) return false;

        String[] labels = trimmed.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) return false;
            if (!RFC1123_LABEL_PATTERN.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Kiểm tra host hợp lệ: Có thể là IPv4, IPv6 hoặc Hostname RFC 1123
     */
    public static boolean isValidHost(String host) {
        if (host == null || host.trim().isEmpty()) return false;
        String trimmed = host.trim();
        return isValidIPv4(trimmed) || isValidIPv6(trimmed) || isValidRfc1123Hostname(trimmed);
    }

    /**
     * Xác định xem Host/IP có phải là địa chỉ riêng, cục bộ, hoặc không thể định tuyến toàn cục theo ERRATA §E11.1
     * <p>
     * Bao gồm: RFC 1918, CGNAT 100.64/10, Loopback, Link-Local, IPv6 ULA, localhost, và tên miền cục bộ (.local, .internal, .lan).
     * </p>
     */
    public static boolean isPrivateOrLocalAddress(String host) {
        if (host == null || host.trim().isEmpty()) return false;
        String trimmed = host.trim();

        // 1. Kiểm tra hostname cục bộ thông dụng
        String lower = trimmed.toLowerCase();
        if ("localhost".equals(lower) || lower.endsWith(".localhost") ||
                lower.endsWith(".local") || lower.endsWith(".internal") || lower.endsWith(".lan")) {
            return true;
        }

        // 2. Kiểm tra nếu là IPv4
        if (isValidIPv4(trimmed)) {
            return isPrivateOrLocalIPv4(trimmed);
        }

        // 3. Kiểm tra nếu là IPv6
        if (isValidIPv6(trimmed)) {
            return isPrivateOrLocalIPv6(trimmed);
        }

        // Là hostname thông thường (không phải đuôi cục bộ) -> coi là định danh toàn cục
        return false;
    }

    /**
     * Phân loại dải IPv4 riêng/cục bộ
     */
    private static boolean isPrivateOrLocalIPv4(String ip) {
        String[] parts = ip.split("\\.");
        int o1 = Integer.parseInt(parts[0]);
        int o2 = Integer.parseInt(parts[1]);

        // RFC 1918: 10.0.0.0/8
        if (o1 == 10) return true;

        // RFC 1918: 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        if (o1 == 172 && (o2 >= 16 && o2 <= 31)) return true;

        // RFC 1918: 192.168.0.0/16
        if (o1 == 192 && o2 == 168) return true;

        // CGNAT (RFC 6598): 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
        if (o1 == 100 && (o2 >= 64 && o2 <= 127)) return true;

        // Loopback: 127.0.0.0/8
        if (o1 == 127) return true;

        // Link-Local (RFC 3927): 169.254.0.0/16
        if (o1 == 169 && o2 == 254) return true;

        // Any / 0.0.0.0/8
        if (o1 == 0) return true;

        // Broadcast: 255.255.255.255
        if ("255.255.255.255".equals(ip)) return true;

        return false;
    }

    /**
     * Phân loại dải IPv6 riêng/cục bộ
     */
    private static boolean isPrivateOrLocalIPv6(String ip) {
        try {
            String clean = ip;
            if (clean.startsWith("[") && clean.endsWith("]")) {
                clean = clean.substring(1, clean.length() - 1);
            }
            InetAddress addr = InetAddress.getByName(clean);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                return true;
            }

            byte[] b = addr.getAddress();
            if (b.length == 16) {
                // ULA (Unique Local Address, RFC 4193): fc00::/7 -> bit đầu 1111110 -> 0xfc hoặc 0xfd
                int firstByte = b[0] & 0xff;
                if ((firstByte & 0xfe) == 0xfc) {
                    return true;
                }

                // IPv4-mapped IPv6 (::ffff:x.x.x.x)
                boolean isV4Mapped = true;
                for (int i = 0; i < 10; i++) {
                    if (b[i] != 0) { isV4Mapped = false; break; }
                }
                if (isV4Mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                    int v4o1 = b[12] & 0xff;
                    int v4o2 = b[13] & 0xff;
                    String v4Str = v4o1 + "." + v4o2 + "." + (b[14] & 0xff) + "." + (b[15] & 0xff);
                    return isPrivateOrLocalIPv4(v4Str);
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lấy tên mô tả dải địa chỉ riêng nếu là IP riêng/cục bộ
     */
    public static String getPrivateAddressTypeDescription(String host) {
        if (!isPrivateOrLocalAddress(host)) return "GLOBAL";
        String trimmed = host.trim();
        String lower = trimmed.toLowerCase();
        if ("localhost".equals(lower) || lower.endsWith(".localhost")) return "Loopback (localhost)";
        if (isValidIPv4(trimmed)) {
            String[] parts = trimmed.split("\\.");
            int o1 = Integer.parseInt(parts[0]);
            int o2 = Integer.parseInt(parts[1]);
            if (o1 == 10) return "RFC 1918 (10.0.0.0/8)";
            if (o1 == 172 && (o2 >= 16 && o2 <= 31)) return "RFC 1918 (172.16.0.0/12)";
            if (o1 == 192 && o2 == 168) return "RFC 1918 (192.168.0.0/16)";
            if (o1 == 100 && (o2 >= 64 && o2 <= 127)) return "CGNAT RFC 6598 (100.64.0.0/10)";
            if (o1 == 127) return "Loopback (127.0.0.0/8)";
            if (o1 == 169 && o2 == 254) return "Link-Local (169.254.0.0/16)";
            return "Private/Local IPv4";
        }
        if (isValidIPv6(trimmed)) {
            try {
                String clean = trimmed;
                if (clean.startsWith("[") && clean.endsWith("]")) {
                    clean = clean.substring(1, clean.length() - 1);
                }
                InetAddress addr = InetAddress.getByName(clean);
                if (addr.isLoopbackAddress()) return "Loopback (::1)";
                if (addr.isLinkLocalAddress()) return "Link-Local (fe80::/10)";
                byte[] b = addr.getAddress();
                if (b.length == 16 && ((b[0] & 0xfe) == 0xfc)) return "IPv6 ULA (fc00::/7)";
            } catch (Exception ignored) {}
            return "Private/Local IPv6";
        }
        return "Local Hostname";
    }
}

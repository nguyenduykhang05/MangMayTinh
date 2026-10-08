package com.networkalert.service.checker;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerTarget;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Kiểm tra kết nối tầng Ứng dụng qua giao thức DNS (Domain Name System Query).
 * Hỗ trợ:
 * 1. Nếu target là IP của DNS Server (ví dụ: Cloudflare DNS 1.1.1.1, Google DNS 8.8.8.8):
 *    Tự đóng gói bản tin DNS Standard Query (Type A) chuẩn RFC 1035 gửi qua DatagramSocket
 *    (UDP cổng 53) để đo độ trễ thực tế và kiểm tra phản hồi từ máy chủ DNS.
 *    Khi ngắt mạng / tắt Wi-Fi, socket sẽ timeout hoặc văng lỗi mạng, đánh dấu OFFLINE chính xác.
 * 2. Nếu target là một tên miền (ví dụ: google.com, cloudflare.com):
 *    Thực hiện phân giải tên miền qua hệ thống DNS Resolver.
 */
public class DnsChecker implements IProtocolChecker {

    private static final String DEFAULT_PROBE_DOMAIN = "example.com";
    private String configuredProbeDomain = DEFAULT_PROBE_DOMAIN;
    private final Random random = new Random();

    public String getProbeDomain() {
        return configuredProbeDomain;
    }

    public void setProbeDomain(String domain) {
        if (domain != null && !domain.trim().isEmpty()) {
            this.configuredProbeDomain = domain.trim();
        }
    }

    @Override
    public CheckResult check(ServerTarget target) {
        String rawHost = target.getHost() != null ? target.getHost().trim() : "";
        if (rawHost.isEmpty()) {
            return CheckResult.failure("Địa chỉ host DNS không hợp lệ (trống)");
        }

        // Hỗ trợ chỉ định rõ chế độ OS Resolver qua tiền tố os:// hoặc dns-os://
        boolean forceOsMode = rawHost.startsWith("os://") || rawHost.startsWith("dns-os://");
        String host = forceOsMode ? rawHost.replaceFirst("^dns-os://|^os://", "").trim() : rawHost;

        int port = target.getPort() > 0 ? target.getPort() : 53;
        int timeoutMs = target.getTimeoutMs() > 0 ? target.getTimeoutMs() : 2000;

        // Xử lý trường hợp chuỗi host có kèm port (ví dụ: "1.1.1.1:53")
        if (host.contains(":") && host.indexOf(':') == host.lastIndexOf(':') && !host.startsWith("[")) {
            String[] parts = host.split(":");
            host = parts[0].trim();
            try {
                port = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {}
        }

        if (!forceOsMode && isIpAddress(host)) {
            // Chế độ 1: Target là IP DNS Server -> Gửi gói tin DNS UDP RFC 1035 trực tiếp tới server:53
            return queryDnsServer(host, port, timeoutMs, configuredProbeDomain);
        } else {
            // Chế độ 2: Target là tên miền hoặc ép OS -> Phân giải qua DNS Resolver hệ điều hành
            return resolveDomain(host);
        }
    }

    /**
     * Gửi gói tin UDP DNS Query (Type A) chuẩn tới DNS Server cụ thể (ví dụ 1.1.1.1:53)
     */
    private CheckResult queryDnsServer(String dnsServerIp, int port, int timeoutMs, String queryDomain) {
        short txId = (short) (random.nextInt(0x7FFF) + 1);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);

            InetAddress serverAddress = InetAddress.getByName(dnsServerIp);
            byte[] queryPacketData = buildDnsQuery(queryDomain, txId);
            DatagramPacket sendPacket = new DatagramPacket(queryPacketData, queryPacketData.length, serverAddress, port);

            long startTime = System.nanoTime();
            socket.send(sendPacket);

            byte[] buffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(receivePacket);
            long endTime = System.nanoTime();

            long latencyMs = (endTime - startTime) / 1_000_000;
            if (latencyMs == 0) latencyMs = 1;

            byte[] data = receivePacket.getData();
            int length = receivePacket.getLength();

            if (length < 12) {
                return CheckResult.failure("Gói tin DNS phản hồi không hợp lệ (kích thước quá ngắn: " + length + " bytes)");
            }

            // Kiểm tra Transaction ID phải khớp với gói đã gửi
            short respId = (short) (((data[0] & 0xFF) << 8) | (data[1] & 0xFF));
            if (respId != txId) {
                return CheckResult.failure(String.format("DNS Transaction ID không khớp (gửi: 0x%04X, nhận: 0x%04X)", txId, respId));
            }

            // Cờ flags & RCODE
            int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            boolean isResponse = (flags & 0x8000) != 0;
            int rcode = flags & 0x0F;

            if (!isResponse) {
                return CheckResult.failure("Gói tin nhận được từ " + dnsServerIp + " không phải phản hồi DNS (QR bit = 0)");
            }

            if (rcode != 0) {
                String rcodeDesc = getRcodeDescription(rcode);
                return CheckResult.failure(String.format("Máy chủ DNS %s trả về lỗi: %s (RCODE=%d)", dnsServerIp, rcodeDesc, rcode));
            }

            // Trích xuất danh sách IP trả về từ Answer Section
            List<String> resolvedIps = parseDnsAnswers(data, length);
            List<com.networkalert.model.PerfData> perf = Collections.singletonList(
                    com.networkalert.model.PerfData.of("rta", latencyMs, "ms", null, (double) timeoutMs, 0.0, null)
            );
            if (!resolvedIps.isEmpty()) {
                String ipList = resolvedIps.stream().limit(3).collect(Collectors.joining(", "));
                return CheckResult.ok(latencyMs, String.format("[UDP Direct :%d] Query '%s' OK (%d IPs: %s)",
                        port, queryDomain, resolvedIps.size(), ipList), "dns", perf);
            } else {
                return CheckResult.ok(latencyMs, String.format("[UDP Direct :%d] Query '%s' OK (NoError, 0 answers)",
                        port, queryDomain), "dns", perf);
            }
        } catch (SocketTimeoutException e) {
            return CheckResult.crit(timeoutMs, 2, "DNS Query Timeout sau " + timeoutMs + "ms (Máy chủ " + dnsServerIp + " không phản hồi)", "dns");
        } catch (PortUnreachableException e) {
            return CheckResult.crit(0, 2, "Cổng DNS " + port + " không thể truy cập (ICMP Port Unreachable)", "dns");
        } catch (SocketException e) {
            return CheckResult.crit(0, 2, "Lỗi Socket DNS: " + e.getMessage(), "dns");
        } catch (UnknownHostException e) {
            return CheckResult.unknown(0, 3, "Không xác định được máy chủ DNS: " + dnsServerIp, "dns");
        } catch (IOException e) {
            return CheckResult.crit(0, 2, "Lỗi mạng DNS: " + e.getMessage(), "dns");
        } catch (Exception e) {
            return CheckResult.crit(0, 2, "Lỗi DNS: " + e.getMessage(), "dns");
        }
    }

    /**
     * Phân giải một tên miền bằng DNS Resolver của hệ thống
     */
    private CheckResult resolveDomain(String host) {
        if (!hasActiveNetworkInterface()) {
            return CheckResult.crit("Lỗi DNS: Mạng đã bị ngắt kết nối (Network is down)", "dns");
        }

        long startTime = System.nanoTime();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            long endTime = System.nanoTime();
            long latencyMs = (endTime - startTime) / 1_000_000;
            if (latencyMs == 0) latencyMs = 1;

            if (addresses != null && addresses.length > 0) {
                String ipList = Arrays.stream(addresses)
                        .map(InetAddress::getHostAddress)
                        .limit(3)
                        .collect(Collectors.joining(", "));
                List<com.networkalert.model.PerfData> perf = Collections.singletonList(
                        com.networkalert.model.PerfData.of("rta", latencyMs, "ms")
                );
                return CheckResult.ok(latencyMs, String.format("[OS Resolver] '%s' OK (%d IPs: %s)", host, addresses.length, ipList), "dns", perf);
            } else {
                return CheckResult.crit(latencyMs, 2, "[OS Resolver] Không trả về địa chỉ IP nào cho " + host, "dns");
            }
        } catch (UnknownHostException e) {
            return CheckResult.unknown(0, 3, "DNS Query thất bại: Không tìm thấy máy chủ DNS hoặc tên miền không tồn tại (" + host + ")", "dns");
        } catch (Exception e) {
            return CheckResult.crit(0, 2, "Lỗi DNS: " + e.getMessage(), "dns");
        }
    }

    /**
     * Đóng gói bản tin DNS Standard Query (Type A, Class IN) theo chuẩn RFC 1035
     */
    private byte[] buildDnsQuery(String domain, short transactionId) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // 1. Transaction ID (16-bit)
        dos.writeShort(transactionId);
        // 2. Flags: Standard Query, Recursion Desired (0x0100)
        dos.writeShort(0x0100);
        // 3. QDCOUNT: 1 question
        dos.writeShort(1);
        // 4. ANCOUNT: 0
        dos.writeShort(0);
        // 5. NSCOUNT: 0
        dos.writeShort(0);
        // 6. ARCOUNT: 0
        dos.writeShort(0);

        // 7. QNAME: Danh sách nhãn tên miền (ví dụ: google.com -> [6]google[3]com[0])
        String[] labels = domain.split("\\.");
        for (String label : labels) {
            byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
            dos.writeByte(labelBytes.length);
            dos.write(labelBytes);
        }
        dos.writeByte(0); // Kết thúc chuỗi nhãn

        // 8. QTYPE: Type A (IPv4) = 1
        dos.writeShort(1);
        // 9. QCLASS: Class IN (Internet) = 1
        dos.writeShort(1);

        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Bóc tách danh sách IP IPv4 từ phần Answer của gói tin DNS Response
     */
    private List<String> parseDnsAnswers(byte[] data, int length) {
        List<String> ips = new ArrayList<>();
        if (length < 12) return ips;

        int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (ancount == 0) return ips;

        // Bỏ qua phần Question: bắt đầu từ byte 12
        int offset = 12;
        while (offset < length) {
            if ((data[offset] & 0xC0) == 0xC0) {
                offset += 2; // Pointer
                break;
            }
            if (data[offset] == 0) {
                offset += 1;
                break;
            }
            int l = data[offset] & 0xFF;
            offset += 1 + l;
        }
        offset += 4; // Bỏ qua QTYPE (2) + QCLASS (2)

        // Phân tích các bản ghi trong Answer section
        for (int i = 0; i < ancount && offset < length; i++) {
            // NAME (có thể là con trỏ nén 0xC0 hoặc nhãn)
            while (offset < length) {
                if ((data[offset] & 0xC0) == 0xC0) {
                    offset += 2;
                    break;
                }
                if (data[offset] == 0) {
                    offset += 1;
                    break;
                }
                int l = data[offset] & 0xFF;
                offset += 1 + l;
            }

            if (offset + 10 > length) break;
            int type = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            // Bỏ qua TYPE (2), CLASS (2), TTL (4) -> 8 bytes
            offset += 8;
            int rdLength = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            offset += 2;

            if (type == 1 && rdLength == 4 && offset + 4 <= length) {
                // Type A (IPv4)
                String ip = (data[offset] & 0xFF) + "." +
                            (data[offset + 1] & 0xFF) + "." +
                            (data[offset + 2] & 0xFF) + "." +
                            (data[offset + 3] & 0xFF);
                ips.add(ip);
            }
            offset += rdLength;
        }

        return ips;
    }

    /**
     * Nhận diện chuỗi có phải là địa chỉ IP (IPv4 hoặc IPv6) hay không
     */
    private boolean isIpAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        // IPv4 format: d.d.d.d
        if (host.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
            return true;
        }
        // IPv6 format (chứa dấu hai chấm)
        if (host.contains(":") && !host.contains("/")) {
            return true;
        }
        return false;
    }

    /**
     * Kiểm tra máy tính có card mạng thật nào đang hoạt động hay không
     */
    private boolean hasActiveNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) {
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && (addr instanceof Inet4Address)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String getRcodeDescription(int rcode) {
        switch (rcode) {
            case 0: return "NoError (Không có lỗi)";
            case 1: return "Format Error (Lỗi định dạng gói tin)";
            case 2: return "Server Failure (Lỗi máy chủ DNS nội bộ)";
            case 3: return "NXDOMAIN (Tên miền không tồn tại)";
            case 4: return "Not Implemented (Dịch vụ DNS chưa hỗ trợ tính năng này)";
            case 5: return "Refused (Máy chủ từ chối trả lời truy vấn)";
            default: return "Lỗi DNS mã " + rcode;
        }
    }
}

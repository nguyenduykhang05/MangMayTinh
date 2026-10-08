package com.networkalert.service.checker;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ProtocolType;
import com.networkalert.model.ServerTarget;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.*;

/**
 * Kiểm tra kết nối tầng Ứng dụng qua giao thức HTTP / HTTPS
 * Phân rã thời gian đo lường mạng chi tiết:
 * - DNS Lookup (thời gian phân giải tên miền)
 * - TCP Connect (bắt tay 3 bước SYN-SYN/ACK-ACK)
 * - TLS Handshake (bắt tay bảo mật HTTPS)
 * - TTFB (Time To First Byte - thời gian chờ byte đầu tiên từ máy chủ)
 * - Tổng thời gian phản hồi (Round-Trip Total)
 */
public class HttpChecker implements IProtocolChecker {

    @Override
    public CheckResult check(ServerTarget target) {
        String protocolPrefix = (target.getProtocol() == ProtocolType.HTTPS || target.getPort() == 443) ? "https://" : "http://";
        String host = target.getHost().trim();
        if (host.startsWith("http://") || host.startsWith("https://")) {
            protocolPrefix = "";
        }

        String urlString;
        if (protocolPrefix.isEmpty()) {
            urlString = host;
        } else {
            boolean isDefaultPort = (protocolPrefix.equals("http://") && target.getPort() == 80) ||
                                    (protocolPrefix.equals("https://") && target.getPort() == 443) ||
                                    target.getPort() <= 0;
            if (isDefaultPort) {
                urlString = protocolPrefix + host;
            } else {
                urlString = protocolPrefix + host + ":" + target.getPort();
            }
        }

        long tTotalStart = System.nanoTime();
        long dnsMs = 0;
        long tcpMs = 0;
        long tlsMs = -1;
        long ttfbMs = 0;
        Socket socket = null;

        try {
            URL url = new URL(urlString);
            String domain = url.getHost();
            boolean isHttps = "https".equalsIgnoreCase(url.getProtocol()) ||
                              target.getProtocol() == ProtocolType.HTTPS ||
                              target.getPort() == 443;
            int port = url.getPort();
            if (port <= 0) {
                port = isHttps ? 443 : 80;
            }
            int timeout = Math.max(100, target.getTimeoutMs());

            // 1. Phân giải DNS Lookup
            long t0 = System.nanoTime();
            InetAddress ip = InetAddress.getByName(domain);
            dnsMs = Math.max(0, (System.nanoTime() - t0) / 1_000_000);

            // 2. Thiết lập kết nối TCP Socket (3-way handshake)
            long t1 = System.nanoTime();
            socket = new Socket();
            socket.setSoTimeout(timeout);
            socket.connect(new InetSocketAddress(ip, port), timeout);
            tcpMs = Math.max(0, (System.nanoTime() - t1) / 1_000_000);

            // 3. Bắt tay TLS/SSL Handshake (nếu là HTTPS)
            if (isHttps) {
                long t2 = System.nanoTime();
                SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(socket, domain, port, true);
                sslSocket.setSoTimeout(timeout);
                sslSocket.startHandshake();
                tlsMs = Math.max(0, (System.nanoTime() - t2) / 1_000_000);

                // Đọc hạn sử dụng chứng chỉ TLS/SSL (Certificate Expiry)
                try {
                    java.security.cert.Certificate[] peerCerts = sslSocket.getSession().getPeerCertificates();
                    if (peerCerts != null && peerCerts.length > 0 && peerCerts[0] instanceof java.security.cert.X509Certificate) {
                        java.security.cert.X509Certificate x509 = (java.security.cert.X509Certificate) peerCerts[0];
                        java.util.Date notAfter = x509.getNotAfter();
                        if (notAfter != null) {
                            long diffMs = notAfter.getTime() - System.currentTimeMillis();
                            int daysLeft = (int) (diffMs / (1000L * 60 * 60 * 24));
                            target.setTlsExpiryDays(daysLeft);
                            target.setTlsExpiryDate(notAfter.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate());
                        }
                    }
                } catch (Exception ignored) {}

                socket = sslSocket;
            }

            // 4. Gửi HTTP Request và đo thời gian chờ phản hồi đầu tiên (TTFB)
            String path = (url.getPath() == null || url.getPath().isEmpty()) ? "/" : url.getPath();
            if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                path += "?" + url.getQuery();
            }

            OutputStream os = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n" +
                             "Host: " + domain + "\r\n" +
                             "User-Agent: NetworkConnectionAlertSystem/1.0\r\n" +
                             "Accept: */*\r\n" +
                             "Connection: close\r\n\r\n";

            long t3 = System.nanoTime();
            os.write(request.getBytes(StandardCharsets.US_ASCII));
            os.flush();

            InputStream is = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            ttfbMs = Math.max(0, (System.nanoTime() - t3) / 1_000_000);

            long totalMs = Math.max(1, (System.nanoTime() - tTotalStart) / 1_000_000);

            int statusCode = 0;
            String statusDesc = "";
            if (statusLine != null && statusLine.startsWith("HTTP/")) {
                String[] parts = statusLine.split(" ", 3);
                if (parts.length >= 2) {
                    try {
                        statusCode = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 3) {
                    statusDesc = parts[2];
                }
            } else {
                return CheckResult.failure("Phản hồi HTTP không hợp lệ từ máy chủ");
            }

            // Đọc bỏ qua headers
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                // headers
            }

            // 5. Kiểm tra từ khóa trong Body (Keyword Matching) nếu có cấu hình
            if (target.getExpectedKeyword() != null && !target.getExpectedKeyword().trim().isEmpty()) {
                String expected = target.getExpectedKeyword().trim();
                StringBuilder body = new StringBuilder();
                char[] buf = new char[4096];
                int read;
                while (body.length() < 65536 && (read = reader.read(buf)) != -1) {
                    body.append(buf, 0, read);
                    if (body.indexOf(expected) != -1) {
                        break;
                    }
                }
                if (body.indexOf(expected) == -1) {
                    return CheckResult.failure(String.format("HTTP %d OK nhưng body không chứa từ khóa mong đợi: '%s'", statusCode, expected));
                }
            }

            // Cảnh báo hạn chứng chỉ TLS nếu dưới 14 ngày
            String tlsWarning = "";
            if (target.getTlsExpiryDays() >= 0 && target.getTlsExpiryDays() < 14) {
                tlsWarning = String.format(" ⚠️ [Chứng chỉ TLS hết hạn sau %d ngày!]", target.getTlsExpiryDays());
            }

            String timingBreakdown = String.format("[DNS: %dms | TCP: %dms%s | TTFB: %dms | Tổng: %dms]",
                    dnsMs, tcpMs, (isHttps ? (" | TLS: " + tlsMs + "ms") : ""), ttfbMs, totalMs);

            List<com.networkalert.model.PerfData> perfList = new java.util.ArrayList<>();
            perfList.add(com.networkalert.model.PerfData.of("time", totalMs, "ms", (double) target.getWarningThresholdMs(), (double) target.getTimeoutMs(), 0.0, null));
            perfList.add(com.networkalert.model.PerfData.of("ttfb", ttfbMs, "ms"));
            perfList.add(com.networkalert.model.PerfData.of("dns", dnsMs, "ms"));
            perfList.add(com.networkalert.model.PerfData.of("tcp", tcpMs, "ms"));
            if (isHttps && tlsMs >= 0) {
                perfList.add(com.networkalert.model.PerfData.of("tls", tlsMs, "ms"));
            }

            String msg = "HTTP " + statusCode + " " + statusDesc + " " + timingBreakdown + tlsWarning;
            String method = isHttps ? "https" : "http";

            if (statusCode >= 200 && statusCode < 400) {
                if (target.getWarningThresholdMs() > 0 && totalMs > target.getWarningThresholdMs()) {
                    return CheckResult.warn(totalMs, msg, method, perfList);
                }
                return CheckResult.ok(totalMs, msg, method, perfList);
            } else {
                return CheckResult.crit(totalMs, statusCode, msg, method, perfList);
            }

        } catch (UnknownHostException uhe) {
            return CheckResult.unknown(0, 3, "Không phân giải được tên miền: " + target.getHost(), (target.getProtocol() == ProtocolType.HTTPS || target.getPort() == 443) ? "https" : "http");
        } catch (SocketTimeoutException ste) {
            return CheckResult.crit(target.getTimeoutMs(), 2, "HTTP Timeout sau " + target.getTimeoutMs() + "ms (DNS: " + dnsMs + "ms, TCP: " + tcpMs + "ms)", (target.getProtocol() == ProtocolType.HTTPS || target.getPort() == 443) ? "https" : "http");
        } catch (SSLHandshakeException sslEx) {
            return CheckResult.crit(0, 2, "Lỗi SSL/TLS Handshake: " + sslEx.getMessage(), "https");
        } catch (IOException ioe) {
            return CheckResult.crit(0, 2, "Lỗi kết nối HTTP: " + ioe.getMessage(), (target.getProtocol() == ProtocolType.HTTPS || target.getPort() == 443) ? "https" : "http");
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }
}

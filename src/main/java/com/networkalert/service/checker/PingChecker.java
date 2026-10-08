package com.networkalert.service.checker;

import com.networkalert.model.CheckResult;
import com.networkalert.model.PerfData;
import com.networkalert.model.ServerTarget;
import com.networkalert.util.NetworkValidationUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm tra kết nối tầng Mạng qua ICMP Ping hệ thống, tự động phân định phương thức
 * và hỗ trợ fallback sang TCP Connect theo chuẩn Nagios (ERRATA §E9.6 & §E9.1).
 * <p>
 * 1. Validate host chặt chẽ theo RFC 1123 / IP để ngăn ngừa Command Injection.
 * 2. Thực thi lệnh {@code ping} gốc của HĐH (Windows / Linux / POSIX) qua ProcessBuilder dạng mảng tham số.
 * 3. Trích xuất thống kê RTT (min, avg, max), tỉ lệ mất gói (packet loss %) và jitter.
 * 4. Nếu ICMP không khả dụng (bị chặn quyền / firewall), tự động fallback sang TCP connect
 *    và gán {@code method = "tcp"} kèm chỉ báo giao diện {@code ICMP*}.
 * </p>
 */
public class PingChecker implements IProtocolChecker {

    @FunctionalInterface
    public interface PingRunner {
        PingOutput execute(String host, int count, int timeoutMs) throws IOException;
    }

    public static class PingOutput {
        private final int sent;
        private final int received;
        private final double packetLossPercent;
        private final double minRtt;
        private final double avgRtt;
        private final double maxRtt;
        private final double jitter;
        private final String rawOutput;

        public PingOutput(int sent, int received, double packetLossPercent,
                          double minRtt, double avgRtt, double maxRtt, double jitter, String rawOutput) {
            this.sent = sent;
            this.received = received;
            this.packetLossPercent = packetLossPercent;
            this.minRtt = minRtt;
            this.avgRtt = avgRtt;
            this.maxRtt = maxRtt;
            this.jitter = jitter;
            this.rawOutput = rawOutput;
        }

        public int getSent() { return sent; }
        public int getReceived() { return received; }
        public double getPacketLossPercent() { return packetLossPercent; }
        public double getMinRtt() { return minRtt; }
        public double getAvgRtt() { return avgRtt; }
        public double getMaxRtt() { return maxRtt; }
        public double getJitter() { return jitter; }
        public String getRawOutput() { return rawOutput; }
    }

    private final PingRunner pingRunner;

    public PingChecker() {
        this(new DefaultSystemPingRunner());
    }

    public PingChecker(PingRunner pingRunner) {
        this.pingRunner = pingRunner != null ? pingRunner : new DefaultSystemPingRunner();
    }

    @Override
    public CheckResult check(ServerTarget target) {
        String host = target.getHost();

        // 1. Kiểm tra an toàn đầu vào (chống command injection)
        if (!NetworkValidationUtil.isValidHost(host)) {
            return CheckResult.unknown(0, 3, "Host không hợp lệ hoặc chứa ký tự bị cấm: " + host, "icmp");
        }

        int timeoutMs = target.getTimeoutMs() > 0 ? target.getTimeoutMs() : 1500;
        int count = (timeoutMs <= 1000) ? 2 : 4;
        long warningThreshold = target.getWarningThresholdMs() > 0 ? target.getWarningThresholdMs() : 300;
        long checkStartTime = System.nanoTime();

        // 2. Thử thực thi System ICMP Ping
        try {
            PingOutput output = pingRunner.execute(host, count, timeoutMs);
            if (output != null && output.getReceived() > 0 && output.getPacketLossPercent() < 100.0) {
                long avgRtt = Math.max(1, Math.round(output.getAvgRtt()));
                double pl = output.getPacketLossPercent();

                List<PerfData> perf = Arrays.asList(
                        PerfData.of("rta", avgRtt, "ms", (double) warningThreshold, (double) timeoutMs, output.getMinRtt(), output.getMaxRtt()),
                        PerfData.of("pl", Math.round(pl), "%", 20.0, 50.0, 0.0, 100.0)
                );

                if (pl > 0.0) {
                    // Mất gói một phần -> Cảnh báo WARN
                    String msg = String.format("ICMP Ping phản hồi (%d%% mất gói, RTT avg=%.1fms, jitter=%.1fms)",
                            (int) Math.round(pl), output.getAvgRtt(), output.getJitter());
                    return CheckResult.warn(avgRtt, msg, "icmp", perf);
                }

                if (avgRtt > warningThreshold) {
                    String msg = String.format("ICMP Echo Reply độ trễ cao (%.1fms > %dms)", output.getAvgRtt(), warningThreshold);
                    return CheckResult.warn(avgRtt, msg, "icmp", perf);
                }

                String msg = String.format("ICMP Echo Reply thành công từ %s (RTT avg=%.1fms)", host, output.getAvgRtt());
                return CheckResult.ok(avgRtt, msg, "icmp", perf);
            }
        } catch (Exception ignored) {
            // Lỗi chạy lệnh ping hoặc quyền raw socket bị từ chối -> Chuyển sang fallback
        }

        // 3. Fallback sang TCP Connect nếu ICMP thất bại / không có đặc quyền
        long elapsedMs = (System.nanoTime() - checkStartTime) / 1_000_000;
        int remainingTcpTimeout = Math.max(300, (int) (timeoutMs - elapsedMs));
        int fallbackPort = target.getPort() > 0 ? target.getPort() : 80;
        long fbStart = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, fallbackPort), remainingTcpTimeout);
            long fbEnd = System.nanoTime();
            long fbLatency = Math.max(1, (fbEnd - fbStart) / 1_000_000);

            List<PerfData> perf = Arrays.asList(
                    PerfData.of("rta", fbLatency, "ms", (double) warningThreshold, (double) timeoutMs, 0.0, null),
                    PerfData.of("pl", 0.0, "%", 20.0, 50.0, 0.0, 100.0)
            );

            if (fbLatency > warningThreshold) {
                String msg = String.format("Host phản hồi [TCP Fallback qua cổng %d] độ trễ cao (%dms)", fallbackPort, fbLatency);
                return CheckResult.warn(fbLatency, msg, "tcp", perf);
            }

            String msg = String.format("Host phản hồi [TCP Fallback qua cổng %d] (%dms)", fallbackPort, fbLatency);
            return CheckResult.ok(fbLatency, msg, "tcp", perf);
        } catch (Exception e) {
            // Cả ICMP lẫn TCP Fallback đều thất bại
            List<PerfData> perf = Arrays.asList(
                    PerfData.of("rta", timeoutMs, "ms", (double) warningThreshold, (double) timeoutMs, 0.0, null),
                    PerfData.of("pl", 100.0, "%", 20.0, 50.0, 0.0, 100.0)
            );
            return CheckResult.crit(timeoutMs, 2, "ICMP Ping thất bại (100% packet loss) & TCP fallback cổng " + fallbackPort + " không phản hồi", "icmp", perf);
        }
    }

    /**
     * Bộ phân tích stdout đầu ra lệnh ping của hệ thống (Windows và Linux)
     */
    public static PingOutput parsePingOutput(String output, int sentCount) {
        if (output == null || output.trim().isEmpty()) {
            return new PingOutput(sentCount, 0, 100.0, 0, 0, 0, 0, output != null ? output : "");
        }

        // 1. Phân tích số gói tin và packet loss
        int sent = sentCount;
        int received = 0;
        double packetLoss = 100.0;

        // Windows: Packets: Sent = 4, Received = 4, Lost = 0 (0% loss)
        // Tiếng Việt Windows: Gói: Đã gửi = 4, Đã nhận = 4, Bị mất = 0 (0% mất)
        Pattern winPktPattern = Pattern.compile("(?:Sent|Đã gửi)\\s*=\\s*(\\d+).*?(?:Received|Đã nhận)\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher winPktMatcher = winPktPattern.matcher(output);
        if (winPktMatcher.find()) {
            sent = Integer.parseInt(winPktMatcher.group(1));
            received = Integer.parseInt(winPktMatcher.group(2));
            if (sent > 0) {
                packetLoss = ((sent - received) * 100.0) / sent;
            }
        } else {
            // Linux: 4 packets transmitted, 4 received, 0% packet loss
            Pattern linPktPattern = Pattern.compile("(\\d+)\\s+packets\\s+transmitted,\\s+(\\d+)\\s+(?:packets\\s+)?received", Pattern.CASE_INSENSITIVE);
            Matcher linPktMatcher = linPktPattern.matcher(output);
            if (linPktMatcher.find()) {
                sent = Integer.parseInt(linPktMatcher.group(1));
                received = Integer.parseInt(linPktMatcher.group(2));
                if (sent > 0) {
                    packetLoss = ((sent - received) * 100.0) / sent;
                }
            } else {
                // Fallback: Tìm số phần trăm loss trực tiếp
                Pattern lossPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:packet\\s*)?(?:loss|mất)", Pattern.CASE_INSENSITIVE);
                Matcher lossMatcher = lossPattern.matcher(output);
                if (lossMatcher.find()) {
                    packetLoss = Double.parseDouble(lossMatcher.group(1));
                    if (sent > 0) {
                        received = (int) Math.round(sent * (100.0 - packetLoss) / 100.0);
                    }
                }
            }
        }

        // 2. Thu thập danh sách các mẫu RTT từng gói trả lời
        List<Double> rttSamples = new ArrayList<>();
        // Reply from ...: time=2ms hoặc time<1ms hoặc time=0.045 ms
        Pattern replyPattern = Pattern.compile("time[=<]([0-9.]+)\\s*ms", Pattern.CASE_INSENSITIVE);
        Matcher replyMatcher = replyPattern.matcher(output);
        while (replyMatcher.find()) {
            try {
                rttSamples.add(Double.parseDouble(replyMatcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        if (received == 0 && !rttSamples.isEmpty()) {
            received = rttSamples.size();
            packetLoss = ((sent - received) * 100.0) / sent;
        }

        // 3. Phân tích RTT tổng hợp (Min/Avg/Max/Jitter)
        double minRtt = 0;
        double avgRtt = 0;
        double maxRtt = 0;
        double jitter = 0;

        // Thử regex Linux trước: rtt min/avg/max/mdev = 0.038/0.041/0.045/0.005 ms
        Pattern linRttPattern = Pattern.compile("rtt\\s+min/avg/max/(?:mdev|stddev)\\s*=\\s*([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)", Pattern.CASE_INSENSITIVE);
        Matcher linRttMatcher = linRttPattern.matcher(output);
        if (linRttMatcher.find()) {
            minRtt = Double.parseDouble(linRttMatcher.group(1));
            avgRtt = Double.parseDouble(linRttMatcher.group(2));
            maxRtt = Double.parseDouble(linRttMatcher.group(3));
            jitter = Double.parseDouble(linRttMatcher.group(4));
        } else {
            // Thử regex Windows: Minimum = 1ms, Maximum = 4ms, Average = 2ms
            Pattern winRttPattern = Pattern.compile("(?:Minimum|Tối thiểu)\\s*=\\s*([0-9]+)ms.*?(?:Maximum|Tối đa)\\s*=\\s*([0-9]+)ms.*?(?:Average|Trung bình)\\s*=\\s*([0-9]+)ms", Pattern.CASE_INSENSITIVE);
            Matcher winRttMatcher = winRttPattern.matcher(output);
            if (winRttMatcher.find()) {
                minRtt = Double.parseDouble(winRttMatcher.group(1));
                maxRtt = Double.parseDouble(winRttMatcher.group(2));
                avgRtt = Double.parseDouble(winRttMatcher.group(3));
                jitter = Math.max(0, maxRtt - minRtt);
            } else if (!rttSamples.isEmpty()) {
                // Tính toán trực tiếp từ các mẫu RTT đo được
                minRtt = rttSamples.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                maxRtt = rttSamples.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                avgRtt = rttSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                jitter = Math.max(0, maxRtt - minRtt);
            }
        }

        return new PingOutput(sent, received, packetLoss, minRtt, avgRtt, maxRtt, jitter, output);
    }

    /**
     * Bộ thực thi lệnh ping hệ thống mặc định
     */
    public static class DefaultSystemPingRunner implements PingRunner {
        @Override
        public PingOutput execute(String host, int count, int timeoutMs) throws IOException {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            List<String> cmd = new ArrayList<>();
            cmd.add("ping");

            int effectiveCount = Math.max(1, count);
            int perPacketTimeoutMs = Math.max(100, (timeoutMs - 200) / effectiveCount);

            if (isWindows) {
                cmd.add("-n");
                cmd.add(String.valueOf(effectiveCount));
                cmd.add("-w");
                cmd.add(String.valueOf(perPacketTimeoutMs));
                cmd.add(host);
            } else {
                cmd.add("-c");
                cmd.add(String.valueOf(effectiveCount));
                cmd.add("-W");
                int timeoutSec = Math.max(1, (int) Math.ceil(perPacketTimeoutMs / 1000.0));
                cmd.add(String.valueOf(timeoutSec));
                cmd.add(host);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }

            try {
                // Chờ tiến trình tối đa timeoutMs + 300 ms
                long waitDeadlineMs = (long) timeoutMs + 300L;
                boolean finished = process.waitFor(waitDeadlineMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Tiến trình ping bị gián đoạn", e);
            }

            return parsePingOutput(sb.toString(), effectiveCount);
        }
    }
}

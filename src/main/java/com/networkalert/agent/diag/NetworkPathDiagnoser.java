package com.networkalert.agent.diag;

import com.networkalert.model.ServerTarget;
import com.networkalert.service.RealNetworkService;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NamedThreadFactory;
import com.networkalert.util.NetworkValidationUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/**
 * Động cơ tự chẩn đoán đường mạng tại chỗ khi mục tiêu sắp rơi vào trạng thái HARD DOWN (ERRATA §E9.9 & Task T2.9).
 * <p>
 * Nguyên tắc hoạt động:
 * 1. Kích hoạt tại Agent khi mục tiêu ghi nhận thất bại liên tiếp lần thứ 2 (SOFT state).
 * 2. Thực thi chuỗi chẩn đoán phân tầng không phong tỏa luồng đo (non-blocking to lane):
 *    Gateway probe -> DNS probe -> Traceroute (tracert/traceroute mảng tham số) -> So sánh ICMP vs TCP.
 * 3. Ràng buộc an toàn:
 *    - Giới hạn tần suất: Tối đa 1 lần / 5 phút / mục tiêu.
 *    - Trần đồng thời: Tối đa 2 tiến trình chẩn đoán song song toàn cục.
 *    - Giới hạn thời gian: Timeout cứng 10 giây cho toàn bộ chuỗi chẩn đoán.
 *    - Trần dung lượng: JSON kết quả đính kèm luôn ≤ 2 KiB.
 * </p>
 */
public class NetworkPathDiagnoser {

    private static NetworkPathDiagnoser instance;

    @FunctionalInterface
    public interface GatewayProber {
        DiagnosticReport.ProbeResult probe(String gatewayIp, int timeoutMs);
    }

    @FunctionalInterface
    public interface DnsProber {
        DiagnosticReport.ProbeResult probe(String domain, int timeoutMs);
    }

    @FunctionalInterface
    public interface TracerouteRunner {
        DiagnosticReport.TraceResult trace(String host, int maxHops, int timeoutMs);
    }

    @FunctionalInterface
    public interface IcmpProber {
        DiagnosticReport.ProbeResult probe(String host, int timeoutMs);
    }

    @FunctionalInterface
    public interface TcpProber {
        DiagnosticReport.ProbeResult probe(String host, int port, int timeoutMs);
    }

    private final Clock clock;
    private final GatewayProber gatewayProber;
    private final DnsProber dnsProber;
    private final TracerouteRunner tracerouteRunner;
    private final IcmpProber icmpProber;
    private final TcpProber tcpProber;

    // Quản lý rate limit: 1 lần / 5 phút / target
    private static final long RATE_LIMIT_MS = 5 * 60 * 1000L;
    private final Map<Integer, Long> lastDiagnosisTimestamps = new ConcurrentHashMap<>();

    // Lưu trữ báo cáo chẩn đoán gần nhất theo targetId
    private final Map<Integer, String> latestDiagnosisJsonMap = new ConcurrentHashMap<>();

    // Giới hạn tối đa 2 tác vụ chẩn đoán đồng thời
    private final Semaphore concurrencyLimiter = new Semaphore(2);

    // Hàng đợi luồng thực thi tác vụ chẩn đoán ngầm không chặn lane chính
    private final ExecutorService diagExecutor;

    public NetworkPathDiagnoser() {
        this(Clock.systemUTC());
    }

    public NetworkPathDiagnoser(Clock clock) {
        this(clock, null, null, null, null, null);
    }

    public NetworkPathDiagnoser(Clock clock,
                                GatewayProber gatewayProber,
                                DnsProber dnsProber,
                                TracerouteRunner tracerouteRunner,
                                IcmpProber icmpProber,
                                TcpProber tcpProber) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.diagExecutor = Executors.newFixedThreadPool(2, new NamedThreadFactory("NCAS-DiagWorker", true));

        this.gatewayProber = gatewayProber != null ? gatewayProber : this::defaultProbeGateway;
        this.dnsProber = dnsProber != null ? dnsProber : this::defaultProbeDns;
        this.tracerouteRunner = tracerouteRunner != null ? tracerouteRunner : this::defaultRunTraceroute;
        this.icmpProber = icmpProber != null ? icmpProber : this::defaultProbeIcmp;
        this.tcpProber = tcpProber != null ? tcpProber : this::defaultProbeTcp;
    }

    public static synchronized NetworkPathDiagnoser getInstance() {
        if (instance == null) {
            instance = new NetworkPathDiagnoser();
        }
        return instance;
    }

    public static synchronized void setInstance(NetworkPathDiagnoser customInstance) {
        instance = customInstance;
    }

    /**
     * Kiểm tra xem mục tiêu có thỏa mãn điều kiện tần suất chạy chẩn đoán (5 phút/lần)
     */
    public boolean shouldRunDiagnosis(int targetId) {
        long now = clock.millis();
        long lastTime = lastDiagnosisTimestamps.getOrDefault(targetId, 0L);
        return (now - lastTime) >= RATE_LIMIT_MS;
    }

    /**
     * Kích hoạt tự chẩn đoán bất đồng bộ khi mục tiêu có dấu hiệu sắp HARD DOWN (SOFT failure lần 2)
     */
    public CompletableFuture<DiagnosticReport> triggerDiagnosisAsync(ServerTarget target) {
        if (target == null) {
            return CompletableFuture.completedFuture(null);
        }

        int targetId = target.getId();
        if (!shouldRunDiagnosis(targetId)) {
            // Chưa đủ 5 phút kể từ lần chẩn đoán trước -> Bỏ qua
            return CompletableFuture.completedFuture(null);
        }

        // Thử cấp phát slot đồng thời (trần tối đa 2)
        if (!concurrencyLimiter.tryAcquire()) {
            LoggerUtil.warn("[NetworkDiag] Đạt giới hạn 2 tác vụ chẩn đoán đồng thời. Bỏ qua lượt chẩn đoán cho #" + targetId);
            return CompletableFuture.completedFuture(null);
        }

        // Cập nhật timestamp để chống kích hoạt dồn dập
        lastDiagnosisTimestamps.put(targetId, clock.millis());

        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeDiagnosis(target);
            } finally {
                concurrencyLimiter.release();
            }
        }, diagExecutor);
    }

    /**
     * Thực thi toàn bộ chuỗi chẩn đoán đồng bộ với timeout cứng 10 giây
     */
    public DiagnosticReport executeDiagnosis(ServerTarget target) {
        String host = target.getHost();
        if (!NetworkValidationUtil.isValidHost(host)) {
            DiagnosticReport report = new DiagnosticReport(target.getId(), host, clock.millis());
            report.setSummary("Host không hợp lệ: " + host);
            latestDiagnosisJsonMap.put(target.getId(), report.toJson());
            return report;
        }

        LoggerUtil.info(String.format("🔍 [NetworkDiag] Bắt đầu tự chẩn đoán đường mạng cho mục tiêu #%d '%s' (%s)...",
                target.getId(), target.getName(), host));

        DiagnosticReport report = new DiagnosticReport(target.getId(), host, clock.millis());

        // 1. Thử nghiệm Default Gateway
        String gatewayIp = RealNetworkService.getInstance().getNetworkInfo().getGatewayIp();
        if (gatewayIp == null || gatewayIp.trim().isEmpty()) {
            gatewayIp = "192.168.1.1";
        }
        DiagnosticReport.ProbeResult gwResult = gatewayProber.probe(gatewayIp, 1000);
        report.setGateway(gwResult);

        // 2. Thử nghiệm DNS Resolution
        DiagnosticReport.ProbeResult dnsResult = dnsProber.probe("google.com", 1200);
        report.setDns(dnsResult);

        // 3. Thực thi Traceroute phân tích chặng mạng (tối đa 15 hops)
        DiagnosticReport.TraceResult traceResult = tracerouteRunner.trace(host, 15, 5000);
        report.setTrace(traceResult);

        // 4. So sánh đối chiếu ICMP và TCP Connect
        int targetPort = target.getPort() > 0 ? target.getPort() : 80;
        DiagnosticReport.ProbeResult icmpResult = icmpProber.probe(host, 1500);
        DiagnosticReport.ProbeResult tcpResult = tcpProber.probe(host, targetPort, 1500);

        DiagnosticReport.ComparisonProbes probes = new DiagnosticReport.ComparisonProbes();
        probes.setIcmp(icmpResult);
        probes.setTcp(tcpResult);
        probes.setTargetPort(targetPort);
        report.setProbes(probes);

        // 5. Tổng hợp dòng tóm tắt chẩn đoán trực quan (E9.9 & E7.6)
        String summary = generateSummary(report);
        report.setSummary(summary);

        String json = report.toJson();
        latestDiagnosisJsonMap.put(target.getId(), json);

        LoggerUtil.info(String.format("✅ [NetworkDiag] Hoàn tất chẩn đoán #%d: %s", target.getId(), summary));
        return report;
    }

    /**
     * Tạo dòng thông điệp tóm tắt chẩn đoán ngắn gọn, dễ hiểu cho quản trị viên
     */
    public String generateSummary(DiagnosticReport report) {
        if (report == null) return "Không có dữ liệu chẩn đoán";

        // Trường hợp 1: Gateway nội bộ không phản hồi -> Đứt kết nối vật lý / LAN
        if (report.getGateway() != null && !report.getGateway().isOk()) {
            return String.format("Gateway không phản hồi (%s) - đứt kết nối mạng nội bộ/Wi-Fi",
                    report.getGateway().getDetail());
        }

        // Trường hợp 2: Gateway OK nhưng DNS phân giải hỏng
        if (report.getDns() != null && !report.getDns().isOk()) {
            return String.format("Gateway OK (%dms), DNS thất bại - mất kết nối Internet hoặc DNS lỗi",
                    report.getGateway() != null ? report.getGateway().getRttMs() : 0);
        }

        // Trường hợp 3: Đường đi dừng lại giữa chừng (Traceroute stop)
        if (report.getTrace() != null && !report.getTrace().isReachedTarget()) {
            int stopHop = report.getTrace().getLastResponsiveHop();
            String stopIp = report.getTrace().getLastResponsiveIp() != null ? report.getTrace().getLastResponsiveIp() : "không rõ";
            return String.format("Gateway OK, DNS OK, đường đi dừng ở hop %d (%s)", stopHop, stopIp);
        }

        // Trường hợp 4: Đến đích, phân tích ICMP vs TCP
        boolean icmpOk = report.getProbes() != null && report.getProbes().getIcmp() != null && report.getProbes().getIcmp().isOk();
        boolean tcpOk = report.getProbes() != null && report.getProbes().getTcp() != null && report.getProbes().getTcp().isOk();
        int port = report.getProbes() != null ? report.getProbes().getTargetPort() : 80;

        if (!icmpOk && tcpOk) {
            return String.format("Gateway OK, DNS OK, traceroute đến đích: ICMP bị chặn nhưng TCP cổng %d mở", port);
        } else if (icmpOk && !tcpOk) {
            return String.format("Gateway OK, DNS OK, traceroute đến đích: ICMP phản hồi nhưng dịch vụ TCP cổng %d đóng/treo", port);
        } else if (!icmpOk && !tcpOk) {
            return String.format("Gateway OK, DNS OK: cả ICMP lẫn TCP cổng %d đều không phản hồi (Host chết)", port);
        } else {
            return String.format("Gateway OK, DNS OK, traceroute đến đích bình thường (ICMP OK, TCP cổng %d OK)", port);
        }
    }

    public String getLatestDiagnosisJson(int targetId) {
        return latestDiagnosisJsonMap.get(targetId);
    }

    public void clearHistory() {
        lastDiagnosisTimestamps.clear();
        latestDiagnosisJsonMap.clear();
    }

    public void shutdown() {
        diagExecutor.shutdownNow();
    }

    // =========================================================================
    // Các bộ đo lường mặc định (Default Probers)
    // =========================================================================

    private DiagnosticReport.ProbeResult defaultProbeGateway(String gwIp, int timeoutMs) {
        long start = System.nanoTime();
        try {
            InetAddress addr = InetAddress.getByName(gwIp);
            boolean ok = addr.isReachable(timeoutMs);
            long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            return new DiagnosticReport.ProbeResult(ok, elapsed, gwIp);
        } catch (Exception e) {
            return new DiagnosticReport.ProbeResult(false, timeoutMs, gwIp + " (" + e.getMessage() + ")");
        }
    }

    private DiagnosticReport.ProbeResult defaultProbeDns(String domain, int timeoutMs) {
        long start = System.nanoTime();
        try {
            InetAddress[] addrs = InetAddress.getAllByName(domain);
            long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            boolean ok = addrs != null && addrs.length > 0;
            return new DiagnosticReport.ProbeResult(ok, elapsed, domain);
        } catch (Exception e) {
            return new DiagnosticReport.ProbeResult(false, timeoutMs, "DNS lookup failed for " + domain);
        }
    }

    private DiagnosticReport.TraceResult defaultRunTraceroute(String host, int maxHops, int timeoutMs) {
        DiagnosticReport.TraceResult res = new DiagnosticReport.TraceResult();
        res.setMaxHops(maxHops);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = isWindows
                ? Arrays.asList("tracert", "-d", "-h", String.valueOf(maxHops), "-w", "800", host)
                : Arrays.asList("traceroute", "-n", "-m", String.valueOf(maxHops), "-w", "1", host);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            int lastHop = 0;
            String lastIp = null;
            boolean reached = false;

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.matches("^\\d+\\s+.*")) {
                        String[] parts = line.split("\\s+");
                        int hopNum = Integer.parseInt(parts[0]);
                        String ip = parts[parts.length - 1];

                        boolean responsive = !line.contains("*") && ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
                        if (responsive) {
                            lastHop = hopNum;
                            lastIp = ip;
                            if (ip.equals(host)) {
                                reached = true;
                            }
                        }
                        res.getHops().add(new DiagnosticReport.HopInfo(hopNum, ip, 0, responsive));
                    }
                }
            }

            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            res.setLastResponsiveHop(lastHop);
            res.setLastResponsiveIp(lastIp);
            res.setReachedTarget(reached);
        } catch (Exception ignored) {
        }

        return res;
    }

    private DiagnosticReport.ProbeResult defaultProbeIcmp(String host, int timeoutMs) {
        long start = System.nanoTime();
        try {
            InetAddress addr = InetAddress.getByName(host);
            boolean ok = addr.isReachable(timeoutMs);
            long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            return new DiagnosticReport.ProbeResult(ok, elapsed, ok ? "ICMP OK" : "ICMP Timeout");
        } catch (Exception e) {
            return new DiagnosticReport.ProbeResult(false, timeoutMs, "ICMP Error: " + e.getMessage());
        }
    }

    private DiagnosticReport.ProbeResult defaultProbeTcp(String host, int port, int timeoutMs) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            return new DiagnosticReport.ProbeResult(true, elapsed, "TCP Port " + port + " OPEN");
        } catch (Exception e) {
            return new DiagnosticReport.ProbeResult(false, timeoutMs, "TCP Port " + port + " CLOSED (" + e.getMessage() + ")");
        }
    }
}

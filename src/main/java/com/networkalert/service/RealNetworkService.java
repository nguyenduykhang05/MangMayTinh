package com.networkalert.service;

import com.networkalert.model.LanDevice;
import com.networkalert.model.RealNetworkInfo;
import com.networkalert.util.LoggerUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dịch vụ khai thác và giám sát Mạng Thật (Real Network Service)
 * Khám phá Card mạng thật, Gateway Router, IP Public, DNS, và Quét thiết bị LAN
 */
public class RealNetworkService {
    private static RealNetworkService instance;
    private final RealNetworkInfo cachedInfo = new RealNetworkInfo();
    private long lastPublicIpCheck = 0;
    private final ScheduledExecutorService backgroundScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RealNetworkService-Poller");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean isLanScanning = new AtomicBoolean(false);

    private RealNetworkService() {
        refreshNetworkInfo();
        // Cập nhật ngầm định kỳ mỗi 15 giây
        backgroundScheduler.scheduleWithFixedDelay(this::refreshNetworkInfo, 15, 15, TimeUnit.SECONDS);
    }

    public static synchronized RealNetworkService getInstance() {
        if (instance == null) {
            instance = new RealNetworkService();
        }
        return instance;
    }

    public synchronized RealNetworkInfo getNetworkInfo() {
        return cachedInfo;
    }

    /**
     * Thu thập toàn bộ thông tin môi trường mạng thật từ hệ điều hành và adapter
     */
    public synchronized void refreshNetworkInfo() {
        try {
            // 1. Tìm Card mạng tích cực (Active Network Interface)
            NetworkInterface activeNic = null;
            Inet4Address localIpv4 = null;
            int prefixLength = 24;

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;

                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    if (addr.getAddress() instanceof Inet4Address && !addr.getAddress().isLoopbackAddress()) {
                        Inet4Address ipv4 = (Inet4Address) addr.getAddress();
                        // Ưu tiên card mạng có IP dải riêng thông dụng (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
                        if (ipv4.isSiteLocalAddress()) {
                            activeNic = ni;
                            localIpv4 = ipv4;
                            prefixLength = addr.getNetworkPrefixLength();
                            break;
                        } else if (activeNic == null) {
                            activeNic = ni;
                            localIpv4 = ipv4;
                            prefixLength = addr.getNetworkPrefixLength();
                        }
                    }
                }
                if (activeNic != null && localIpv4 != null && localIpv4.isSiteLocalAddress()) {
                    break;
                }
            }

            if (activeNic != null && localIpv4 != null) {
                cachedInfo.setInterfaceName(activeNic.getDisplayName());
                String lowerName = activeNic.getDisplayName().toLowerCase();
                if (lowerName.contains("wi-fi") || lowerName.contains("wireless") || lowerName.contains("802.11")) {
                    cachedInfo.setInterfaceType("Wi-Fi (Không dây)");
                } else if (lowerName.contains("ethernet") || lowerName.contains("realtek") || lowerName.contains("intel(r) ethernet")) {
                    cachedInfo.setInterfaceType("Ethernet (Có dây)");
                } else {
                    cachedInfo.setInterfaceType("Network Adapter");
                }

                cachedInfo.setLocalIp(localIpv4.getHostAddress());
                cachedInfo.setNetworkPrefix(prefixLength);
                cachedInfo.setSubnetMask(calcSubnetMask(prefixLength));

                byte[] mac = activeNic.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
                    }
                    cachedInfo.setMacAddress(sb.toString());
                }
            }

            // 2. Tìm Default Gateway (Router)
            String gateway = detectDefaultGateway();
            if (gateway != null && !gateway.isEmpty()) {
                cachedInfo.setGatewayIp(gateway);
            } else if (localIpv4 != null) {
                String ipStr = localIpv4.getHostAddress();
                int lastDot = ipStr.lastIndexOf('.');
                if (lastDot > 0) {
                    cachedInfo.setGatewayIp(ipStr.substring(0, lastDot) + ".1");
                }
            }

            // Đo độ trễ tới Gateway
            long gwLatency = measurePingLatency(cachedInfo.getGatewayIp());
            cachedInfo.setGatewayLatencyMs(gwLatency);
            if (gwLatency >= 0 && gwLatency < 100) {
                cachedInfo.setGatewayStatus("ONLINE");
            } else if (gwLatency >= 100) {
                cachedInfo.setGatewayStatus("WARNING");
            } else {
                cachedInfo.setGatewayStatus("OFFLINE");
            }

            // 3. DNS Servers & Latency
            List<String> dnsList = detectDnsServers();
            if (!dnsList.isEmpty()) {
                cachedInfo.setDnsServers(dnsList);
            } else if (cachedInfo.getDnsServers().isEmpty()) {
                cachedInfo.setDnsServers(Arrays.asList("8.8.8.8", "1.1.1.1"));
            }

            // Đo thời gian phân giải tên miền (DNS Query Time)
            long dnsStart = System.nanoTime();
            try {
                InetAddress.getAllByName("google.com");
                long dnsLatency = (System.nanoTime() - dnsStart) / 1_000_000;
                cachedInfo.setDnsLatencyMs(dnsLatency > 0 ? dnsLatency : 1);
            } catch (Exception e) {
                cachedInfo.setDnsLatencyMs(-1);
            }

            // 4. IP Public (cache 60s để tránh nghẽn / rate limit)
            if (System.currentTimeMillis() - lastPublicIpCheck > 60_000 || cachedInfo.getPublicIp().contains("Đang")) {
                fetchPublicIpAsync();
            }

            // 5. Tổng hợp trạng thái Internet
            if (cachedInfo.getGatewayStatus().equals("ONLINE") && cachedInfo.getDnsLatencyMs() > 0) {
                cachedInfo.setInternetStatus("CONNECTED");
            } else if (cachedInfo.getGatewayStatus().equals("ONLINE")) {
                cachedInfo.setInternetStatus("DEGRADED");
            } else {
                cachedInfo.setInternetStatus("DISCONNECTED");
            }

            cachedInfo.setLastUpdated(System.currentTimeMillis());
        } catch (Exception e) {
            LoggerUtil.warn("Lỗi khi cập nhật RealNetworkInfo: " + e.getMessage());
        }
    }

    private String calcSubnetMask(int prefix) {
        int mask = 0xffffffff << (32 - prefix);
        int value = mask;
        byte[] bytes = new byte[]{
                (byte) (value >>> 24),
                (byte) (value >> 16 & 0xff),
                (byte) (value >> 8 & 0xff),
                (byte) (value & 0xff)
        };
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception e) {
            return "255.255.255.0";
        }
    }

    /**
     * Nhận diện Default Gateway qua route print 0.0.0.0 trên Windows hoặc /proc/net/route
     */
    private String detectDefaultGateway() {
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (isWindows) {
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "route print 0.0.0.0");
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("0.0.0.0")) {
                            String[] parts = line.split("\\s+");
                            if (parts.length >= 3 && parts[2].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                                return parts[2];
                            }
                        }
                    }
                }
                p.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Nhận diện DNS Servers cấu hình trên hệ điều hành
     */
    private List<String> detectDnsServers() {
        Set<String> servers = new LinkedHashSet<>();
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (isWindows) {
                Process p = new ProcessBuilder("cmd.exe", "/c", "netsh interface ip show dns").start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        for (String part : line.split("\\s+")) {
                            if (part.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && !part.equals("0.0.0.0")) {
                                servers.add(part);
                            }
                        }
                    }
                }
                p.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {}

        if (servers.isEmpty()) {
            servers.add("8.8.8.8");
            servers.add("1.1.1.1");
        }
        return new ArrayList<>(servers);
    }

    /**
     * Lấy IP Public thực tế từ dịch vụ bên ngoài bất đồng bộ
     */
    private void fetchPublicIpAsync() {
        new Thread(() -> {
            String[] endpoints = {"https://api.ipify.org", "https://icanhazip.com", "https://checkip.amazonaws.com"};
            for (String ep : endpoints) {
                try {
                    URL url = new URL(ep);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.setRequestProperty("User-Agent", "NCAS-NetworkMonitor/1.0");
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String ip = in.readLine();
                        if (ip != null && ip.trim().matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                            cachedInfo.setPublicIp(ip.trim());
                            lastPublicIpCheck = System.currentTimeMillis();
                            return;
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (Exception ignored) {}
            }
        }, "PublicIpFetcher").start();
    }

    /**
     * Đo độ trễ ping tới 1 Host bất kỳ (hỗ trợ ICMP Ping & TCP Socket Probe)
     */
    public long measurePingLatency(String host) {
        if (host == null || host.isEmpty()) return -1;
        long start = System.nanoTime();
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isReachable(600)) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                return ms > 0 ? ms : 1;
            }

            // Fallback probe cổng 80 hoặc 53 hoặc 443
            int[] ports = {80, 53, 443, 8080};
            for (int port : ports) {
                try (Socket s = new Socket()) {
                    long pStart = System.nanoTime();
                    s.connect(new InetSocketAddress(addr, port), 400);
                    long ms = (System.nanoTime() - pStart) / 1_000_000;
                    return ms > 0 ? ms : 1;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Đọc bảng ARP trên Windows để tìm danh sách IP và MAC đã biết
     */
    public Map<String, String> getArpTable() {
        Map<String, String> arpMap = new HashMap<>();
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "arp -a").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && parts[0].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                        String ip = parts[0];
                        String mac = parts[1].replace('-', ':').toUpperCase();
                        if (!mac.equalsIgnoreCase("ff:ff:ff:ff:ff:ff") && !ip.startsWith("224.") && !ip.startsWith("239.")) {
                            arpMap.put(ip, mac);
                        }
                    }
                }
            }
            p.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return arpMap;
    }

    /**
     * Quét toàn bộ thiết bị trong dải mạng LAN thật (/24)
     */
    public List<LanDevice> scanLanSubnet(int timeoutMs) {
        List<LanDevice> foundDevices = new CopyOnWriteArrayList<>();
        String localIp = cachedInfo.getLocalIp();
        String gateway = cachedInfo.getGatewayIp();
        int lastDot = localIp.lastIndexOf('.');
        if (lastDot <= 0) return foundDevices;

        String subnetPrefix = localIp.substring(0, lastDot + 1);
        Map<String, String> arpMap = getArpTable();

        // Luôn đưa Gateway và Thiết bị hiện tại vào danh sách đầu tiên
        LanDevice self = new LanDevice(localIp, cachedInfo.getMacAddress(), "Máy tính này (NCAS Host)",
                0, "Máy tính giám sát", false, true);
        foundDevices.add(self);

        long gwLatency = cachedInfo.getGatewayLatencyMs();
        String gwMac = arpMap.getOrDefault(gateway, "N/A");
        LanDevice gw = new LanDevice(gateway, gwMac, "Wi-Fi Router / Default Gateway",
                gwLatency >= 0 ? gwLatency : 1, "Bộ định tuyến (Router)", true, false);
        foundDevices.add(gw);

        // Quét đa luồng 254 địa chỉ IP trong dải
        ExecutorService scannerPool = Executors.newFixedThreadPool(32);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 1; i <= 254; i++) {
            final String targetIp = subnetPrefix + i;
            if (targetIp.equals(localIp) || targetIp.equals(gateway)) continue;

            futures.add(scannerPool.submit(() -> {
                try {
                    long latency = -1;
                    InetAddress addr = InetAddress.getByName(targetIp);
                    long start = System.nanoTime();

                    if (addr.isReachable(timeoutMs)) {
                        latency = (System.nanoTime() - start) / 1_000_000;
                    } else {
                        // Thử mở các cổng dịch vụ thông dụng (80, 443, 8080, 53, 22, 135, 445)
                        int[] testPorts = {80, 443, 8080, 53, 22, 135, 445};
                        for (int p : testPorts) {
                            try (Socket s = new Socket()) {
                                long pStart = System.nanoTime();
                                s.connect(new InetSocketAddress(addr, p), timeoutMs);
                                latency = (System.nanoTime() - pStart) / 1_000_000;
                                break;
                            } catch (Exception ignored) {}
                        }
                    }

                    // Nếu IP có trong bảng ARP hoặc phản hồi probe
                    String mac = arpMap.get(targetIp);
                    if (latency >= 0 || mac != null) {
                        String hostname = "Thiết bị LAN (" + targetIp + ")";
                        String devType = guessDeviceType(hostname, mac);
                        LanDevice dev = new LanDevice(targetIp, mac != null ? mac : "N/A",
                                hostname, latency >= 0 ? (latency > 0 ? latency : 1) : 2,
                                devType, false, false);
                        foundDevices.add(dev);
                    }
                } catch (Exception ignored) {}
            }));
        }

        scannerPool.shutdown();
        try {
            scannerPool.awaitTermination(Math.max(1200, timeoutMs * 2), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        scannerPool.shutdownNow();

        // Sắp xếp: Thiết bị hiện tại và Gateway lên đầu, sau đó theo IP
        foundDevices.sort((a, b) -> {
            if (a.isCurrentDevice()) return -1;
            if (b.isCurrentDevice()) return 1;
            if (a.isGateway()) return -1;
            if (b.isGateway()) return 1;
            return a.getIp().compareTo(b.getIp());
        });

        return foundDevices;
    }

    private String guessDeviceType(String hostname, String mac) {
        String h = hostname.toLowerCase();
        if (h.contains("router") || h.contains("gateway") || h.contains("modem")) return "Bộ định tuyến / Modem";
        if (h.contains("phone") || h.contains("iphone") || h.contains("android") || h.contains("galaxy") || h.contains("xiaomi")) return "Điện thoại thông minh";
        if (h.contains("ipad") || h.contains("tablet")) return "Máy tính bảng";
        if (h.contains("tv") || h.contains("smarttv") || h.contains("tcl") || h.contains("sony") || h.contains("lg")) return "Smart TV / Streaming";
        if (h.contains("desktop") || h.contains("laptop") || h.contains("pc")) return "Máy tính (PC/Laptop)";
        if (h.contains("printer") || h.contains("canon") || h.contains("hp") || h.contains("epson")) return "Máy in mạng";
        return "Thiết bị mạng LAN";
    }

    /**
     * Chẩn đoán Ping chi tiết (Min, Avg, Max, Jitter, Packet Loss)
     */
    public Map<String, Object> pingDiagnostics(String host, int count, int timeoutMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("count", count);

        List<Long> latencies = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        int lost = 0;

        for (int i = 1; i <= count; i++) {
            long start = System.nanoTime();
            try {
                InetAddress addr = InetAddress.getByName(host);
                boolean ok = addr.isReachable(timeoutMs);
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                if (elapsed == 0) elapsed = 1;

                if (ok) {
                    latencies.add(elapsed);
                    logs.add("Gói #" + i + ": Phản hồi từ " + addr.getHostAddress() + " trong " + elapsed + " ms");
                } else {
                    // Probe port 80/443
                    long fbStart = System.nanoTime();
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(addr, 80), timeoutMs);
                        long fbElapsed = (System.nanoTime() - fbStart) / 1_000_000;
                        if (fbElapsed == 0) fbElapsed = 1;
                        latencies.add(fbElapsed);
                        logs.add("Gói #" + i + ": Phản hồi TCP từ " + addr.getHostAddress() + " trong " + fbElapsed + " ms");
                    } catch (Exception ex) {
                        lost++;
                        logs.add("Gói #" + i + ": Yêu cầu hết thời gian (Request timed out)");
                    }
                }
            } catch (Exception e) {
                lost++;
                logs.add("Gói #" + i + ": Lỗi - " + e.getMessage());
            }

            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        int received = count - lost;
        double lossPercent = (lost * 100.0) / count;
        long min = latencies.stream().mapToLong(v -> v).min().orElse(0);
        long max = latencies.stream().mapToLong(v -> v).max().orElse(0);
        long avg = latencies.isEmpty() ? 0 : (long) latencies.stream().mapToLong(v -> v).average().orElse(0);

        // Tính Jitter (độ biến thiên trễ)
        long jitter = 0;
        if (latencies.size() > 1) {
            long sumDiff = 0;
            for (int i = 1; i < latencies.size(); i++) {
                sumDiff += Math.abs(latencies.get(i) - latencies.get(i - 1));
            }
            jitter = sumDiff / (latencies.size() - 1);
        }

        result.put("sent", count);
        result.put("received", received);
        result.put("lost", lost);
        result.put("lossPercent", lossPercent);
        result.put("minMs", min);
        result.put("avgMs", avg);
        result.put("maxMs", max);
        result.put("jitterMs", jitter);
        result.put("logs", logs);

        return result;
    }

    /**
     * Chạy Traceroute tới máy chủ bất kỳ
     */
    public List<Map<String, Object>> runTraceroute(String host, int maxHops) {
        List<Map<String, Object>> hops = new ArrayList<>();
        if (host == null || !host.matches("^[a-zA-Z0-9][a-zA-Z0-9.\\-_:]*$") || host.length() > 255) {
            return hops;
        }
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            List<String> cmd = isWindows
                    ? Arrays.asList("tracert", "-d", "-h", String.valueOf(maxHops), "-w", "800", host)
                    : Arrays.asList("traceroute", "-m", String.valueOf(maxHops), "-w", "1", host);
            Process p = new ProcessBuilder(cmd).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int hopNum = 1;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    // Tìm dòng chứa định dạng số hop: 1    <1 ms    <1 ms    <1 ms  192.168.100.1
                    if (line.matches("^\\d+\\s+.*")) {
                        String[] parts = line.split("\\s+");
                        Map<String, Object> hop = new LinkedHashMap<>();
                        hop.put("hop", parts[0]);
                        String ip = parts[parts.length - 1];
                        hop.put("ip", ip);
                        hop.put("raw", line);
                        hops.add(hop);
                        hopNum++;
                        if (hopNum > maxHops) break;
                    }
                }
            }
            p.waitFor(12, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return hops;
    }
}

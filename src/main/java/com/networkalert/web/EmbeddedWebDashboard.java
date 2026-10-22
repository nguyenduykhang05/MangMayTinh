package com.networkalert.web;

import com.networkalert.model.*;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.ProblemRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.server.AgentManager;
import com.networkalert.server.AgentSession;
import com.networkalert.service.MaintenanceService;
import com.networkalert.service.MonitorService;
import com.networkalert.service.PushMonitorService;
import com.networkalert.service.RealNetworkService;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NamedThreadFactory;
import com.networkalert.util.NetworkValidationUtil;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import java.security.MessageDigest;

/**
 * Máy chủ Web Mini nhúng sẵn (Embedded Web Dashboard NOC)
 * Tích hợp giám sát Mạng Thật: Card mạng, Default Gateway Router, IP Public, DNS
 * Cùng các công cụ chẩn đoán trực tiếp: Ping thời gian thực, Quét thiết bị LAN, Traceroute
 *
 * Hardened theo chuẩn ERRATA §E10:
 * - Mặc định chỉ bind loopback 127.0.0.1 (E10.1)
 * - Chống DNS Rebinding bằng cách thẩm định Host header nghiêm ngặt (E10.3)
 * - Chống CSRF trên các phương thức thay đổi trạng thái bằng Origin/Bearer (E10.3)
 * - Gắn bộ Security Headers đầy đủ (CSP, nosniff, DENY, no-referrer, no-store) (E10.3)
 * - Giới hạn kích thước body <= 64 KiB (413 Payload Too Large) (E10.3)
 * - Bounded thread pool executor 16 workers (E10.3)
 * - Giới hạn đồng thời tối đa 2 tác vụ chẩn đoán nặng (E10.4)
 */
public class EmbeddedWebDashboard {
    private int port;
    private final MonitorService monitorService;
    private final IncidentRepository incidentRepository;
    private final TargetRepository targetRepository;
    private final RealNetworkService realNetworkService;
    private final com.networkalert.service.AlertService alertService;
    private String apiToken;
    private boolean allowLanAccess = false;
    private final Semaphore toolConcurrencyLimit = new Semaphore(2);

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM");

    public EmbeddedWebDashboard(int port, MonitorService monitorService, IncidentRepository incidentRepo,
                                TargetRepository targetRepo, com.networkalert.service.AlertService alertService) {
        this.port = port;
        this.monitorService = monitorService;
        this.incidentRepository = incidentRepo;
        this.targetRepository = targetRepo;
        this.alertService = alertService;
        this.realNetworkService = RealNetworkService.getInstance();
        if (alertService != null && alertService.getConfig() != null && alertService.getConfig().getApiToken() != null && !alertService.getConfig().getApiToken().isBlank()) {
            this.apiToken = alertService.getConfig().getApiToken();
        } else {
            this.apiToken = UUID.randomUUID().toString().replace("-", "");
        }
        if (alertService != null && alertService.getConfig() != null) {
            this.allowLanAccess = !alertService.getConfig().isWebDashboardLocalhostOnly();
        } else {
            this.allowLanAccess = false;
        }
    }

    public EmbeddedWebDashboard(int port, MonitorService monitorService, IncidentRepository incidentRepo, TargetRepository targetRepo) {
        this(port, monitorService, incidentRepo, targetRepo, null);
    }

    public EmbeddedWebDashboard(int port, MonitorService monitorService, IncidentRepository incidentRepo) {
        this(port, monitorService, incidentRepo, null, null);
    }

    public synchronized boolean start() {
        if (running.get()) return true;

        int attempts = 0;
        int currentPort = this.port;
        boolean localhostOnly = !this.allowLanAccess;
        if (alertService != null && alertService.getConfig() != null) {
            localhostOnly = alertService.getConfig().isWebDashboardLocalhostOnly();
            this.allowLanAccess = !localhostOnly;
        }

        while (attempts < 5) {
            try {
                InetSocketAddress bindAddr = localhostOnly
                        ? new InetSocketAddress("127.0.0.1", currentPort)
                        : new InetSocketAddress(currentPort);
                server = HttpServer.create(bindAddr, 0);
                this.port = currentPort;

                // Các route giao diện và REST API
                HttpContext[] contexts = new HttpContext[] {
                    server.createContext("/", new DashboardHtmlHandler()),
                    server.createContext("/status", new PublicStatusHtmlHandler()),
                    server.createContext("/api/public/status", new ApiPublicStatusHandler()),
                    server.createContext("/api/push", new ApiPushHandler()),
                    server.createContext("/api/status", new ApiStatusHandler()),
                    server.createContext("/api/network-info", new ApiNetworkInfoHandler()),
                    server.createContext("/api/incidents", new ApiIncidentsHandler()),
                    server.createContext("/api/topology", new ApiTopologyHandler()),
                    server.createContext("/api/problems", new ApiProblemsHandler()),
                    server.createContext("/api/check-now", new ApiCheckNowHandler()),
                    server.createContext("/api/toggle-monitor", new ApiToggleMonitorHandler()),
                    server.createContext("/api/add-target", new ApiAddTargetHandler()),
                    server.createContext("/api/tools/ping", new ApiPingToolHandler()),
                    server.createContext("/api/tools/scan-lan", new ApiScanLanHandler()),
                    server.createContext("/api/tools/traceroute", new ApiTracerouteHandler())
                };

                SecurityFilter securityFilter = new SecurityFilter();
                for (HttpContext ctx : contexts) {
                    ctx.getFilters().add(securityFilter);
                }

                executor = Executors.newFixedThreadPool(16, new NamedThreadFactory("NCAS-WebNocWorker", true));
                server.setExecutor(executor);
                server.start();
                running.set(true);

                if (localhostOnly) {
                    LoggerUtil.info("Web Dashboard (Hardened Localhost) đã hoạt động tại: http://127.0.0.1:" + this.port);
                } else {
                    String localIp = getLocalIpAddress();
                    LoggerUtil.info("Web Dashboard Mạng Thật đã hoạt động tại: http://localhost:" + this.port +
                            " (Truy cập từ điện thoại qua Wi-Fi: http://" + localIp + ":" + this.port + ")");
                }
                return true;
            } catch (IOException e) {
                attempts++;
                currentPort++;
                LoggerUtil.warn("Cổng " + (currentPort - 1) + " bị chiếm, thử cổng " + currentPort);
            }
        }

        LoggerUtil.error("Không thể khởi động Web Dashboard sau nhiều lần thử.");
        return false;
    }

    public synchronized void stop() {
        running.set(false);
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        LoggerUtil.info("Đã dừng Web Dashboard.");
    }

    public boolean isRunning() { return running.get(); }
    public int getPort() { return port; }
    public boolean isAllowLanAccess() { return allowLanAccess; }
    public void setAllowLanAccess(boolean allowLanAccess) { this.allowLanAccess = allowLanAccess; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public HttpServer getServer() { return server; }

    public String getLocalIpAddress() {
        RealNetworkInfo info = realNetworkService.getNetworkInfo();
        if (info != null && info.getLocalIp() != null && !info.getLocalIp().equals("127.0.0.1")) {
            return info.getLocalIp();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // --- CÁC HANDLER REST API ---

    private class DashboardHtmlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            String html = generateHtmlPage();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<ServerTarget> targets = monitorService.getTargets();
            int total = targets.size();
            int online = 0;
            int warning = 0;
            int offline = 0;
            int paused = 0;
            long sumLatency = 0;
            int activeCount = 0;

            StringBuilder itemsJson = new StringBuilder("[");
            for (int i = 0; i < targets.size(); i++) {
                ServerTarget t = targets.get(i);
                String status = t.getStatus().name();
                if (!t.isEnabled() || status.equals("PAUSED")) {
                    paused++;
                } else if (status.equals("ONLINE")) {
                    online++;
                    if (t.getCurrentLatency() > 0) {
                        sumLatency += t.getCurrentLatency();
                        activeCount++;
                    }
                } else if (status.equals("WARNING")) {
                    warning++;
                    if (t.getCurrentLatency() > 0) {
                        sumLatency += t.getCurrentLatency();
                        activeCount++;
                    }
                } else if (status.equals("OFFLINE")) {
                    offline++;
                }

                if (i > 0) itemsJson.append(",");
                boolean isMock = t.getHost().equals("127.0.0.1") || t.getName().contains("[Giả lập]") || t.getName().contains("Mock");
                itemsJson.append(String.format(
                        "{\"id\":%d,\"name\":\"%s\",\"host\":\"%s\",\"port\":%d,\"protocol\":\"%s\",\"status\":\"%s\",\"latency\":%d,\"uptime\":%.1f,\"enabled\":%b,\"isMock\":%b}",
                        t.getId(), escapeJson(t.getName()), escapeJson(t.getHost()), t.getPort(),
                        t.getProtocol().name(), status, t.getCurrentLatency(),
                        t.getUptimePercentage(), t.isEnabled(), isMock
                ));
            }
            itemsJson.append("]");

            long avg = activeCount > 0 ? (sumLatency / activeCount) : 0;
            String json = String.format(
                    "{\"total\":%d,\"online\":%d,\"warning\":%d,\"offline\":%d,\"paused\":%d,\"avgLatency\":%d,\"isRunning\":%b,\"servers\":%s}",
                    total, online, warning, offline, paused, avg, monitorService.isRunning(), itemsJson
            );

            sendJsonResponse(exchange, json);
        }
    }

    private class ApiNetworkInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            RealNetworkInfo net = realNetworkService.getNetworkInfo();
            StringBuilder dnsArray = new StringBuilder("[");
            for (int i = 0; i < net.getDnsServers().size(); i++) {
                if (i > 0) dnsArray.append(",");
                dnsArray.append("\"").append(escapeJson(net.getDnsServers().get(i))).append("\"");
            }
            dnsArray.append("]");

            String json = String.format(
                    "{\"interfaceName\":\"%s\",\"interfaceType\":\"%s\",\"macAddress\":\"%s\",\"localIp\":\"%s\",\"subnetMask\":\"%s\",\"prefix\":%d,\"gatewayIp\":\"%s\",\"gatewayLatency\":%d,\"gatewayStatus\":\"%s\",\"publicIp\":\"%s\",\"dnsServers\":%s,\"dnsLatency\":%d,\"internetStatus\":\"%s\",\"lastUpdated\":%d}",
                    escapeJson(net.getInterfaceName()), escapeJson(net.getInterfaceType()),
                    escapeJson(net.getMacAddress()), escapeJson(net.getLocalIp()),
                    escapeJson(net.getSubnetMask()), net.getNetworkPrefix(),
                    escapeJson(net.getGatewayIp()), net.getGatewayLatencyMs(),
                    escapeJson(net.getGatewayStatus()), escapeJson(net.getPublicIp()),
                    dnsArray, net.getDnsLatencyMs(), escapeJson(net.getInternetStatus()),
                    net.getLastUpdated()
            );

            sendJsonResponse(exchange, json);
        }
    }

    private class ApiIncidentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<IncidentLog> logs = incidentRepository.getRecentIncidents(25);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < logs.size(); i++) {
                IncidentLog log = logs.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format(
                        "{\"id\":%d,\"target\":\"%s\",\"host\":\"%s:%d\",\"event\":\"%s\",\"time\":\"%s\",\"msg\":\"%s\"}",
                        log.getId(), escapeJson(log.getTargetName()), escapeJson(log.getHost()), log.getPort(),
                        log.getEventType(), log.getEventTime() != null ? log.getEventTime().format(TIME_FMT) : "",
                        escapeJson(log.getMessage())
                ));
            }
            sb.append("]");
            sendJsonResponse(exchange, sb.toString());
        }
    }

    private class ApiTopologyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<ServerTarget> targets = monitorService.getTargets();
            AgentManager am = AgentManager.getInstance();
            Collection<AgentSession> sessions = (am != null) ? am.getConnectedSessions() : Collections.emptyList();

            StringBuilder agentsJson = new StringBuilder("[");
            int aIdx = 0;
            for (AgentSession s : sessions) {
                if (aIdx++ > 0) agentsJson.append(",");
                agentsJson.append(String.format(
                        "{\"id\":\"%s\",\"ip\":\"%s\",\"os\":\"%s\",\"cpu\":%.1f,\"memory\":%d,\"status\":\"%s\"}",
                        escapeJson(s.getAgentId()), escapeJson(s.getRemoteAddress()),
                        escapeJson(s.getOs() != null ? s.getOs() : "Unknown"),
                        s.getLastCpuUsage(), s.getLastFreeMemoryMb(),
                        s.isAuthenticated() ? "ONLINE" : "OFFLINE"
                ));
            }
            agentsJson.append("]");

            StringBuilder targetsJson = new StringBuilder("[");
            for (int i = 0; i < targets.size(); i++) {
                if (i > 0) targetsJson.append(",");
                ServerTarget t = targets.get(i);
                targetsJson.append(String.format(
                        "{\"id\":%d,\"name\":\"%s\",\"host\":\"%s\",\"port\":%d,\"protocol\":\"%s\",\"status\":\"%s\",\"latency\":%d,\"unreachable\":%b,\"flapping\":%b,\"scope\":\"%s\",\"station\":\"%s\"}",
                        t.getId(), escapeJson(t.getName()), escapeJson(t.getHost()), t.getPort(),
                        t.getProtocol().name(), t.getStatus().name(), t.getCurrentLatency(),
                        t.getStatus() == ServerStatus.UNREACHABLE, t.isFlapping(),
                        t.getScope().name(), escapeJson(t.getQuorumSummary())
                ));
            }
            targetsJson.append("]");

            String json = String.format(
                    "{\"server\":{\"name\":\"NCAS Central Server\",\"ip\":\"127.0.0.1\",\"port\":9001,\"status\":\"ONLINE\"},\"agents\":%s,\"targets\":%s}",
                    agentsJson, targetsJson
            );
            sendJsonResponse(exchange, json);
        }
    }

    private class ApiProblemsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<Problem> list = ProblemRepository.getInstance().getAllProblems();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                Problem p = list.get(i);
                sb.append(String.format(
                        "{\"id\":%d,\"targetId\":%d,\"targetName\":\"%s\",\"source\":\"%s\",\"severity\":\"%s\",\"status\":\"%s\",\"title\":\"%s\",\"duration\":\"%s\",\"ackUser\":\"%s\",\"ackComment\":\"%s\"}",
                        p.getId(), p.getTargetId(), escapeJson(p.getTargetName()), escapeJson(p.getSource()),
                        p.getSeverity().name(), p.getStatus().name(), escapeJson(p.getTitle()),
                        escapeJson(p.getFormattedDuration()),
                        escapeJson(p.getAckUser() != null ? p.getAckUser() : ""),
                        escapeJson(p.getAckComment() != null ? p.getAckComment() : "")
                ));
            }
            sb.append("]");
            sendJsonResponse(exchange, sb.toString());
        }
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> map = new HashMap<>();
        if (uri != null && uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2) {
                    try {
                        map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                } else if (pair.length == 1) {
                    try {
                        map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
                    } catch (Exception ignored) {}
                }
            }
        }
        return map;
    }

    private class ApiPushHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String token = "";
            if (path != null && path.length() > "/api/push/".length()) {
                token = path.substring("/api/push/".length()).trim();
            }

            Map<String, String> qParams = parseQueryParams(exchange.getRequestURI());
            if (token.isEmpty() && qParams.containsKey("token")) {
                token = qParams.get("token");
            }

            String status = qParams.getOrDefault("status", "up");
            String msg = qParams.getOrDefault("msg", "");
            long ping = 0;
            if (qParams.containsKey("ping")) {
                try { ping = Long.parseLong(qParams.get("ping")); } catch (Exception ignored) {}
            }

            ServerTarget target = null;
            for (ServerTarget t : monitorService.getTargets()) {
                if (token.equals(t.getPushToken())) {
                    target = t;
                    break;
                }
            }

            PushMonitorService.PushResult result = PushMonitorService.getInstance().handlePush(token, status, msg, ping, target);
            byte[] bytes = result.getJsonResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(result.getStatusCode(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ApiPublicStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<ServerTarget> targets = monitorService.getTargets();
            List<MaintenanceWindow> windows = MaintenanceService.getInstance().getAllWindows();
            List<Problem> activeProblems = ProblemRepository.getInstance().getActiveProblems();

            boolean hasOffline = false;
            boolean hasWarning = false;

            StringBuilder targetsJson = new StringBuilder("[");
            for (int i = 0; i < targets.size(); i++) {
                if (i > 0) targetsJson.append(",");
                ServerTarget t = targets.get(i);
                if (t.getStatus() == ServerStatus.OFFLINE) hasOffline = true;
                if (t.getStatus() == ServerStatus.WARNING) hasWarning = true;

                List<ServerTarget.HeartbeatRecord> hbList = t.getHeartbeats();
                StringBuilder hbArr = new StringBuilder("[");
                for (int h = 0; h < hbList.size(); h++) {
                    if (h > 0) hbArr.append(",");
                    ServerTarget.HeartbeatRecord rec = hbList.get(h);
                    hbArr.append(String.format("{\"status\":\"%s\",\"ping\":%d}", rec.getStatus().name(), rec.getLatencyMs()));
                }
                hbArr.append("]");

                targetsJson.append(String.format(
                        "{\"id\":%d,\"name\":\"%s\",\"host\":\"%s\",\"port\":%d,\"protocol\":\"%s\",\"status\":\"%s\",\"latency\":%d,\"uptime30d\":%.1f,\"tlsDays\":%d,\"heartbeats\":%s}",
                        t.getId(), escapeJson(t.getName()), escapeJson(t.getHost()), t.getPort(),
                        t.getProtocol().name(), t.getStatus().name(), t.getCurrentLatency(),
                        t.getUptimePercentage(), t.getTlsExpiryDays(), hbArr
                ));
            }
            targetsJson.append("]");

            StringBuilder maintJson = new StringBuilder("[");
            int mIdx = 0;
            for (MaintenanceWindow w : windows) {
                if (w.isActiveNow()) {
                    if (mIdx++ > 0) maintJson.append(",");
                    maintJson.append(String.format("{\"name\":\"%s\",\"schedule\":\"%s\"}", escapeJson(w.getName()), escapeJson(w.getScheduleDisplay())));
                }
            }
            maintJson.append("]");

            StringBuilder probJson = new StringBuilder("[");
            for (int p = 0; p < activeProblems.size(); p++) {
                if (p > 0) probJson.append(",");
                Problem pr = activeProblems.get(p);
                probJson.append(String.format("{\"target\":\"%s\",\"title\":\"%s\",\"severity\":\"%s\",\"duration\":\"%s\"}",
                        escapeJson(pr.getTargetName()), escapeJson(pr.getTitle()), pr.getSeverity().name(), escapeJson(pr.getFormattedDuration())));
            }
            probJson.append("]");

            String systemState = hasOffline ? "OUTAGE" : (hasWarning ? "DEGRADED" : "OPERATIONAL");
            String json = String.format("{\"systemState\":\"%s\",\"maintenance\":%s,\"problems\":%s,\"targets\":%s}",
                    systemState, maintJson, probJson, targetsJson);
            sendJsonResponse(exchange, json);
        }
    }

    private class PublicStatusHtmlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = "<!DOCTYPE html>\n" +
                    "<html lang=\"vi\">\n" +
                    "<head>\n" +
                    "  <meta charset=\"UTF-8\">\n" +
                    "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                    "  <title>Trạng Thái Hệ Thống - NCAS Status</title>\n" +
                    "  <style>\n" +
                    "    :root { --bg: #0b0f19; --card-bg: #111827; --text: #f9fafb; --text-muted: #9ca3af; --border: rgba(255,255,255,0.08); --online: #10b981; --warning: #f59e0b; --offline: #ef4444; --maintenance: #3b82f6; }\n" +
                    "    body { margin: 0; padding: 0; background: var(--bg); color: var(--text); font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; }\n" +
                    "    .container { max-width: 900px; margin: 40px auto; padding: 0 20px; }\n" +
                    "    .header { text-align: center; margin-bottom: 30px; }\n" +
                    "    .header h1 { margin: 0; font-size: 1.8rem; font-weight: 700; }\n" +
                    "    .header p { color: var(--text-muted); margin-top: 6px; font-size: 0.95rem; }\n" +
                    "    .hero-banner { padding: 16px 20px; border-radius: 12px; margin-bottom: 24px; display: flex; align-items: center; justify-content: space-between; font-weight: 600; font-size: 1.1rem; }\n" +
                    "    .hero-online { background: rgba(16, 185, 129, 0.15); border: 1px solid var(--online); color: var(--online); }\n" +
                    "    .hero-degraded { background: rgba(245, 158, 11, 0.15); border: 1px solid var(--warning); color: var(--warning); }\n" +
                    "    .hero-outage { background: rgba(239, 68, 68, 0.15); border: 1px solid var(--offline); color: var(--offline); }\n" +
                    "    .alert-banner { padding: 12px 16px; border-radius: 8px; margin-bottom: 16px; font-size: 0.9rem; }\n" +
                    "    .banner-maint { background: rgba(59, 130, 246, 0.12); border: 1px solid var(--maintenance); color: #60a5fa; }\n" +
                    "    .banner-prob { background: rgba(239, 68, 68, 0.12); border: 1px solid var(--offline); color: #f87171; }\n" +
                    "    .card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 12px; padding: 16px 20px; margin-bottom: 14px; }\n" +
                    "    .service-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }\n" +
                    "    .service-title { font-weight: 600; font-size: 1.05rem; }\n" +
                    "    .service-meta { color: var(--text-muted); font-size: 0.8rem; margin-top: 3px; }\n" +
                    "    .badge { padding: 4px 10px; border-radius: 20px; font-size: 0.75rem; font-weight: 700; }\n" +
                    "    .badge-online { background: rgba(16, 185, 129, 0.2); color: var(--online); }\n" +
                    "    .badge-warning { background: rgba(245, 158, 11, 0.2); color: var(--warning); }\n" +
                    "    .badge-offline { background: rgba(239, 68, 68, 0.2); color: var(--offline); }\n" +
                    "    .badge-maintenance { background: rgba(59, 130, 246, 0.2); color: var(--maintenance); }\n" +
                    "    .heartbeat-bar { display: flex; gap: 3px; height: 26px; align-items: center; margin-bottom: 8px; }\n" +
                    "    .hb-slot { flex: 1; height: 18px; border-radius: 2px; transition: transform 0.1s; }\n" +
                    "    .hb-slot:hover { transform: scaleY(1.3); }\n" +
                    "    .service-metrics { display: flex; justify-content: space-between; color: var(--text-muted); font-size: 0.8rem; }\n" +
                    "    .footer { text-align: center; margin-top: 40px; color: var(--text-muted); font-size: 0.8rem; }\n" +
                    "  </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "  <div class=\"container\">\n" +
                    "    <div class=\"header\">\n" +
                    "      <h1>📡 NCAS System Status</h1>\n" +
                    "      <p>Trang theo dõi tình trạng hạ tầng và dịch vụ trực tuyến công khai</p>\n" +
                    "    </div>\n" +
                    "    <div id=\"hero-box\" class=\"hero-banner hero-online\">\n" +
                    "      <span id=\"hero-text\">🟢 TẤT CẢ DỊCH VỤ HOẠT ĐỘNG BÌNH THƯỜNG</span>\n" +
                    "      <span id=\"live-time\" style=\"font-size:0.85rem; font-weight:normal; opacity:0.8;\"></span>\n" +
                    "    </div>\n" +
                    "    <div id=\"maint-box\"></div>\n" +
                    "    <div id=\"prob-box\"></div>\n" +
                    "    <div id=\"services-list\"></div>\n" +
                    "    <div class=\"footer\">\n" +
                    "      NCAS Distributed Alert System &copy; 2026 &bull; Tự động cập nhật mỗi 10 giây\n" +
                    "    </div>\n" +
                    "  </div>\n" +
                    "  <script>\n" +
                    "    async function loadStatus() {\n" +
                    "      try {\n" +
                    "        const res = await fetch('/api/public/status');\n" +
                    "        const data = await res.json();\n" +
                    "        document.getElementById('live-time').textContent = new Date().toLocaleTimeString('vi-VN');\n" +
                    "        const heroBox = document.getElementById('hero-box');\n" +
                    "        const heroText = document.getElementById('hero-text');\n" +
                    "        if (data.systemState === 'OUTAGE') {\n" +
                    "          heroBox.className = 'hero-banner hero-outage'; heroText.textContent = '🔴 PHÁT HIỆN SỰ CỐ MẤT KẾT NỐI HỆ THỐNG';\n" +
                    "        } else if (data.systemState === 'DEGRADED') {\n" +
                    "          heroBox.className = 'hero-banner hero-degraded'; heroText.textContent = '🟡 MỘT SỐ HỆ THỐNG PHẢN HỒI CHẬM';\n" +
                    "        } else {\n" +
                    "          heroBox.className = 'hero-banner hero-online'; heroText.textContent = '🟢 TẤT CẢ HỆ THỐNG ĐANG HOẠT ĐỘNG ỔN ĐỊNH';\n" +
                    "        }\n" +
                    "        const maintBox = document.getElementById('maint-box');\n" +
                    "        if (data.maintenance && data.maintenance.length > 0) {\n" +
                    "          maintBox.innerHTML = data.maintenance.map(m => `<div class=\"alert-banner banner-maint\">🔧 <b>Đang trong lịch bảo trì:</b> ${m.name} (${m.schedule})</div>`).join('');\n" +
                    "        } else { maintBox.innerHTML = ''; }\n" +
                    "        const probBox = document.getElementById('prob-box');\n" +
                    "        if (data.problems && data.problems.length > 0) {\n" +
                    "          probBox.innerHTML = data.problems.map(p => `<div class=\"alert-banner banner-prob\">⚠️ <b>Sự cố đang xử lý:</b> [${p.target}] ${p.title} (${p.severity} - Duy trì: ${p.duration})</div>`).join('');\n" +
                    "        } else { probBox.innerHTML = ''; }\n" +
                    "        const listDiv = document.getElementById('services-list');\n" +
                    "        const colorMap = { 'ONLINE': '#10b981', 'WARNING': '#f59e0b', 'OFFLINE': '#ef4444', 'MAINTENANCE': '#3b82f6', 'PAUSED': '#475569', 'UNREACHABLE': '#64748b' };\n" +
                    "        listDiv.innerHTML = (data.targets || []).map(t => {\n" +
                    "          const badgeClass = 'badge-' + t.status.toLowerCase();\n" +
                    "          const slotsHtml = (t.heartbeats || []).map(hb => {\n" +
                    "            const bg = colorMap[hb.status] || '#334155';\n" +
                    "            return `<div class=\"hb-slot\" style=\"background:${bg}\" title=\"${hb.status}: ${hb.ping >= 0 ? hb.ping + 'ms' : 'N/A'}\"></div>`;\n" +
                    "          }).join('');\n" +
                    "          const tlsBadge = (t.tlsDays >= 0) ? `<span style=\"font-size:0.75rem; color:#94a3b8; margin-left:8px;\">🔒 TLS: ${t.tlsDays} ngày</span>` : '';\n" +
                    "          return `\n" +
                    "            <div class=\"card\">\n" +
                    "              <div class=\"service-head\">\n" +
                    "                <div><div class=\"service-title\">${t.name} ${tlsBadge}</div><div class=\"service-meta\">${t.protocol} &bull; ${t.host}${t.port > 0 ? ':' + t.port : ''}</div></div>\n" +
                    "                <span class=\"badge ${badgeClass}\">${t.status}</span>\n" +
                    "              </div>\n" +
                    "              <div class=\"heartbeat-bar\">${slotsHtml || '<span style=\"color:#64748b; font-size:0.75rem;\">Chưa có nhịp tim</span>'}</div>\n" +
                    "              <div class=\"service-metrics\">\n" +
                    "                <span>60 lần kiểm tra gần nhất</span>\n" +
                    "                <span><b>${t.latency >= 0 ? t.latency + ' ms' : 'N/A'}</b> &bull; Uptime 30 ngày: <b>${t.uptime30d.toFixed(1)}%</b></span>\n" +
                    "              </div>\n" +
                    "            </div>\n" +
                    "          `;\n" +
                    "        }).join('');\n" +
                    "      } catch (e) { console.error('Lỗi tải trạng thái:', e); }\n" +
                    "    }\n" +
                    "    loadStatus();\n" +
                    "    setInterval(loadStatus, 10000);\n" +
                    "  </script>\n" +
                    "</body>\n" +
                    "</html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class SecurityFilter extends Filter {
        @Override
        public String description() {
            return "NCAS Web NOC Security Hardening Filter (Host validation, CSRF, Security Headers)";
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            Headers respHeaders = exchange.getResponseHeaders();
            // Gắn Security Headers theo ERRATA §E10.3
            respHeaders.set("X-Content-Type-Options", "nosniff");
            respHeaders.set("X-Frame-Options", "DENY");
            respHeaders.set("Referrer-Policy", "no-referrer");
            respHeaders.set("Content-Security-Policy", "default-src 'self' 'unsafe-inline' data:;");
            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "";
            if (path != null && path.startsWith("/api/")) {
                respHeaders.set("Cache-Control", "no-store");
            }

            // 1. Chống DNS Rebinding: Kiểm tra Host header
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            if (!isHostAllowed(hostHeader)) {
                sendForbidden(exchange, "Forbidden: Invalid Host header (DNS Rebinding defense)");
                return;
            }

            // 2. Giới hạn độ dài Content-Length
            String cl = exchange.getRequestHeaders().getFirst("Content-Length");
            if (cl != null) {
                try {
                    long len = Long.parseLong(cl.trim());
                    if (len > 65536) {
                        sendPayloadTooLarge(exchange);
                        return;
                    }
                } catch (NumberFormatException ignored) {}
            }

            // 3. Chống CSRF trên các method thay đổi trạng thái (POST, PUT, DELETE, PATCH)
            String method = exchange.getRequestMethod().toUpperCase();
            if (method.equals("POST") || method.equals("PUT") || method.equals("DELETE") || method.equals("PATCH")) {
                // Ngoại lệ: /api/push/{token} sử dụng token bí mật trực tiếp trong URL đường dẫn
                if (path == null || !path.startsWith("/api/push")) {
                    String origin = exchange.getRequestHeaders().getFirst("Origin");
                    boolean hasOrigin = isOriginAllowed(origin);
                    boolean hasBearer = hasValidBearerToken(exchange);
                    if (!hasOrigin && !hasBearer) {
                        sendForbidden(exchange, "Forbidden: CSRF check failed - missing valid Origin or Bearer token");
                        return;
                    }
                }
            }

            chain.doFilter(exchange);
        }
    }

    private void sendForbidden(HttpExchange exchange, String message) throws IOException {
        byte[] body = ("{\"success\":false,\"error\":\"" + escapeJson(message) + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(403, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendPayloadTooLarge(HttpExchange exchange) throws IOException {
        byte[] body = "{\"success\":false,\"error\":\"Payload Too Large: Dung lượng request tối đa 64 KiB\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(413, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public boolean isHostAllowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String h = hostHeader.trim().toLowerCase();
        String hostOnly = h;
        if (h.startsWith("[")) {
            int closeBracket = h.indexOf(']');
            if (closeBracket != -1) {
                hostOnly = h.substring(0, closeBracket + 1);
            }
        } else {
            int colon = h.indexOf(':');
            if (colon != -1) {
                hostOnly = h.substring(0, colon);
            }
        }

        if (hostOnly.equals("localhost") || hostOnly.equals("127.0.0.1") || hostOnly.equals("[::1]")) {
            return true;
        }

        if (allowLanAccess) {
            String localIp = getLocalIpAddress();
            if (localIp != null && hostOnly.equals(localIp.toLowerCase())) {
                return true;
            }
            try {
                String hostName = InetAddress.getLocalHost().getHostName().toLowerCase();
                if (hostOnly.equals(hostName)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")) {
                return true;
            }
            if (allowLanAccess) {
                String localIp = getLocalIpAddress();
                if (localIp != null && host.equals(localIp.toLowerCase())) {
                    return true;
                }
                try {
                    String hostName = InetAddress.getLocalHost().getHostName().toLowerCase();
                    if (host.equals(hostName)) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean hasValidBearerToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (this.apiToken != null && !this.apiToken.isBlank()) {
                return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), apiToken.getBytes(StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    private boolean isValidHost(String host) {
        if (host == null || host.isBlank() || host.length() > 255) return false;
        if (host.startsWith("-")) return false;
        return NetworkValidationUtil.isValidHost(host);
    }

    private boolean isAuthorized(HttpExchange exchange, Map<String, String> bodyParams) {
        // Trang công khai /status và API đẩy nhịp tim /api/push không yêu cầu Token
        URI reqUri = exchange.getRequestURI();
        if (reqUri != null && reqUri.getPath() != null) {
            String p = reqUri.getPath();
            if (p.equals("/status") || p.startsWith("/api/public/") || p.startsWith("/api/push")) {
                return true;
            }
        }

        if (this.apiToken == null || this.apiToken.isBlank()) {
            return true;
        }
        // 1. Kiểm tra Authorization header: Bearer <token>
        if (hasValidBearerToken(exchange)) {
            return true;
        }
        // 2. Kiểm tra query param: ?token=...
        URI uri = exchange.getRequestURI();
        if (uri != null && uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2 && "token".equalsIgnoreCase(pair[0])) {
                    String token = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), apiToken.getBytes(StandardCharsets.UTF_8))) {
                        return true;
                    }
                }
            }
        }
        // 3. Kiểm tra bodyParams
        if (bodyParams != null && bodyParams.containsKey("token")) {
            String token = bodyParams.get("token");
            if (token != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), apiToken.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"success\":false,\"error\":\"Unauthorized: Cần cung cấp API Token hợp lệ qua Authorization header hoặc query param ?token=\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(401, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private class ApiCheckNowHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Chỉ chấp nhận phương thức POST");
                return;
            }
            Map<String, String> params;
            try {
                params = parseBody(exchange);
            } catch (PayloadTooLargeException e) {
                sendPayloadTooLarge(exchange);
                return;
            }
            if (!isAuthorized(exchange, params)) {
                sendUnauthorized(exchange);
                return;
            }
            String idStr = params.get("id");
            if (idStr != null && !idStr.isEmpty()) {
                try {
                    int id = Integer.parseInt(idStr);
                    ServerTarget t = monitorService.getTargets().stream().filter(x -> x.getId() == id).findFirst().orElse(null);
                    if (t != null) {
                        monitorService.checkTargetNow(t);
                    }
                } catch (Exception ignored) {}
            } else {
                for (ServerTarget t : monitorService.getTargets()) {
                    if (t.isEnabled()) {
                        if (executor != null && !executor.isShutdown()) {
                            executor.submit(() -> monitorService.checkTargetNow(t));
                        } else {
                            monitorService.checkTargetNow(t);
                        }
                    }
                }
            }
            sendJsonResponse(exchange, "{\"success\":true,\"message\":\"Đang kiểm tra ngay lập tức...\"}");
        }
    }

    private class ApiToggleMonitorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> params;
            try {
                params = parseBody(exchange);
            } catch (PayloadTooLargeException e) {
                sendPayloadTooLarge(exchange);
                return;
            }
            if (!isAuthorized(exchange, params)) {
                sendUnauthorized(exchange);
                return;
            }
            if (monitorService.isRunning()) {
                monitorService.stopAll();
            } else {
                monitorService.startAll();
            }
            sendJsonResponse(exchange, "{\"success\":true,\"isRunning\":" + monitorService.isRunning() + "}");
        }
    }

    private class ApiAddTargetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> p;
            try {
                p = parseBody(exchange);
            } catch (PayloadTooLargeException e) {
                sendPayloadTooLarge(exchange);
                return;
            }
            if (!isAuthorized(exchange, p)) {
                sendUnauthorized(exchange);
                return;
            }
            String name = p.getOrDefault("name", "").trim();
            String host = p.getOrDefault("host", "").trim();
            if (name.isEmpty() || host.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Tên và Địa chỉ host không được để trống\"}");
                return;
            }
            if (!isValidHost(host)) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Địa chỉ host không hợp lệ (không được bắt đầu bằng '-' hoặc chứa ký tự đặc biệt)\"}");
                return;
            }
            int port = 80;
            try { port = Integer.parseInt(p.getOrDefault("port", "80")); } catch (Exception ignored) {}
            ProtocolType proto = ProtocolType.fromString(p.getOrDefault("protocol", "TCP"));
            int interval = 5;
            try { interval = Integer.parseInt(p.getOrDefault("interval", "5")); } catch (Exception ignored) {}
            int timeout = 1500;
            try { timeout = Integer.parseInt(p.getOrDefault("timeout", "1500")); } catch (Exception ignored) {}

            ServerTarget newT = new ServerTarget(0, name, host, port, proto, interval, timeout, true);
            if (targetRepository != null) {
                targetRepository.addTarget(newT);
            }
            monitorService.addTarget(newT);

            sendJsonResponse(exchange, "{\"success\":true,\"message\":\"Đã thêm máy chủ " + escapeJson(name) + " vào hệ thống giám sát.\"}");
        }
    }

    private class ApiPingToolHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> p;
            try {
                p = parseBody(exchange);
            } catch (PayloadTooLargeException e) {
                sendPayloadTooLarge(exchange);
                return;
            }
            if (!isAuthorized(exchange, p)) {
                sendUnauthorized(exchange);
                return;
            }
            String host = p.getOrDefault("host", "").trim();
            if (host.isEmpty()) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Vui lòng nhập Host / IP cần Ping\"}");
                return;
            }
            if (!isValidHost(host)) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Địa chỉ host không hợp lệ (không được bắt đầu bằng '-' hoặc chứa ký tự không an toàn)\"}");
                return;
            }

            if (!toolConcurrencyLimit.tryAcquire()) {
                sendResponse(exchange, 429, "{\"success\":false,\"message\":\"Quá tải: Tối đa 2 tác vụ chẩn đoán đồng thời\"}");
                return;
            }
            try {
                int count = 4;
                try { count = Integer.parseInt(p.getOrDefault("count", "4")); } catch (Exception ignored) {}
                count = Math.min(10, Math.max(1, count));

                Map<String, Object> res = realNetworkService.pingDiagnostics(host, count, 1000);
                StringBuilder logsJson = new StringBuilder("[");
                @SuppressWarnings("unchecked")
                List<String> logs = (List<String>) res.get("logs");
                if (logs != null) {
                    for (int i = 0; i < logs.size(); i++) {
                        if (i > 0) logsJson.append(",");
                        logsJson.append("\"").append(escapeJson(logs.get(i))).append("\"");
                    }
                }
                logsJson.append("]");

                String json = String.format(
                        "{\"success\":true,\"host\":\"%s\",\"sent\":%s,\"received\":%s,\"lost\":%s,\"lossPercent\":%s,\"minMs\":%s,\"avgMs\":%s,\"maxMs\":%s,\"jitterMs\":%s,\"logs\":%s}",
                        escapeJson(host), res.get("sent"), res.get("received"), res.get("lost"), res.get("lossPercent"),
                        res.get("minMs"), res.get("avgMs"), res.get("maxMs"), res.get("jitterMs"), logsJson
                );
                sendJsonResponse(exchange, json);
            } finally {
                toolConcurrencyLimit.release();
            }
        }
    }

    private class ApiScanLanHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> body = null;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    body = parseBody(exchange);
                } catch (PayloadTooLargeException e) {
                    sendPayloadTooLarge(exchange);
                    return;
                }
            }
            if (!isAuthorized(exchange, body)) {
                sendUnauthorized(exchange);
                return;
            }

            String subnet = null;
            URI reqUri = exchange.getRequestURI();
            if (reqUri != null && reqUri.getQuery() != null) {
                for (String q : reqUri.getQuery().split("&")) {
                    String[] parts = q.split("=", 2);
                    if (parts.length == 2 && "subnet".equalsIgnoreCase(parts[0])) {
                        subnet = URLDecoder.decode(parts[1], StandardCharsets.UTF_8).trim();
                        break;
                    }
                }
            }
            if (subnet == null && body != null) {
                subnet = body.get("subnet");
            }
            if (subnet != null && !subnet.isBlank()) {
                String cleanSubnet = subnet.trim();
                String localIp = getLocalIpAddress();
                int lastDot = localIp.lastIndexOf('.');
                String serverSubnetPrefix = (lastDot > 0) ? localIp.substring(0, lastDot + 1) : "127.0.0.";
                if (!cleanSubnet.startsWith(serverSubnetPrefix) || cleanSubnet.endsWith("/16") || cleanSubnet.endsWith("/8") || cleanSubnet.endsWith("/0")) {
                    sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Chỉ được phép quét trong dải /24 của máy chủ hiện tại (" + serverSubnetPrefix + "0/24)\"}");
                    return;
                }
            }

            if (!toolConcurrencyLimit.tryAcquire()) {
                sendResponse(exchange, 429, "{\"success\":false,\"message\":\"Quá tải: Tối đa 2 tác vụ chẩn đoán đồng thời\"}");
                return;
            }
            try {
                List<LanDevice> devices = realNetworkService.scanLanSubnet(500);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < devices.size(); i++) {
                    LanDevice d = devices.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(String.format(
                            "{\"ip\":\"%s\",\"mac\":\"%s\",\"hostname\":\"%s\",\"latency\":%d,\"deviceType\":\"%s\",\"isGateway\":%b,\"isCurrent\":%b}",
                            escapeJson(d.getIp()), escapeJson(d.getMac()), escapeJson(d.getHostname()),
                            d.getLatencyMs(), escapeJson(d.getDeviceType()), d.isGateway(), d.isCurrentDevice()
                    ));
                }
                sb.append("]");
                sendJsonResponse(exchange, String.format("{\"success\":true,\"count\":%d,\"devices\":%s}", devices.size(), sb));
            } finally {
                toolConcurrencyLimit.release();
            }
        }
    }

    private class ApiTracerouteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> p;
            try {
                p = parseBody(exchange);
            } catch (PayloadTooLargeException e) {
                sendPayloadTooLarge(exchange);
                return;
            }
            if (!isAuthorized(exchange, p)) {
                sendUnauthorized(exchange);
                return;
            }
            String host = p.getOrDefault("host", "8.8.8.8").trim();
            if (host.isEmpty() || !isValidHost(host)) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Địa chỉ host không hợp lệ (không được bắt đầu bằng '-' hoặc chứa ký tự không an toàn)\"}");
                return;
            }

            if (!toolConcurrencyLimit.tryAcquire()) {
                sendResponse(exchange, 429, "{\"success\":false,\"message\":\"Quá tải: Tối đa 2 tác vụ chẩn đoán đồng thời\"}");
                return;
            }
            try {
                int maxHops = 8;
                try { maxHops = Integer.parseInt(p.getOrDefault("maxHops", "8")); } catch (Exception ignored) {}
                maxHops = Math.min(15, Math.max(1, maxHops));

                List<Map<String, Object>> hops = realNetworkService.runTraceroute(host, maxHops);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < hops.size(); i++) {
                    Map<String, Object> h = hops.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"hop\":\"%s\",\"ip\":\"%s\",\"raw\":\"%s\"}",
                            escapeJson(String.valueOf(h.get("hop"))), escapeJson(String.valueOf(h.get("ip"))), escapeJson(String.valueOf(h.get("raw")))));
                }
                sb.append("]");
                sendJsonResponse(exchange, String.format("{\"success\":true,\"host\":\"%s\",\"hops\":%s}", escapeJson(host), sb));
            } finally {
                toolConcurrencyLimit.release();
            }
        }
    }

    // --- HELPER UTILITIES ---

    private void sendJsonResponse(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (isOriginAllowed(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        } else {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://127.0.0.1:" + this.port);
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        if (msg.trim().startsWith("{") || msg.trim().startsWith("[")) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        } else {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) { super(message); }
    }

    private Map<String, String> parseBody(HttpExchange exchange) throws IOException {
        Map<String, String> map = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int totalBytes = 0;
        try (InputStream is = exchange.getRequestBody()) {
            int read;
            while ((read = is.read(buf)) != -1) {
                totalBytes += read;
                if (totalBytes > 65536) {
                    throw new PayloadTooLargeException("Request body exceeds 64 KiB");
                }
                sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
            }
        }
        String raw = sb.toString().trim();
        if (raw.startsWith("{") && raw.endsWith("}")) {
            // Đọc JSON phẳng cơ bản
            raw = raw.substring(1, raw.length() - 1);
            String[] tokens = raw.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String t : tokens) {
                String[] kv = t.split(":", 2);
                if (kv.length == 2) {
                    String k = kv[0].trim().replace("\"", "");
                    String v = kv[1].trim().replace("\"", "");
                    map.put(k, v);
                }
            }
        } else if (!raw.isEmpty()) {
            // Form URL encoded: key=val&key2=val2
            String[] pairs = raw.split("&");
            for (String p : pairs) {
                String[] kv = p.split("=", 2);
                if (kv.length == 2) {
                    map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
            }
        }
        return map;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    /**
     * Giao diện HTML5 / CSS3 / Vanilla JS cao cấp chuẩn NOC (Network Operations Center)
     */
    private String generateHtmlPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"vi\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>NCAS - Trung Tâm Giám Sát & Cảnh Báo Mạng Thật (Real NOC Dashboard)</title>\n" +
                "  <link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n" +
                "  <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n" +
                "  <link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap\" rel=\"stylesheet\">\n" +
                "  <style>\n" +
                "    :root {\n" +
                "      --bg-body: #07090e;\n" +
                "      --bg-surface: rgba(15, 23, 42, 0.75);\n" +
                "      --bg-card: rgba(30, 41, 59, 0.55);\n" +
                "      --bg-card-hover: rgba(51, 65, 85, 0.65);\n" +
                "      --border: rgba(255, 255, 255, 0.08);\n" +
                "      --border-glow: rgba(59, 130, 246, 0.4);\n" +
                "      --text-main: #f8fafc;\n" +
                "      --text-muted: #94a3b8;\n" +
                "      --accent-blue: #38bdf8;\n" +
                "      --accent-purple: #a855f7;\n" +
                "      --accent-indigo: #6366f1;\n" +
                "      --status-online: #10b981;\n" +
                "      --status-warning: #f59e0b;\n" +
                "      --status-offline: #ef4444;\n" +
                "      --status-paused: #64748b;\n" +
                "    }\n" +
                "    * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Inter', -apple-system, sans-serif; }\n" +
                "    body { background: radial-gradient(circle at 50% 0%, #1e1b4b 0%, var(--bg-body) 65%); color: var(--text-main); min-height: 100vh; padding: 20px; }\n" +
                "    .container { max-width: 1400px; margin: 0 auto; }\n" +
                "    \n" +
                "    /* Header & Top Bar */\n" +
                "    header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 24px; padding-bottom: 18px; border-bottom: 1px solid var(--border); }\n" +
                "    .brand { display: flex; align-items: center; gap: 14px; }\n" +
                "    .logo-badge { width: 44px; height: 44px; border-radius: 12px; background: linear-gradient(135deg, #2563eb, #7c3aed); display: flex; align-items: center; justify-content: center; font-size: 22px; box-shadow: 0 0 20px rgba(59, 130, 246, 0.4); }\n" +
                "    .brand-text h1 { font-size: 1.35rem; font-weight: 800; letter-spacing: -0.02em; background: linear-gradient(to right, #60a5fa, #c084fc); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }\n" +
                "    .brand-text p { font-size: 0.8rem; color: var(--text-muted); }\n" +
                "    .header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }\n" +
                "    .btn { background: var(--bg-card); border: 1px solid var(--border); color: var(--text-main); padding: 8px 16px; border-radius: 8px; font-size: 0.85rem; font-weight: 600; cursor: pointer; transition: all 0.2s ease; display: inline-flex; align-items: center; gap: 8px; }\n" +
                "    .btn:hover { background: var(--bg-card-hover); border-color: rgba(255,255,255,0.2); transform: translateY(-1px); }\n" +
                "    .btn-primary { background: linear-gradient(135deg, #2563eb, #1d4ed8); border-color: #3b82f6; box-shadow: 0 4px 14px rgba(37,99,235,0.3); }\n" +
                "    .btn-primary:hover { background: linear-gradient(135deg, #1d4ed8, #1e40af); }\n" +
                "    .btn-success { background: linear-gradient(135deg, #059669, #047857); border-color: #10b981; }\n" +
                "    .live-pulse { width: 10px; height: 10px; border-radius: 50%; background: var(--status-online); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); animation: pulse 2s infinite; }\n" +
                "    @keyframes pulse { 0% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); } 70% { box-shadow: 0 0 0 10px rgba(16, 185, 129, 0); } 100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); } }\n" +
                "    \n" +
                "    /* Real Network Topology Pipeline */\n" +
                "    .topology-container { background: var(--bg-surface); backdrop-filter: blur(16px); border: 1px solid var(--border); border-radius: 14px; padding: 20px; margin-bottom: 24px; box-shadow: 0 8px 32px rgba(0,0,0,0.3); }\n" +
                "    .topology-title { font-size: 0.85rem; font-weight: 700; text-transform: uppercase; color: var(--accent-blue); letter-spacing: 0.05em; margin-bottom: 16px; display: flex; align-items: center; justify-content: space-between; }\n" +
                "    .topology-nodes { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 16px; position: relative; }\n" +
                "    .node-card { background: rgba(15, 23, 42, 0.9); border: 1px solid var(--border); border-radius: 10px; padding: 14px; position: relative; transition: all 0.2s; }\n" +
                "    .node-card:hover { border-color: var(--border-glow); transform: translateY(-2px); }\n" +
                "    .node-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }\n" +
                "    .node-type { font-size: 0.7rem; color: var(--text-muted); text-transform: uppercase; font-weight: 700; }\n" +
                "    .node-badge { font-size: 0.7rem; font-weight: 700; padding: 2px 8px; border-radius: 10px; }\n" +
                "    .badge-online { background: rgba(16, 185, 129, 0.15); color: var(--status-online); border: 1px solid rgba(16, 185, 129, 0.3); }\n" +
                "    .badge-warning { background: rgba(245, 158, 11, 0.15); color: var(--status-warning); border: 1px solid rgba(245, 158, 11, 0.3); }\n" +
                "    .badge-offline { background: rgba(239, 68, 68, 0.15); color: var(--status-offline); border: 1px solid rgba(239, 68, 68, 0.3); }\n" +
                "    .node-name { font-size: 1rem; font-weight: 700; color: #fff; margin-bottom: 4px; }\n" +
                "    .node-desc { font-size: 0.8rem; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; word-break: break-all; }\n" +
                "    .node-metric { margin-top: 8px; padding-top: 8px; border-top: 1px dashed rgba(255,255,255,0.08); font-size: 0.75rem; display: flex; justify-content: space-between; }\n" +
                "    \n" +
                "    /* KPI Summary Cards */\n" +
                "    .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 14px; margin-bottom: 24px; }\n" +
                "    .kpi-card { background: var(--bg-surface); backdrop-filter: blur(12px); border: 1px solid var(--border); border-radius: 12px; padding: 16px; position: relative; overflow: hidden; }\n" +
                "    .kpi-card::after { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: var(--accent-blue); }\n" +
                "    .kpi-card.green::after { background: var(--status-online); }\n" +
                "    .kpi-card.yellow::after { background: var(--status-warning); }\n" +
                "    .kpi-card.red::after { background: var(--status-offline); }\n" +
                "    .kpi-label { font-size: 0.75rem; text-transform: uppercase; color: var(--text-muted); font-weight: 600; margin-bottom: 8px; }\n" +
                "    .kpi-num { font-size: 1.8rem; font-weight: 800; letter-spacing: -0.02em; }\n" +
                "    \n" +
                "    /* Main Content Layout (Left: Targets & Tools, Right: Live Chart & Incidents) */\n" +
                "    .content-grid { display: grid; grid-template-columns: 1.8fr 1.2fr; gap: 20px; }\n" +
                "    @media (max-width: 1024px) { .content-grid { grid-template-columns: 1fr; } }\n" +
                "    \n" +
                "    .panel { background: var(--bg-surface); backdrop-filter: blur(16px); border: 1px solid var(--border); border-radius: 14px; padding: 20px; margin-bottom: 20px; }\n" +
                "    .panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }\n" +
                "    .panel-title { font-size: 1.05rem; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 10px; }\n" +
                "    \n" +
                "    /* Filter Pills */\n" +
                "    .filter-tabs { display: flex; gap: 6px; }\n" +
                "    .tab-btn { background: rgba(255,255,255,0.05); border: 1px solid var(--border); color: var(--text-muted); padding: 5px 12px; border-radius: 6px; font-size: 0.75rem; font-weight: 600; cursor: pointer; transition: all 0.2s; }\n" +
                "    .tab-btn.active { background: #2563eb; color: #fff; border-color: #3b82f6; }\n" +
                "    \n" +
                "    /* Modern Table */\n" +
                "    .table-responsive { overflow-x: auto; }\n" +
                "    table { width: 100%; border-collapse: collapse; text-align: left; font-size: 0.85rem; }\n" +
                "    th { background: rgba(15, 23, 42, 0.8); padding: 12px 14px; color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--border); font-size: 0.75rem; text-transform: uppercase; }\n" +
                "    td { padding: 12px 14px; border-bottom: 1px solid rgba(255,255,255,0.04); vertical-align: middle; }\n" +
                "    tr:hover { background: rgba(255,255,255,0.02); }\n" +
                "    .badge-proto { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 0.7rem; font-weight: 700; font-family: 'JetBrains Mono', monospace; background: rgba(56, 189, 248, 0.15); color: #38bdf8; border: 1px solid rgba(56, 189, 248, 0.3); }\n" +
                "    .target-title { font-weight: 600; color: #fff; }\n" +
                "    .target-host { font-size: 0.75rem; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }\n" +
                "    \n" +
                "    /* Diagnostic Tools Section */\n" +
                "    .tool-tabs { display: flex; gap: 8px; border-bottom: 1px solid var(--border); padding-bottom: 10px; margin-bottom: 16px; }\n" +
                "    .tool-tab { background: transparent; border: none; color: var(--text-muted); font-size: 0.9rem; font-weight: 600; cursor: pointer; padding: 6px 12px; border-radius: 6px; }\n" +
                "    .tool-tab.active { background: rgba(255,255,255,0.08); color: var(--accent-blue); }\n" +
                "    .tool-pane { display: none; }\n" +
                "    .tool-pane.active { display: block; }\n" +
                "    .input-group { display: flex; gap: 10px; margin-bottom: 12px; }\n" +
                "    .input-text { flex: 1; background: rgba(15, 23, 42, 0.9); border: 1px solid var(--border); border-radius: 8px; padding: 10px 14px; color: #fff; font-size: 0.85rem; font-family: 'JetBrains Mono', monospace; outline: none; }\n" +
                "    .input-text:focus { border-color: var(--accent-blue); }\n" +
                "    .console-out { background: #000; border: 1px solid var(--border); border-radius: 8px; padding: 12px; font-family: 'JetBrains Mono', monospace; font-size: 0.8rem; color: #10b981; max-height: 180px; overflow-y: auto; white-space: pre-wrap; line-height: 1.4; }\n" +
                "    \n" +
                "    /* Modal Dialog */\n" +
                "    .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.7); backdrop-filter: blur(8px); display: none; align-items: center; justify-content: center; z-index: 999; }\n" +
                "    .modal-box { background: #0f172a; border: 1px solid var(--border); border-radius: 14px; width: 90%; max-width: 480px; padding: 24px; box-shadow: 0 20px 40px rgba(0,0,0,0.6); }\n" +
                "    .form-group { margin-bottom: 14px; }\n" +
                "    .form-label { display: block; font-size: 0.8rem; color: var(--text-muted); margin-bottom: 6px; font-weight: 600; }\n" +
                "    \n" +
                "    /* Realtime Latency Canvas */\n" +
                "    #latencyCanvas { width: 100%; height: 200px; background: rgba(15, 23, 42, 0.6); border-radius: 8px; border: 1px solid var(--border); }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"container\">\n" +
                "    <!-- Header -->\n" +
                "    <header>\n" +
                "      <div class=\"brand\">\n" +
                "        <div class=\"logo-badge\">🌐</div>\n" +
                "        <div class=\"brand-text\">\n" +
                "          <h1>NCAS • Network Operations Center</h1>\n" +
                "          <p>Hệ Thống Giám Sát & Chẩn Đoán Mạng Thật Thời Gian Thực</p>\n" +
                "        </div>\n" +
                "      </div>\n" +
                "      <div class=\"header-actions\">\n" +
                "        <div class=\"btn\" style=\"cursor:default\"><div class=\"live-pulse\"></div> <span id=\"clock-live\">--:--:--</span></div>\n" +
                "        <button class=\"btn btn-primary\" onclick=\"triggerCheckAll()\">⚡ Kiểm Tra Ngay</button>\n" +
                "        <button class=\"btn\" id=\"btn-toggle-run\" onclick=\"toggleMonitoring()\">⏸ Tạm Dừng</button>\n" +
                "        <button class=\"btn btn-success\" onclick=\"openAddModal()\">➕ Thêm Mục Tiêu</button>\n" +
                "      </div>\n" +
                "    </header>\n" +
                "\n" +
                "    <!-- Real Network Topology Pipeline -->\n" +
                "    <div class=\"topology-container\">\n" +
                "      <div class=\"topology-title\">\n" +
                "        <span>🌐 Bản Đồ Kết Nối Mạng Thật (Live Topology Pipeline)</span>\n" +
                "        <span id=\"internet-badge\" class=\"node-badge badge-online\">INTERNET ONLINE</span>\n" +
                "      </div>\n" +
                "      <div class=\"topology-nodes\">\n" +
                "        <!-- Hop 1: Host Device -->\n" +
                "        <div class=\"node-card\">\n" +
                "          <div class=\"node-header\"><span class=\"node-type\">Card Mạng Máy Này</span><span class=\"node-badge badge-online\">HOẠT ĐỘNG</span></div>\n" +
                "          <div class=\"node-name\" id=\"topo-nic-name\">Đang nạp...</div>\n" +
                "          <div class=\"node-desc\">IP: <b id=\"topo-local-ip\">--</b></div>\n" +
                "          <div class=\"node-metric\"><span>MAC: <span id=\"topo-mac\">--</span></span><span id=\"topo-nic-type\">Wi-Fi</span></div>\n" +
                "        </div>\n" +
                "        <!-- Hop 2: Wi-Fi Router Gateway -->\n" +
                "        <div class=\"node-card\" id=\"card-gateway\">\n" +
                "          <div class=\"node-header\"><span class=\"node-type\">Bộ Định Tuyến (Router)</span><span id=\"topo-gw-badge\" class=\"node-badge badge-online\">KẾT NỐI</span></div>\n" +
                "          <div class=\"node-name\">Default Gateway</div>\n" +
                "          <div class=\"node-desc\">IP: <b id=\"topo-gw-ip\">--</b></div>\n" +
                "          <div class=\"node-metric\"><span>Độ trễ Router:</span><b id=\"topo-gw-latency\" style=\"color:var(--status-online)\">-- ms</b></div>\n" +
                "        </div>\n" +
                "        <!-- Hop 3: Public WAN & ISP -->\n" +
                "        <div class=\"node-card\">\n" +
                "          <div class=\"node-header\"><span class=\"node-type\">Cổng Internet Công Cộng</span><span class=\"node-badge badge-online\">WAN UPLINK</span></div>\n" +
                "          <div class=\"node-name\">Địa Chỉ Public IP</div>\n" +
                "          <div class=\"node-desc\">WAN: <b id=\"topo-public-ip\">--</b></div>\n" +
                "          <div class=\"node-metric\"><span>Subnet:</span><span id=\"topo-subnet\">255.255.255.0</span></div>\n" +
                "        </div>\n" +
                "        <!-- Hop 4: Core DNS & Cloud -->\n" +
                "        <div class=\"node-card\">\n" +
                "          <div class=\"node-header\"><span class=\"node-type\">Hạ Tầng Phân Giải DNS</span><span class=\"node-badge badge-online\">DNS READY</span></div>\n" +
                "          <div class=\"node-name\">DNS Phân Giải Tên Miền</div>\n" +
                "          <div class=\"node-desc\">Máy chủ: <b id=\"topo-dns-servers\">--</b></div>\n" +
                "          <div class=\"node-metric\"><span>Độ trễ DNS:</span><b id=\"topo-dns-latency\" style=\"color:var(--accent-blue)\">-- ms</b></div>\n" +
                "        </div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- Hierarchical Topology: Server -> Agent -> Target -->\n" +
                "    <div class=\"topology-container\" style=\"margin-bottom:24px;\">\n" +
                "      <div class=\"topology-title\">\n" +
                "        <span>🌳 Sơ Đồ Mạng Phân Tầng (Server → Agent → Target)</span>\n" +
                "        <button class=\"btn\" onclick=\"loadTopologyMap()\" style=\"padding:3px 10px; font-size:0.75rem;\">🔄 Làm Mới Sơ Đồ</button>\n" +
                "      </div>\n" +
                "      <div id=\"topo-svg-container\" style=\"width:100%; overflow-x:auto; background:rgba(15,23,42,0.4); border-radius:8px; padding:12px 6px;\">\n" +
                "        <svg id=\"hierarchical-topo-svg\" width=\"100%\" height=\"260\" style=\"min-width:720px;\"></svg>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- KPI Quick Metrics -->\n" +
                "    <div class=\"kpi-grid\">\n" +
                "      <div class=\"kpi-card\"><div class=\"kpi-label\">Tổng Máy Chủ</div><div class=\"kpi-num\" id=\"kpi-total\">--</div></div>\n" +
                "      <div class=\"kpi-card green\"><div class=\"kpi-label\">Trực Tuyến (Online)</div><div class=\"kpi-num\" id=\"kpi-online\" style=\"color:var(--status-online)\">--</div></div>\n" +
                "      <div class=\"kpi-card yellow\"><div class=\"kpi-label\">Cảnh Báo Độ Trễ</div><div class=\"kpi-num\" id=\"kpi-warning\" style=\"color:var(--status-warning)\">--</div></div>\n" +
                "      <div class=\"kpi-card red\"><div class=\"kpi-label\">Ngoại Tuyến (Offline)</div><div class=\"kpi-num\" id=\"kpi-offline\" style=\"color:var(--status-offline)\">--</div></div>\n" +
                "      <div class=\"kpi-card\"><div class=\"kpi-label\">Độ Trễ Trung Bình</div><div class=\"kpi-num\" id=\"kpi-latency\" style=\"color:var(--accent-blue)\">-- ms</div></div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- Content Grid -->\n" +
                "    <div class=\"content-grid\">\n" +
                "      <!-- Cột Trái: Danh sách giám sát & Công cụ chẩn đoán -->\n" +
                "      <div>\n" +
                "        <!-- Bảng danh sách máy chủ -->\n" +
                "        <div class=\"panel\">\n" +
                "          <div class=\"panel-header\">\n" +
                "            <div class=\"panel-title\"><span>🎯 Mục Tiêu Giám Sát</span></div>\n" +
                "            <div class=\"filter-tabs\">\n" +
                "              <button class=\"tab-btn active\" onclick=\"filterTargets('ALL', this)\">Tất cả</button>\n" +
                "              <button class=\"tab-btn\" onclick=\"filterTargets('REAL', this)\">Mạng thật</button>\n" +
                "              <button class=\"tab-btn\" onclick=\"filterTargets('MOCK', this)\">Giả lập</button>\n" +
                "              <button class=\"tab-btn\" onclick=\"filterTargets('ISSUES', this)\">Sự cố</button>\n" +
                "            </div>\n" +
                "          </div>\n" +
                "          <div class=\"table-responsive\">\n" +
                "            <table>\n" +
                "              <thead>\n" +
                "                <tr>\n" +
                "                  <th>Máy Chủ</th>\n" +
                "                  <th>Địa Chỉ</th>\n" +
                "                  <th>Giao Thức</th>\n" +
                "                  <th>Trạng Thái</th>\n" +
                "                  <th>Độ Trễ</th>\n" +
                "                  <th>Uptime</th>\n" +
                "                  <th>Thao Tác</th>\n" +
                "                </tr>\n" +
                "              </thead>\n" +
                "              <tbody id=\"targets-tbody\">\n" +
                "                <tr><td colspan=\"7\" style=\"text-align:center\">Đang kết nối dữ liệu mạng...</td></tr>\n" +
                "              </tbody>\n" +
                "            </table>\n" +
                "          </div>\n" +
                "        </div>\n" +
                "\n" +
                "        <!-- Công cụ chẩn đoán mạng thật -->\n" +
                "        <div class=\"panel\">\n" +
                "          <div class=\"tool-tabs\">\n" +
                "            <button class=\"tool-tab active\" onclick=\"switchToolTab('ping', this)\">⚡ Ping Chẩn Đoán</button>\n" +
                "            <button class=\"tool-tab\" onclick=\"switchToolTab('lan', this)\">🔍 Quét Thiết Bị LAN Thật</button>\n" +
                "            <button class=\"tool-tab\" onclick=\"switchToolTab('trace', this)\">🛣 Traceroute</button>\n" +
                "          </div>\n" +
                "\n" +
                "          <!-- Tab 1: Ping -->\n" +
                "          <div id=\"pane-ping\" class=\"tool-pane active\">\n" +
                "            <div class=\"input-group\">\n" +
                "              <input type=\"text\" id=\"ping-host\" class=\"input-text\" placeholder=\"Nhập IP / Tên miền (ví dụ: 8.8.8.8, 192.168.100.1, google.com)\" value=\"8.8.8.8\">\n" +
                "              <button class=\"btn btn-primary\" onclick=\"runPing()\">Gửi 4 gói Ping</button>\n" +
                "            </div>\n" +
                "            <div class=\"console-out\" id=\"ping-output\">Sẵn sàng thực hiện kiểm tra ping thời gian thực...</div>\n" +
                "          </div>\n" +
                "\n" +
                "          <!-- Tab 2: Quét LAN -->\n" +
                "          <div id=\"pane-lan\" class=\"tool-pane\">\n" +
                "            <div style=\"display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;\">\n" +
                "              <span style=\"font-size:0.85rem; color:var(--text-muted)\">Quét toàn bộ thiết bị đang trực tuyến trong cùng dải mạng Wi-Fi/Ethernet (/24):</span>\n" +
                "              <button class=\"btn btn-primary\" id=\"btn-scan-lan\" onclick=\"runLanScan()\">🔎 Bắt Đầu Quét Mạng LAN</button>\n" +
                "            </div>\n" +
                "            <div class=\"table-responsive\" style=\"max-height:220px; overflow-y:auto;\">\n" +
                "              <table>\n" +
                "                <thead>\n" +
                "                  <tr>\n" +
                "                    <th>Địa Chỉ IP</th>\n" +
                "                    <th>Địa Chỉ MAC</th>\n" +
                "                    <th>Tên Thiết Bị / Hostname</th>\n" +
                "                    <th>Loại Thiết Bị</th>\n" +
                "                    <th>Độ Trễ</th>\n" +
                "                    <th>Hành Động</th>\n" +
                "                  </tr>\n" +
                "                </thead>\n" +
                "                <tbody id=\"lan-devices-tbody\">\n" +
                "                  <tr><td colspan=\"6\" style=\"text-align:center\">Bấm nút \"Bắt Đầu Quét\" để dò tìm thiết bị thật trong mạng.</td></tr>\n" +
                "                </tbody>\n" +
                "              </table>\n" +
                "            </div>\n" +
                "          </div>\n" +
                "\n" +
                "          <!-- Tab 3: Traceroute -->\n" +
                "          <div id=\"pane-trace\" class=\"tool-pane\">\n" +
                "            <div class=\"input-group\">\n" +
                "              <input type=\"text\" id=\"trace-host\" class=\"input-text\" placeholder=\"Nhập IP / Domain cần dò đường\" value=\"8.8.8.8\">\n" +
                "              <button class=\"btn btn-primary\" onclick=\"runTraceroute()\">Bắt Đầu Trace</button>\n" +
                "            </div>\n" +
                "            <div class=\"console-out\" id=\"trace-output\">Sẵn sàng dò tìm các hop mạng trung gian...</div>\n" +
                "          </div>\n" +
                "        </div>\n" +
                "      </div>\n" +
                "\n" +
                "      <!-- Cột Phải: Biểu đồ thời gian thực & Lịch sử sự cố -->\n" +
                "      <div>\n" +
                "        <!-- Live Latency Chart -->\n" +
                "        <div class=\"panel\">\n" +
                "          <div class=\"panel-header\">\n" +
                "            <div class=\"panel-title\">📈 Biến Thiên Độ Trễ Thời Gian Thực (ms)</div>\n" +
                "            <span style=\"font-size:0.75rem; color:var(--text-muted)\">Live 3s</span>\n" +
                "          </div>\n" +
                "          <canvas id=\"latencyCanvas\" width=\"500\" height=\"200\"></canvas>\n" +
                "        </div>\n" +
                "\n" +
                "        <!-- Lịch sử sự cố gần nhất -->\n" +
                "        <div class=\"panel\">\n" +
                "          <div class=\"panel-header\">\n" +
                "            <div class=\"panel-title\">⚠️ Sự Cố Kết Nối Gần Nhất</div>\n" +
                "          </div>\n" +
                "          <div class=\"table-responsive\" style=\"max-height: 280px; overflow-y:auto;\">\n" +
                "            <table>\n" +
                "              <thead>\n" +
                "                <tr>\n" +
                "                  <th>Máy Chủ</th>\n" +
                "                  <th>Sự Cố</th>\n" +
                "                  <th>Thời Gian</th>\n" +
                "                  <th>Chi Tiết</th>\n" +
                "                </tr>\n" +
                "              </thead>\n" +
                "              <tbody id=\"incidents-tbody\">\n" +
                "                <tr><td colspan=\"4\" style=\"text-align:center\">Chưa ghi nhận sự cố nào.</td></tr>\n" +
                "              </tbody>\n" +
                "            </table>\n" +
                "          </div>\n" +
                "        </div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "\n" +
                "  <!-- Modal Thêm Máy Chủ Mới -->\n" +
                "  <div class=\"modal-overlay\" id=\"addModal\">\n" +
                "    <div class=\"modal-box\">\n" +
                "      <h3 style=\"font-size:1.15rem; margin-bottom:16px; color:#fff;\">➕ Thêm Mục Tiêu Giám Sát Mới</h3>\n" +
                "      <div class=\"form-group\">\n" +
                "        <label class=\"form-label\">Tên Máy Chủ / Thiết Bị:</label>\n" +
                "        <input type=\"text\" id=\"m-name\" class=\"input-text\" style=\"width:100%\" placeholder=\"Ví dụ: Camera Phòng Khách, VNPT DNS\">\n" +
                "      </div>\n" +
                "      <div class=\"form-group\">\n" +
                "        <label class=\"form-label\">Địa Chỉ Host / IP:</label>\n" +
                "        <input type=\"text\" id=\"m-host\" class=\"input-text\" style=\"width:100%\" placeholder=\"192.168.1.1 hoặc domain.com\">\n" +
                "      </div>\n" +
                "      <div style=\"display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:14px;\">\n" +
                "        <div>\n" +
                "          <label class=\"form-label\">Cổng (Port):</label>\n" +
                "          <input type=\"number\" id=\"m-port\" class=\"input-text\" style=\"width:100%\" value=\"80\">\n" +
                "        </div>\n" +
                "        <div>\n" +
                "          <label class=\"form-label\">Giao Thức:</label>\n" +
                "          <select id=\"m-protocol\" class=\"input-text\" style=\"width:100%\">\n" +
                "            <option value=\"ICMP\">ICMP (Ping)</option>\n" +
                "            <option value=\"TCP\" selected>TCP (Port Check)</option>\n" +
                "            <option value=\"HTTP\">HTTP Web</option>\n" +
                "            <option value=\"HTTPS\">HTTPS Secure</option>\n" +
                "            <option value=\"DNS\">DNS Query</option>\n" +
                "            <option value=\"UDP\">UDP Echo</option>\n" +
                "          </select>\n" +
                "        </div>\n" +
                "      </div>\n" +
                "      <div style=\"display:flex; justify-content:flex-end; gap:10px; margin-top:20px;\">\n" +
                "        <button class=\"btn\" onclick=\"closeAddModal()\">Hủy Bỏ</button>\n" +
                "        <button class=\"btn btn-primary\" onclick=\"submitAddTarget()\">Lưu Mục Tiêu</button>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "\n" +
                "  <!-- Client Logic JS -->\n" +
                "  <script>\n" +
                "    const API_TOKEN = \"" + escapeJson(this.apiToken != null ? this.apiToken : "") + "\";\n" +
                "    function authFetch(url, options = {}) {\n" +
                "      options.headers = options.headers || {};\n" +
                "      if (API_TOKEN) {\n" +
                "        if (options.headers instanceof Headers) {\n" +
                "          options.headers.set('Authorization', 'Bearer ' + API_TOKEN);\n" +
                "        } else {\n" +
                "          options.headers['Authorization'] = 'Bearer ' + API_TOKEN;\n" +
                "        }\n" +
                "      }\n" +
                "      return fetch(url, options);\n" +
                "    }\n" +
                "\n" +
                "    let rawServers = [];\n" +
                "    let currentFilter = 'ALL';\n" +
                "    const latencyHistory = [];\n" +
                "    const MAX_CHART_POINTS = 30;\n" +
                "\n" +
                "    // Đồng hồ thời gian thực\n" +
                "    function updateClock() {\n" +
                "      const now = new Date();\n" +
                "      document.getElementById('clock-live').textContent = now.toLocaleTimeString('vi-VN');\n" +
                "    }\n" +
                "    setInterval(updateClock, 1000);\n" +
                "    updateClock();\n" +
                "\n" +
                "    // Nạp thông tin Card Mạng Thật & Gateway\n" +
                "    async function loadNetworkInfo() {\n" +
                "      try {\n" +
                "        const res = await authFetch('/api/network-info');\n" +
                "        const n = await res.json();\n" +
                "        document.getElementById('topo-nic-name').textContent = n.interfaceName || 'Network Adapter';\n" +
                "        document.getElementById('topo-local-ip').textContent = n.localIp;\n" +
                "        document.getElementById('topo-mac').textContent = n.macAddress;\n" +
                "        document.getElementById('topo-nic-type').textContent = n.interfaceType;\n" +
                "        document.getElementById('topo-gw-ip').textContent = n.gatewayIp;\n" +
                "        document.getElementById('topo-gw-latency').textContent = (n.gatewayLatency >= 0 ? n.gatewayLatency + ' ms' : 'N/A');\n" +
                "        \n" +
                "        const gwBadge = document.getElementById('topo-gw-badge');\n" +
                "        gwBadge.textContent = n.gatewayStatus;\n" +
                "        gwBadge.className = 'node-badge badge-' + n.gatewayStatus.toLowerCase();\n" +
                "        \n" +
                "        document.getElementById('topo-public-ip').textContent = n.publicIp;\n" +
                "        document.getElementById('topo-subnet').textContent = n.subnetMask + ' (/' + n.prefix + ')';\n" +
                "        document.getElementById('topo-dns-servers').textContent = n.dnsServers ? n.dnsServers.join(', ') : '8.8.8.8';\n" +
                "        document.getElementById('topo-dns-latency').textContent = (n.dnsLatency >= 0 ? n.dnsLatency + ' ms' : 'N/A');\n" +
                "        \n" +
                "        const inetBadge = document.getElementById('internet-badge');\n" +
                "        inetBadge.textContent = n.internetStatus === 'CONNECTED' ? 'INTERNET ONLINE' : n.internetStatus;\n" +
                "        inetBadge.className = 'node-badge ' + (n.internetStatus === 'CONNECTED' ? 'badge-online' : 'badge-offline');\n" +
                "      } catch (e) { console.error('Lỗi nạp network info:', e); }\n" +
                "    }\n" +
                "\n" +
                "    // Nạp dữ liệu trạng thái các máy chủ\n" +
                "    async function loadStatus() {\n" +
                "      try {\n" +
                "        const res = await authFetch('/api/status');\n" +
                "        const data = await res.json();\n" +
                "        rawServers = data.servers || [];\n" +
                "        \n" +
                "        document.getElementById('kpi-total').textContent = data.total;\n" +
                "        document.getElementById('kpi-online').textContent = data.online;\n" +
                "        document.getElementById('kpi-warning').textContent = data.warning;\n" +
                "        document.getElementById('kpi-offline').textContent = data.offline;\n" +
                "        document.getElementById('kpi-latency').textContent = data.avgLatency + ' ms';\n" +
                "        \n" +
                "        const btnToggle = document.getElementById('btn-toggle-run');\n" +
                "        btnToggle.textContent = data.isRunning ? '⏸ Tạm Dừng' : '▶ Tiếp Tục';\n" +
                "        \n" +
                "        renderServerTable();\n" +
                "        pushLatencyPoint(data.avgLatency);\n" +
                "      } catch (e) { console.error('Lỗi nạp status:', e); }\n" +
                "    }\n" +
                "\n" +
                "    function filterTargets(filter, btn) {\n" +
                "      currentFilter = filter;\n" +
                "      document.querySelectorAll('.filter-tabs .tab-btn').forEach(b => b.classList.remove('active'));\n" +
                "      if (btn) btn.classList.add('active');\n" +
                "      renderServerTable();\n" +
                "    }\n" +
                "\n" +
                "    function renderServerTable() {\n" +
                "      let list = rawServers;\n" +
                "      if (currentFilter === 'REAL') {\n" +
                "        list = list.filter(s => !s.isMock);\n" +
                "      } else if (currentFilter === 'MOCK') {\n" +
                "        list = list.filter(s => s.isMock);\n" +
                "      } else if (currentFilter === 'ISSUES') {\n" +
                "        list = list.filter(s => s.status === 'OFFLINE' || s.status === 'WARNING');\n" +
                "      }\n" +
                "      \n" +
                "      const tbody = document.getElementById('targets-tbody');\n" +
                "      if (list.length === 0) {\n" +
                "        tbody.innerHTML = '<tr><td colspan=\"7\" style=\"text-align:center; padding:20px;\">Không có mục tiêu nào trong bộ lọc này.</td></tr>';\n" +
                "        return;\n" +
                "      }\n" +
                "      \n" +
                "      tbody.innerHTML = list.map(s => {\n" +
                "        const isOff = s.status === 'OFFLINE';\n" +
                "        const isWarn = s.status === 'WARNING';\n" +
                "        const pillClass = isOff ? 'badge-offline' : (isWarn ? 'badge-warning' : 'badge-online');\n" +
                "        const latColor = isOff ? 'var(--status-offline)' : (isWarn ? 'var(--status-warning)' : 'var(--status-online)');\n" +
                "        const mockTag = s.isMock ? '<span style=\"font-size:0.65rem; background:rgba(255,255,255,0.08); padding:2px 4px; border-radius:4px; margin-left:6px;\">MOCK</span>' : '';\n" +
                "        return `\n" +
                "          <tr>\n" +
                "            <td>\n" +
                "              <div class=\"target-title\">${escapeHtml(s.name)} ${mockTag}</div>\n" +
                "            </td>\n" +
                "            <td><span class=\"target-host\">${escapeHtml(s.host)}${s.port > 0 ? ':' + s.port : ''}</span></td>\n" +
                "            <td><span class=\"badge-proto\">${s.protocol}</span></td>\n" +
                "            <td><span class=\"node-badge ${pillClass}\">${s.status}</span></td>\n" +
                "            <td><b style=\"color:${latColor}\">${s.latency >= 0 ? s.latency + ' ms' : 'N/A'}</b></td>\n" +
                "            <td><b>${s.uptime.toFixed(1)}%</b></td>\n" +
                "            <td>\n" +
                "              <button class=\"btn\" style=\"padding:4px 8px; font-size:0.75rem;\" onclick=\"checkSingleTarget(${s.id})\">⚡ Đo lại</button>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "        `;\n" +
                "      }).join('');\n" +
                "    }\n" +
                "\n" +
                "    // Nạp lịch sử sự cố\n" +
                "    async function loadIncidents() {\n" +
                "      try {\n" +
                "        const res = await authFetch('/api/incidents');\n" +
                "        const list = await res.json();\n" +
                "        const tbody = document.getElementById('incidents-tbody');\n" +
                "        if (list.length === 0) {\n" +
                "          tbody.innerHTML = '<tr><td colspan=\"4\" style=\"text-align:center; padding:16px;\">Không có sự cố nào gần đây.</td></tr>';\n" +
                "          return;\n" +
                "        }\n" +
                "        tbody.innerHTML = list.map(i => `\n" +
                "          <tr>\n" +
                "            <td><b>${escapeHtml(i.target)}</b></td>\n" +
                "            <td><span class=\"node-badge badge-${i.event === 'OFFLINE' ? 'offline' : (i.event === 'RECOVERED' ? 'online' : 'warning')}\">${i.event}</span></td>\n" +
                "            <td style=\"font-size:0.75rem; color:var(--text-muted); font-family:monospace;\">${i.time}</td>\n" +
                "            <td style=\"font-size:0.8rem; color:#cbd5e1;\">${escapeHtml(i.msg)}</td>\n" +
                "          </tr>\n" +
                "        `).join('');\n" +
                "      } catch (e) { console.error('Lỗi incidents:', e); }\n" +
                "    }\n" +
                "\n" +
                "    // Thao tác điều khiển\n" +
                "    async function triggerCheckAll() {\n" +
                "      await authFetch('/api/check-now', { method: 'POST' });\n" +
                "      setTimeout(loadStatus, 400);\n" +
                "    }\n" +
                "    async function checkSingleTarget(id) {\n" +
                "      await authFetch('/api/check-now', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({id: id}) });\n" +
                "      setTimeout(loadStatus, 400);\n" +
                "    }\n" +
                "    async function toggleMonitoring() {\n" +
                "      await authFetch('/api/toggle-monitor', { method: 'POST' });\n" +
                "      loadStatus();\n" +
                "    }\n" +
                "\n" +
                "    // Công cụ chẩn đoán Ping\n" +
                "    async function runPing() {\n" +
                "      const host = document.getElementById('ping-host').value.trim();\n" +
                "      if (!host) return;\n" +
                "      const out = document.getElementById('ping-output');\n" +
                "      out.textContent = `Đang gửi gói tin ping tới ${host}...\\n`;\n" +
                "      try {\n" +
                "        const res = await authFetch('/api/tools/ping', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({host: host, count: 4}) });\n" +
                "        const d = await res.json();\n" +
                "        if (!d.success) { out.textContent = d.message; return; }\n" +
                "        let text = `--- Kết quả Ping ${d.host} ---\\n`;\n" +
                "        if (d.logs) text += d.logs.join('\\n') + '\\n';\n" +
                "        text += `\\nThống kê: Đã gửi = ${d.sent}, Nhận = ${d.received}, Mất = ${d.lost} (${d.lossPercent}% mất gói)\\n`;\n" +
                "        text += `Thời gian khứ hồi (ms): Min = ${d.minMs}ms, Avg = ${d.avgMs}ms, Max = ${d.maxMs}ms, Jitter = ${d.jitterMs}ms`;\n" +
                "        out.textContent = text;\n" +
                "      } catch (e) { out.textContent = 'Lỗi thực thi ping: ' + e; }\n" +
                "    }\n" +
                "\n" +
                "    // Công cụ Quét LAN thật\n" +
                "    async function runLanScan() {\n" +
                "      const btn = document.getElementById('btn-scan-lan');\n" +
                "      const tbody = document.getElementById('lan-devices-tbody');\n" +
                "      btn.disabled = true;\n" +
                "      btn.textContent = '⏳ Đang quét toàn bộ dải mạng...';\n" +
                "      tbody.innerHTML = '<tr><td colspan=\"6\" style=\"text-align:center; padding:16px;\">Đang quét dải subnet và phân tích bảng ARP thật... Vui lòng đợi vài giây.</td></tr>';\n" +
                "      try {\n" +
                "        const res = await authFetch('/api/tools/scan-lan', { method: 'POST' });\n" +
                "        const data = await res.json();\n" +
                "        if (!data.success || data.devices.length === 0) {\n" +
                "          tbody.innerHTML = '<tr><td colspan=\"6\" style=\"text-align:center; padding:16px;\">Không phát hiện thêm thiết bị phản hồi.</td></tr>';\n" +
                "        } else {\n" +
                "          tbody.innerHTML = data.devices.map(d => `\n" +
                "            <tr>\n" +
                "              <td><b style=\"color:var(--accent-blue)\">${d.ip}</b></td>\n" +
                "              <td style=\"font-family:monospace; font-size:0.75rem;\">${d.mac}</td>\n" +
                "              <td><b>${escapeHtml(d.hostname)}</b></td>\n" +
                "              <td><span class=\"node-badge ${d.isGateway ? 'badge-warning' : 'badge-online'}\">${d.deviceType}</span></td>\n" +
                "              <td>${d.latency >= 0 ? d.latency + ' ms' : '< 1 ms'}</td>\n" +
                "              <td>\n" +
                "                <button class=\"btn\" style=\"padding:3px 8px; font-size:0.7rem;\" onclick=\"quickAddFromLan('${d.ip}', '${escapeHtml(d.hostname)}')\">➕ Giám Sát</button>\n" +
                "              </td>\n" +
                "            </tr>\n" +
                "          `).join('');\n" +
                "        }\n" +
                "      } catch (e) { tbody.innerHTML = '<tr><td colspan=\"6\" style=\"color:red; text-align:center;\">Lỗi: ' + e + '</td></tr>'; }\n" +
                "      btn.disabled = false;\n" +
                "      btn.textContent = '🔎 Quét Lại Mạng LAN';\n" +
                "    }\n" +
                "\n" +
                "    function quickAddFromLan(ip, name) {\n" +
                "      document.getElementById('m-name').value = name || ('Thiết bị LAN ' + ip);\n" +
                "      document.getElementById('m-host').value = ip;\n" +
                "      document.getElementById('m-port').value = 80;\n" +
                "      document.getElementById('m-protocol').value = 'ICMP';\n" +
                "      openAddModal();\n" +
                "    }\n" +
                "\n" +
                "    // Công cụ Traceroute\n" +
                "    async function runTraceroute() {\n" +
                "      const host = document.getElementById('trace-host').value.trim();\n" +
                "      const out = document.getElementById('trace-output');\n" +
                "      out.textContent = `Đang dò tuyến đường tới ${host}...\\n`;\n" +
                "      try {\n" +
                "        const res = await authFetch('/api/tools/traceroute', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({host: host, maxHops: 8}) });\n" +
                "        const d = await res.json();\n" +
                "        if (d.hops && d.hops.length > 0) {\n" +
                "          out.textContent = d.hops.map(h => `Hop ${h.hop}: ${h.raw || h.ip}`).join('\\n');\n" +
                "        } else {\n" +
                "          out.textContent = 'Hoàn tất dò tuyến đường.\\n';\n" +
                "        }\n" +
                "      } catch (e) { out.textContent = 'Lỗi traceroute: ' + e; }\n" +
                "    }\n" +
                "\n" +
                "    function switchToolTab(tab, btn) {\n" +
                "      document.querySelectorAll('.tool-tab').forEach(b => b.classList.remove('active'));\n" +
                "      document.querySelectorAll('.tool-pane').forEach(p => p.classList.remove('active'));\n" +
                "      btn.classList.add('active');\n" +
                "      document.getElementById('pane-' + tab).classList.add('active');\n" +
                "    }\n" +
                "\n" +
                "    // Modal Thêm Mục Tiêu\n" +
                "    function openAddModal() { document.getElementById('addModal').style.display = 'flex'; }\n" +
                "    function closeAddModal() { document.getElementById('addModal').style.display = 'none'; }\n" +
                "    async function submitAddTarget() {\n" +
                "      const name = document.getElementById('m-name').value.trim();\n" +
                "      const host = document.getElementById('m-host').value.trim();\n" +
                "      const port = document.getElementById('m-port').value;\n" +
                "      const proto = document.getElementById('m-protocol').value;\n" +
                "      if (!name || !host) { alert('Vui lòng nhập đầy đủ tên và host!'); return; }\n" +
                "      await authFetch('/api/add-target', {\n" +
                "        method: 'POST',\n" +
                "        headers: {'Content-Type': 'application/json'},\n" +
                "        body: JSON.stringify({name: name, host: host, port: port, protocol: proto, interval: 5, timeout: 1500})\n" +
                "      });\n" +
                "      closeAddModal();\n" +
                "      setTimeout(loadStatus, 400);\n" +
                "    }\n" +
                "\n" +
                "    // Vẽ biểu đồ độ trễ Canvas Real-time\n" +
                "    function pushLatencyPoint(val) {\n" +
                "      latencyHistory.push(val);\n" +
                "      if (latencyHistory.length > MAX_CHART_POINTS) latencyHistory.shift();\n" +
                "      drawChart();\n" +
                "    }\n" +
                "    function drawChart() {\n" +
                "      const canvas = document.getElementById('latencyCanvas');\n" +
                "      if (!canvas) return;\n" +
                "      const ctx = canvas.getContext('2d');\n" +
                "      const w = canvas.width;\n" +
                "      const h = canvas.height;\n" +
                "      ctx.clearRect(0, 0, w, h);\n" +
                "      \n" +
                "      if (latencyHistory.length < 2) return;\n" +
                "      const maxVal = Math.max(100, ...latencyHistory) * 1.2;\n" +
                "      \n" +
                "      // Lưới mờ\n" +
                "      ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';\n" +
                "      ctx.lineWidth = 1;\n" +
                "      for (let y = 0; y < h; y += 40) {\n" +
                "        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();\n" +
                "      }\n" +
                "      \n" +
                "      // Đường biểu đồ gradient\n" +
                "      const stepX = w / (MAX_CHART_POINTS - 1);\n" +
                "      ctx.beginPath();\n" +
                "      for (let i = 0; i < latencyHistory.length; i++) {\n" +
                "        const x = i * stepX;\n" +
                "        const y = h - (latencyHistory[i] / maxVal) * (h - 20) - 10;\n" +
                "        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);\n" +
                "      }\n" +
                "      ctx.strokeStyle = '#38bdf8';\n" +
                "      ctx.lineWidth = 2.5;\n" +
                "      ctx.shadowColor = '#0284c7';\n" +
                "      ctx.shadowBlur = 10;\n" +
                "      ctx.stroke();\n" +
                "      ctx.shadowBlur = 0;\n" +
                "      \n" +
                "      // Điểm chấm cuối cùng\n" +
                "      const lastX = (latencyHistory.length - 1) * stepX;\n" +
                "      const lastY = h - (latencyHistory[latencyHistory.length - 1] / maxVal) * (h - 20) - 10;\n" +
                "      ctx.fillStyle = '#38bdf8';\n" +
                "      ctx.beginPath(); ctx.arc(lastX, lastY, 4, 0, Math.PI * 2); ctx.fill();\n" +
                "    }\n" +
                "\n" +
                "    function escapeHtml(s) {\n" +
                "      if (!s) return '';\n" +
                "      return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\"/g, '&quot;');\n" +
                "    }\n" +
                "\n" +
                "    async function loadTopologyMap() {\n" +
                "      try {\n" +
                "        const res = await authFetch('/api/topology');\n" +
                "        const data = await res.json();\n" +
                "        const svg = document.getElementById('hierarchical-topo-svg');\n" +
                "        if (!svg) return;\n" +
                "        \n" +
                "        const server = data.server || { name: 'NCAS Server', ip: '127.0.0.1', status: 'ONLINE' };\n" +
                "        const agents = data.agents || [];\n" +
                "        const targets = data.targets || [];\n" +
                "        \n" +
                "        const col1X = 40, col2X = 280, col3X = 540;\n" +
                "        const nodeW = 160, nodeH = 46;\n" +
                "        \n" +
                "        let targetCount = Math.max(1, targets.length);\n" +
                "        let svgH = Math.max(260, targetCount * 54 + 25);\n" +
                "        svg.setAttribute('height', svgH);\n" +
                "        \n" +
                "        const serverY = Math.max(35, (svgH / 2) - (nodeH / 2));\n" +
                "        \n" +
                "        let html = '<defs><filter id=\"dim\"><feColorMatrix type=\"matrix\" values=\"1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 0.4 0\"/></filter></defs>';\n" +
                "        \n" +
                "        const agentPositions = [];\n" +
                "        let agentCount = Math.max(1, agents.length);\n" +
                "        let agentSpacing = Math.max(65, (svgH - 60) / agentCount);\n" +
                "        for (let i = 0; i < agents.length; i++) {\n" +
                "          agentPositions.push({ x: col2X, y: 30 + i * agentSpacing, agent: agents[i] });\n" +
                "        }\n" +
                "        \n" +
                "        const targetPositions = [];\n" +
                "        let targetSpacing = Math.max(50, (svgH - 45) / targetCount);\n" +
                "        for (let i = 0; i < targets.length; i++) {\n" +
                "          targetPositions.push({ x: col3X, y: 22 + i * targetSpacing, target: targets[i] });\n" +
                "        }\n" +
                "        \n" +
                "        // Vẽ đường nối Server -> Agents\n" +
                "        for (let ap of agentPositions) {\n" +
                "          html += `<path d=\"M ${col1X + nodeW} ${serverY + nodeH/2} C ${col1X + nodeW + 40} ${serverY + nodeH/2}, ${ap.x - 40} ${ap.y + nodeH/2}, ${ap.x} ${ap.y + nodeH/2}\" stroke=\"#3b82f6\" stroke-width=\"1.8\" fill=\"none\" opacity=\"0.8\"/>`;\n" +
                "          html += `<text x=\"${(col1X + nodeW + ap.x)/2}\" y=\"${(serverY + ap.y)/2 + 8}\" fill=\"#94a3b8\" font-size=\"10\" text-anchor=\"middle\">TCP:9001</text>`;\n" +
                "        }\n" +
                "        \n" +
                "        // Vẽ đường nối Agent/Server -> Targets\n" +
                "        for (let tp of targetPositions) {\n" +
                "          let t = tp.target;\n" +
                "          let linkColor = t.status === 'ONLINE' ? '#10b981' : (t.status === 'WARNING' ? '#f59e0b' : (t.status === 'MAINTENANCE' ? '#3b82f6' : '#ef4444'));\n" +
                "          if (t.unreachable) linkColor = '#64748b';\n" +
                "          \n" +
                "          let fromX = col1X + nodeW, fromY = serverY + nodeH/2;\n" +
                "          if (t.scope === 'AGENT' && agentPositions.length > 0) {\n" +
                "            let matched = agentPositions.find(a => a.agent.id === t.station);\n" +
                "            if (matched) { fromX = matched.x + nodeW; fromY = matched.y + nodeH/2; }\n" +
                "          }\n" +
                "          html += `<path d=\"M ${fromX} ${fromY} C ${fromX + 40} ${fromY}, ${tp.x - 40} ${tp.y + nodeH/2}, ${tp.x} ${tp.y + nodeH/2}\" stroke=\"${linkColor}\" stroke-width=\"1.5\" fill=\"none\" opacity=\"${t.unreachable ? 0.35 : 0.85}\"/>`;\n" +
                "          if (t.latency >= 0) {\n" +
                "            html += `<text x=\"${(fromX + tp.x)/2}\" y=\"${(fromY + tp.y)/2 + 8}\" fill=\"#94a3b8\" font-size=\"9\" text-anchor=\"middle\">${t.latency}ms</text>`;\n" +
                "          }\n" +
                "        }\n" +
                "        \n" +
                "        function drawSvgNode(x, y, title, sub, status, isUnreach, isFlap) {\n" +
                "          let stColor = status === 'ONLINE' ? '#10b981' : (status === 'WARNING' ? '#f59e0b' : (status === 'MAINTENANCE' ? '#3b82f6' : '#ef4444'));\n" +
                "          let filterAttr = isUnreach ? 'filter=\"url(#dim)\"' : '';\n" +
                "          return `\n" +
                "            <g ${filterAttr}>\n" +
                "              <rect x=\"${x}\" y=\"${y}\" width=\"${nodeW}\" height=\"${nodeH}\" rx=\"8\" fill=\"#1e293b\" stroke=\"${stColor}\" stroke-width=\"1.2\"/>\n" +
                "              <rect x=\"${x+2}\" y=\"${y+3}\" width=\"4\" height=\"${nodeH-6}\" rx=\"2\" fill=\"${stColor}\"/>\n" +
                "              <text x=\"${x+14}\" y=\"${y+18}\" fill=\"#f8fafc\" font-size=\"12\" font-weight=\"bold\">${escapeHtml(title).slice(0, 16)}</text>\n" +
                "              ${isFlap ? `<text x=\"${x+nodeW-18}\" y=\"${y+18}\" fill=\"#f59e0b\" font-size=\"12\">⚡</text>` : ''}\n" +
                "              <text x=\"${x+14}\" y=\"${y+34}\" fill=\"#94a3b8\" font-size=\"10\">${escapeHtml(sub).slice(0, 20)}</text>\n" +
                "            </g>\n" +
                "          `;\n" +
                "        }\n" +
                "        \n" +
                "        html += drawSvgNode(col1X, serverY, server.name, server.ip, 'ONLINE', false, false);\n" +
                "        for (let ap of agentPositions) {\n" +
                "          html += drawSvgNode(ap.x, ap.y, ap.agent.id, ap.agent.ip, ap.agent.status, false, false);\n" +
                "        }\n" +
                "        if (agentPositions.length === 0) {\n" +
                "          html += drawSvgNode(col2X, serverY, 'Chưa có Agent', 'Chế độ Standalone', 'PAUSED', false, false);\n" +
                "        }\n" +
                "        for (let tp of targetPositions) {\n" +
                "          let t = tp.target;\n" +
                "          html += drawSvgNode(tp.x, tp.y, t.name, t.host + (t.port > 0 ? ':' + t.port : ''), t.status, t.unreachable, t.flapping);\n" +
                "        }\n" +
                "        \n" +
                "        svg.innerHTML = html;\n" +
                "      } catch (e) { console.error('Lỗi nạp topology:', e); }\n" +
                "    }\n" +
                "\n" +
                "    // Khởi chạy vòng lặp cập nhật tự động\n" +
                "    loadNetworkInfo();\n" +
                "    loadStatus();\n" +
                "    loadIncidents();\n" +
                "    loadTopologyMap();\n" +
                "    setInterval(loadStatus, 3000);\n" +
                "    setInterval(loadIncidents, 5000);\n" +
                "    setInterval(loadTopologyMap, 6000);\n" +
                "    setInterval(loadNetworkInfo, 10000);\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>";
    }
}

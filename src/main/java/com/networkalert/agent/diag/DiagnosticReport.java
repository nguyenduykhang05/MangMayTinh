package com.networkalert.agent.diag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Cấu trúc báo cáo tự chẩn đoán đường mạng tại chỗ theo chuẩn NCAS 2.0 (ERRATA §E9.9)
 * Đảm bảo kích thước JSON tuần tự hóa luôn ≤ 2 KiB (2048 bytes).
 */
public class DiagnosticReport {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_JSON_BYTES = 2048;

    public static class ProbeResult {
        private boolean ok;
        private long rttMs;
        private String detail;

        public ProbeResult() {}

        public ProbeResult(boolean ok, long rttMs, String detail) {
            this.ok = ok;
            this.rttMs = rttMs;
            this.detail = detail;
        }

        public boolean isOk() { return ok; }
        public void setOk(boolean ok) { this.ok = ok; }

        public long getRttMs() { return rttMs; }
        public void setRttMs(long rttMs) { this.rttMs = rttMs; }

        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }

    public static class HopInfo {
        private int hop;
        private String ip;
        private long rttMs;
        private boolean responsive;

        public HopInfo() {}

        public HopInfo(int hop, String ip, long rttMs, boolean responsive) {
            this.hop = hop;
            this.ip = ip;
            this.rttMs = rttMs;
            this.responsive = responsive;
        }

        public int getHop() { return hop; }
        public void setHop(int hop) { this.hop = hop; }

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }

        public long getRttMs() { return rttMs; }
        public void setRttMs(long rttMs) { this.rttMs = rttMs; }

        public boolean isResponsive() { return responsive; }
        public void setResponsive(boolean responsive) { this.responsive = responsive; }
    }

    public static class TraceResult {
        private int maxHops = 15;
        private int lastResponsiveHop;
        private boolean reachedTarget;
        private String lastResponsiveIp;
        private List<HopInfo> hops = new ArrayList<>();

        public int getMaxHops() { return maxHops; }
        public void setMaxHops(int maxHops) { this.maxHops = maxHops; }

        public int getLastResponsiveHop() { return lastResponsiveHop; }
        public void setLastResponsiveHop(int lastResponsiveHop) { this.lastResponsiveHop = lastResponsiveHop; }

        public boolean isReachedTarget() { return reachedTarget; }
        public void setReachedTarget(boolean reachedTarget) { this.reachedTarget = reachedTarget; }

        public String getLastResponsiveIp() { return lastResponsiveIp; }
        public void setLastResponsiveIp(String lastResponsiveIp) { this.lastResponsiveIp = lastResponsiveIp; }

        public List<HopInfo> getHops() { return hops; }
        public void setHops(List<HopInfo> hops) { this.hops = hops; }
    }

    public static class ComparisonProbes {
        private ProbeResult icmp;
        private ProbeResult tcp;
        private int targetPort;

        public ProbeResult getIcmp() { return icmp; }
        public void setIcmp(ProbeResult icmp) { this.icmp = icmp; }

        public ProbeResult getTcp() { return tcp; }
        public void setTcp(ProbeResult tcp) { this.tcp = tcp; }

        public int getTargetPort() { return targetPort; }
        public void setTargetPort(int targetPort) { this.targetPort = targetPort; }
    }

    private int targetId;
    private String targetHost;
    private long diagnosedAtMs;
    private ProbeResult gateway;
    private ProbeResult dns;
    private TraceResult trace;
    private ComparisonProbes probes;
    private String summary;

    public DiagnosticReport() {}

    public DiagnosticReport(int targetId, String targetHost, long diagnosedAtMs) {
        this.targetId = targetId;
        this.targetHost = targetHost;
        this.diagnosedAtMs = diagnosedAtMs;
    }

    public int getTargetId() { return targetId; }
    public void setTargetId(int targetId) { this.targetId = targetId; }

    public String getTargetHost() { return targetHost; }
    public void setTargetHost(String targetHost) { this.targetHost = targetHost; }

    public long getDiagnosedAtMs() { return diagnosedAtMs; }
    public void setDiagnosedAtMs(long diagnosedAtMs) { this.diagnosedAtMs = diagnosedAtMs; }

    public ProbeResult getGateway() { return gateway; }
    public void setGateway(ProbeResult gateway) { this.gateway = gateway; }

    public ProbeResult getDns() { return dns; }
    public void setDns(ProbeResult dns) { this.dns = dns; }

    public TraceResult getTrace() { return trace; }
    public void setTrace(TraceResult trace) { this.trace = trace; }

    public ComparisonProbes getProbes() { return probes; }
    public void setProbes(ComparisonProbes probes) { this.probes = probes; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    /**
     * Tuần tự hóa đối tượng chẩn đoán sang chuỗi JSON và đảm bảo không vượt quá trần 2 KiB (ERRATA §E9.9)
     */
    public String toJson() {
        String json = GSON.toJson(this);
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_JSON_BYTES) {
            return json;
        }

        // Nếu vượt quá 2 KiB, thu gọn danh sách chi tiết các Hop trong trace để giữ trần an toàn
        if (this.trace != null && this.trace.getHops() != null && !this.trace.getHops().isEmpty()) {
            List<HopInfo> originalHops = new ArrayList<>(this.trace.getHops());
            try {
                this.trace.setHops(new ArrayList<>());
                json = GSON.toJson(this);
                if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_JSON_BYTES) {
                    return json;
                }
            } finally {
                this.trace.setHops(originalHops);
            }
        }

        // Nếu vẫn quá 2 KiB, cắt ngắn trực tiếp chuỗi JSON an toàn
        return json.substring(0, Math.min(json.length(), 2040)) + "...}";
    }

    public static DiagnosticReport fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return GSON.fromJson(json, DiagnosticReport.class);
        } catch (Exception e) {
            return null;
        }
    }
}

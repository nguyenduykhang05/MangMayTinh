package com.networkalert.protocol.messages;

/**
 * Phân rã chi tiết thời gian đo kiểm giao thức mạng (DNS, TCP, TLS, TTFB)
 */
public class MetricBreakdownDto {
    private long dnsLookupMs = -1;
    private long tcpConnectMs = -1;
    private long tlsHandshakeMs = -1;
    private long ttfbMs = -1;

    public MetricBreakdownDto() {}

    public MetricBreakdownDto(long dnsLookupMs, long tcpConnectMs, long tlsHandshakeMs, long ttfbMs) {
        this.dnsLookupMs = dnsLookupMs;
        this.tcpConnectMs = tcpConnectMs;
        this.tlsHandshakeMs = tlsHandshakeMs;
        this.ttfbMs = ttfbMs;
    }

    public long getDnsLookupMs() { return dnsLookupMs; }
    public void setDnsLookupMs(long dnsLookupMs) { this.dnsLookupMs = dnsLookupMs; }

    public long getTcpConnectMs() { return tcpConnectMs; }
    public void setTcpConnectMs(long tcpConnectMs) { this.tcpConnectMs = tcpConnectMs; }

    public long getTlsHandshakeMs() { return tlsHandshakeMs; }
    public void setTlsHandshakeMs(long tlsHandshakeMs) { this.tlsHandshakeMs = tlsHandshakeMs; }

    public long getTtfbMs() { return ttfbMs; }
    public void setTtfbMs(long ttfbMs) { this.ttfbMs = ttfbMs; }

    @Override
    public String toString() {
        return String.format("[DNS:%dms, TCP:%dms, TLS:%dms, TTFB:%dms]", dnsLookupMs, tcpConnectMs, tlsHandshakeMs, ttfbMs);
    }
}

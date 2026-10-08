package com.networkalert.service.checker;

import com.networkalert.model.ProtocolType;

/**
 * Factory cung cấp bộ kiểm tra tương ứng với từng giao thức
 */
public class CheckerFactory {
    private static final TcpChecker TCP_CHECKER = new TcpChecker();
    private static final UdpChecker UDP_CHECKER = new UdpChecker();
    private static final HttpChecker HTTP_CHECKER = new HttpChecker();
    private static final PingChecker PING_CHECKER = new PingChecker();
    private static final DnsChecker DNS_CHECKER = new DnsChecker();
    private static final PushChecker PUSH_CHECKER = new PushChecker();

    public static IProtocolChecker getChecker(ProtocolType protocol) {
        if (protocol == null) return TCP_CHECKER;
        switch (protocol) {
            case TCP:
                return TCP_CHECKER;
            case UDP:
                return UDP_CHECKER;
            case HTTP:
            case HTTPS:
                return HTTP_CHECKER;
            case ICMP:
                return PING_CHECKER;
            case DNS:
                return DNS_CHECKER;
            case PUSH:
                return PUSH_CHECKER;
            default:
                return TCP_CHECKER;
        }
    }
}

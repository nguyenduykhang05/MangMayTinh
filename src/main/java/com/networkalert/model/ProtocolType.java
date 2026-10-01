package com.networkalert.model;

/**
 * Các giao thức mạng được hỗ trợ kiểm tra và giám sát
 */
public enum ProtocolType {
    ICMP("ICMP (Ping)", "Tầng Mạng (Network Layer)"),
    TCP("TCP Port", "Tầng Vận Chuyển (Transport Layer)"),
    UDP("UDP Datagram", "Tầng Vận Chuyển (Transport Layer)"),
    HTTP("HTTP Web", "Tầng Ứng Dụng (Application Layer)"),
    HTTPS("HTTPS Secure", "Tầng Ứng Dụng (Application Layer)"),
    DNS("DNS Query", "Tầng Ứng Dụng (Application Layer)"),
    PUSH("Push Monitor", "Nhịp Tim Đẩy (Push/Heartbeat)");

    private final String displayName;
    private final String layerDescription;

    ProtocolType(String displayName, String layerDescription) {
        this.displayName = displayName;
        this.layerDescription = layerDescription;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLayerDescription() {
        return layerDescription;
    }

    public static ProtocolType fromString(String text) {
        if (text == null) return TCP;
        for (ProtocolType p : values()) {
            if (p.name().equalsIgnoreCase(text.trim()) || p.displayName.equalsIgnoreCase(text.trim())) {
                return p;
            }
        }
        return TCP;
    }
}

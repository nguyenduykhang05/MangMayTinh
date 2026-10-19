package com.networkalert.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cấu trúc chính sách an ninh cục bộ của Agent định nghĩa trong {@code data/agent-policy.json} (ERRATA §E2.6).
 */
public class AgentPolicy {
    private int minIntervalSeconds = 1;
    private int maxTargets = 100;
    private List<String> disallowedCidrs = new ArrayList<>(Arrays.asList("169.254.169.254/32", "169.254.0.0/16"));
    private List<String> allowedProtocols = new ArrayList<>(Arrays.asList("ICMP", "TCP", "HTTP", "HTTPS", "DNS", "UDP"));

    public AgentPolicy() {}

    public AgentPolicy(int minIntervalSeconds, int maxTargets, List<String> disallowedCidrs, List<String> allowedProtocols) {
        this.minIntervalSeconds = minIntervalSeconds;
        this.maxTargets = maxTargets;
        this.disallowedCidrs = disallowedCidrs != null ? new ArrayList<>(disallowedCidrs) : new ArrayList<>();
        this.allowedProtocols = allowedProtocols != null ? new ArrayList<>(allowedProtocols) : new ArrayList<>();
    }

    public int getMinIntervalSeconds() { return minIntervalSeconds; }
    public void setMinIntervalSeconds(int minIntervalSeconds) { this.minIntervalSeconds = minIntervalSeconds; }

    public int getMaxTargets() { return maxTargets; }
    public void setMaxTargets(int maxTargets) { this.maxTargets = maxTargets; }

    public List<String> getDisallowedCidrs() { return disallowedCidrs; }
    public void setDisallowedCidrs(List<String> disallowedCidrs) { this.disallowedCidrs = disallowedCidrs; }

    public List<String> getAllowedProtocols() { return allowedProtocols; }
    public void setAllowedProtocols(List<String> allowedProtocols) { this.allowedProtocols = allowedProtocols; }
}

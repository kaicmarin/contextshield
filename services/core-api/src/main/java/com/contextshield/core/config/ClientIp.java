package com.contextshield.core.config;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.List;

/** IP do cliente para limitação de taxa. X-Forwarded-For só quando o peer é proxy confiável. */
public final class ClientIp {
    private ClientIp() {}

    public static String resolve(HttpServletRequest req, List<String> trustedProxies) {
        String remote = req.getRemoteAddr() == null ? "" : req.getRemoteAddr();
        if (trustedProxies != null && !trustedProxies.isEmpty() && trusted(remote, trustedProxies)) {
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                String first = fwd.split(",")[0].trim();
                if (!first.isEmpty()) return first;
            }
        }
        return remote.isBlank() ? "unknown" : remote;
    }

    static boolean trusted(String remote, List<String> trustedProxies) {
        if ("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)) return true;
        for (String spec : trustedProxies) {
            if (spec == null || spec.isBlank()) continue;
            if (spec.contains("/")) {
                if (inCidr(remote, spec)) return true;
            } else if (remote.equals(spec.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean inCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;
            byte[] network = InetAddress.getByName(parts[0].trim()).getAddress();
            byte[] address = InetAddress.getByName(ip).getAddress();
            if (network.length != address.length) return false;
            int prefix = Integer.parseInt(parts[1].trim());
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != address[i]) return false;
            }
            if (remBits == 0) return true;
            int mask = 0xFF << (8 - remBits);
            return (network[fullBytes] & mask) == (address[fullBytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }
}
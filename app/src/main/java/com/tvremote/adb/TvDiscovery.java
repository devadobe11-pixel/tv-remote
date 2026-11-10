package com.tvremote.adb;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds the TV's ADB TCP port (5555) on the active phone hotspot or local Wi-Fi subnet.
 * Tries the saved IP and previously seen host octet first before sweeping the /24 subnet.
 */
public final class TvDiscovery {
    private static final int PROBE_TIMEOUT_MS = 280;

    private TvDiscovery() {}

    public static String findTvIp(String savedIp, int savedLastOctet) {
        if (savedIp != null && !savedIp.isEmpty() && isAdbPortOpen(savedIp)) {
            return savedIp;
        }

        List<LocalSubnet> subnets = activeIpv4Subnets();
        // Fast path: Android hotspots often rotate the /24 prefix while keeping the TV's host octet.
        if (savedLastOctet > 0 && savedLastOctet < 255) {
            for (LocalSubnet sub : subnets) {
                String candidate = sub.prefix + "." + savedLastOctet;
                if (!candidate.equals(sub.selfIp) && !candidate.equals(savedIp) && isAdbPortOpen(candidate)) {
                    return candidate;
                }
            }
        }

        // Full /24 sweep on active local subnets (hotspot interfaces ordered first).
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            for (LocalSubnet sub : subnets) {
                List<Callable<String>> tasks = new ArrayList<>(254);
                for (int host = 1; host <= 254; host++) {
                    final String ip = sub.prefix + "." + host;
                    if (ip.equals(sub.selfIp)) continue;
                    tasks.add(() -> isAdbPortOpen(ip) ? ip : null);
                }
                try {
                    List<Future<String>> results = pool.invokeAll(tasks);
                    for (Future<String> f : results) {
                        try {
                            String hit = f.get();
                            if (hit != null) return hit;
                        } catch (Exception ignored) {
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return null;
    }

    public static boolean isAdbPortOpen(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, AdbClient.DEFAULT_PORT), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static int parseLastOctet(String ip) {
        if (ip == null) return -1;
        int dot = ip.lastIndexOf('.');
        if (dot < 0 || dot == ip.length() - 1) return -1;
        try {
            int val = Integer.parseInt(ip.substring(dot + 1).trim());
            return (val > 0 && val < 255) ? val : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<LocalSubnet> activeIpv4Subnets() {
        List<LocalSubnet> hotspotFirst = new ArrayList<>();
        List<LocalSubnet> others = new ArrayList<>();
        Set<String> seenPrefixes = new LinkedHashSet<>();

        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String name = nif.getName().toLowerCase();
                // Skip cellular carrier data interfaces (rmnet/ccmni/pdp).
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("tun")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress() || !addr.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    int lastDot = ip.lastIndexOf('.');
                    if (lastDot <= 0) continue;
                    String prefix = ip.substring(0, lastDot);
                    if (!seenPrefixes.add(prefix)) continue;

                    LocalSubnet sub = new LocalSubnet(prefix, ip);
                    if (name.contains("ap") || name.contains("swlan") || name.contains("wlan1") || name.contains("wlan2")) {
                        hotspotFirst.add(sub);
                    } else {
                        others.add(sub);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        hotspotFirst.addAll(others);
        return hotspotFirst;
    }

    private static final class LocalSubnet {
        final String prefix;
        final String selfIp;

        LocalSubnet(String prefix, String selfIp) {
            this.prefix = prefix;
            this.selfIp = selfIp;
        }
    }
}

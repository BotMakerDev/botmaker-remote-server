package com.botmaker.remote;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The address {@code --lan} binds: the one this machine reaches its local network from.
 *
 * <p>An explicit choice, printed with a warning every time the server starts: on the local network the token
 * is the only lock, and anyone on that Wi-Fi can try it. It is still one address, never {@code 0.0.0.0}, and
 * never a public tunnel — a shell on this machine is not put on the internet.
 *
 * <p>Read from {@code ip -4 route get 1.1.1.1} (the source address of the default route) and, when there is
 * no default route (a network with no internet), from the first private address on a physical interface.
 */
public final class Lan {

    private static final Pattern SRC = Pattern.compile("\\bsrc\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern DEV = Pattern.compile("\\bdev\\s+(\\S+)");
    private static final Pattern ADDR = Pattern.compile("^\\d+:\\s+(\\S+)\\s+inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)/",
            Pattern.MULTILINE);

    /** Interfaces that are not the local network: tunnels, containers, virtual machines, Android containers. */
    private static final List<String> VIRTUAL = List.of("lo", "tailscale", "docker", "br-", "veth", "virbr",
            "tun", "wg", "podman", "waydroid", "lxc", "vboxnet", "vmnet", "cni", "flannel");

    private Lan() {
    }

    /** This machine's local network IPv4, or empty. */
    public static Optional<String> address() {
        Proc route = Proc.run(Duration.ofSeconds(5), "ip", "-4", "route", "get", "1.1.1.1");
        Optional<String> viaRoute = route.ok() ? parseRoute(route.out()) : Optional.empty();
        if (viaRoute.isPresent()) return viaRoute;
        Proc addrs = Proc.run(Duration.ofSeconds(5), "ip", "-4", "-o", "addr", "show");
        return addrs.ok() ? parseAddresses(addrs.out()) : Optional.empty();
    }

    /** The {@code src} of a route, unless it leaves through a virtual interface (an exit node, a VPN). */
    static Optional<String> parseRoute(String routeOutput) {
        Matcher src = SRC.matcher(routeOutput);
        Matcher dev = DEV.matcher(routeOutput);
        if (!src.find() || !dev.find() || isVirtual(dev.group(1))) return Optional.empty();
        return isPrivate(src.group(1)) ? Optional.of(src.group(1)) : Optional.empty();
    }

    /** The first private address on a physical interface, from {@code ip -4 -o addr show}. */
    static Optional<String> parseAddresses(String addrOutput) {
        Matcher m = ADDR.matcher(addrOutput);
        while (m.find()) {
            if (!isVirtual(m.group(1)) && isPrivate(m.group(2))) return Optional.of(m.group(2));
        }
        return Optional.empty();
    }

    static boolean isVirtual(String nic) {
        return VIRTUAL.stream().anyMatch(nic::startsWith);
    }

    /** RFC 1918: the only ranges a home or office network hands a laptop. */
    static boolean isPrivate(String ipv4) {
        String[] p = ipv4.split("\\.");
        int a = Integer.parseInt(p[0]);
        int b = Integer.parseInt(p[1]);
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }
}

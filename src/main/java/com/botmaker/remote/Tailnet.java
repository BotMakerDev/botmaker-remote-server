package com.botmaker.remote;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The address this server binds: the machine's Tailscale IPv4, and nothing else.
 *
 * <p><b>This is the security rule, in one place.</b> Whatever is served here is a shell on this machine,
 * guarded by one token. Bound to the tailnet, the token is a second lock behind WireGuard and the tailnet's
 * own ACLs; bound to {@code 0.0.0.0}, it would be the only lock, on every network the laptop joins. So the
 * default refuses to start without a {@code tailscale0} address, and {@code --bind} is the explicit
 * override an operator types knowing what it means.
 */
public final class Tailnet {

    private static final Pattern INET = Pattern.compile("\\binet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)/");

    private Tailnet() {
    }

    /** The IPv4 on {@code tailscale0}, read from {@code ip -4 -o addr show tailscale0}. */
    public static Optional<String> address() {
        Proc shown = Proc.run(Duration.ofSeconds(5), "ip", "-4", "-o", "addr", "show", "tailscale0");
        return shown.ok() ? parse(shown.out()) : Optional.empty();
    }

    static Optional<String> parse(String ipOutput) {
        Matcher m = INET.matcher(ipOutput);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}

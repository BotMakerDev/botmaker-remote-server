package com.botmaker.remote;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who is already listening on a port — the sentence a failed bind owes its reader.
 *
 * <p>Jetty answers a taken port with {@code java.net.BindException: Address already in use} and a stack
 * trace through three frameworks, which says neither which port nor which program. The usual case here is
 * the one that is hardest to guess from that: {@code botmaker-remote.service}, started by systemd at login
 * and serving the very URL the operator is trying to print a QR code for.
 */
public final class Ports {

    /** {@code users:(("java",pid=247245,fd=12))} — the first program in the tuple is the listener. */
    private static final Pattern USER = Pattern.compile("users:\\(\\(\"([^\"]+)\",pid=(\\d+)");

    private Ports() {
    }

    /** {@code java (pid 247245)} for whoever holds {@code port}, or empty — including when {@code ss} is absent. */
    public static Optional<String> holder(int port) {
        Proc listening = Proc.run(Duration.ofSeconds(5), "ss", "-ltnp");
        return listening.ok() ? parse(listening.out(), port) : Optional.empty();
    }

    /**
     * The listener on {@code port} in {@code ss -ltnp} output.
     *
     * <p>The local address is the fourth column and ends in {@code :<port>}; matching the whole line would
     * find a peer address or a queue length that happens to be the same number. A socket owned by another
     * user prints no {@code users:} tuple at all — then the port is known to be taken and the program is
     * not, which is still worth saying.
     */
    static Optional<String> parse(String ssOutput, int port) {
        for (String line : ssOutput.split("\n")) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length < 4 || !columns[3].endsWith(":" + port)) {
                continue;
            }
            Matcher user = USER.matcher(line);
            return Optional.of(user.find()
                    ? user.group(1) + " (pid " + user.group(2) + ")"
                    : "another user's process");
        }
        return Optional.empty();
    }
}

package com.botmaker.remote;

import io.javalin.Javalin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code java -jar botmaker-remote-server-all.jar [--pair] [--port 7788] [--bind <ip>] [--token-file <path>]
 * [--ntfy <topic url>] [--big-qr] [--quiet]}
 *
 * <p>Starts, prints the pairing URL and its QR code, serves until killed. Meant to run as a systemd user
 * unit ({@code tools/botmaker-remote.service}); started by hand it prints the same and the QR is what the
 * phone scans.
 *
 * <p><b>Binds the Tailscale address and refuses to start without one</b> — see {@link Tailnet}. {@code --bind}
 * is the override, and it is deliberately a full address rather than a flag: typing {@code 0.0.0.0} is a
 * decision, and the decision should be typed.
 *
 * <p><b>{@code --pair} prints the pairing block and binds nothing</b> (2026-09-17). The QR is a function of
 * the address, the port and the token file — never of a socket — so the one question a running server makes
 * impossible to ask is the one it is least able to refuse. The unit runs {@code --quiet}, which suppresses
 * the QR block and nothing else: the {@code pair:} URL stays one line in the journal, so
 * {@code journalctl --user -u botmaker-remote} is the second way to pair again.
 */
public final class RemoteServer {

    static final int DEFAULT_PORT = 7788;

    /** Where the token lives: the XDG config dir, under this program's own name. */
    static Path defaultTokenFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg == null || xdg.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        return config.resolve("botmaker").resolve("remote").resolve("token");
    }

    /**
     * {@code bigQr}: one row per module, for consoles that pad lines (IDE run windows).
     *
     * @param pair print the pairing block for what is already running and bind nothing
     */
    record Options(int port, Optional<String> bind, Path tokenFile, Optional<URI> ntfy, boolean bigQr,
                   boolean quiet, boolean pair) {

        static Options parse(String[] args) {
            int port = DEFAULT_PORT;
            Optional<String> bind = Optional.empty();
            Path tokenFile = defaultTokenFile();
            Optional<URI> ntfy = Optional.empty();
            boolean bigQr = false;
            boolean quiet = false;
            boolean pair = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--bind" -> bind = Optional.of(args[++i]);
                    case "--token-file" -> tokenFile = Path.of(args[++i]);
                    case "--ntfy" -> ntfy = Optional.of(URI.create(args[++i]));
                    case "--big-qr" -> bigQr = true;
                    case "--quiet" -> quiet = true;
                    case "--pair" -> pair = true;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            return new Options(port, bind, tokenFile, ntfy, bigQr, quiet, pair);
        }
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (RuntimeException e) {
            System.err.println("botmaker-remote-server: " + e.getMessage());
            System.err.println("usage: --pair  --port N  --bind IP  --token-file PATH  --ntfy URL"
                    + "  --big-qr  --quiet");
            System.exit(2);
            return;
        }

        String host = options.bind().or(Tailnet::address).orElse(null);
        if (host == null) {
            System.err.println("No tailscale0 address found and no --bind given. This server hands out a "
                    + "shell; it binds the tailnet or nothing.");
            Tailnet.reported().ifPresent(ip -> System.err.println("tailscale reports " + ip + " but tailscale0 "
                    + "carries no address: tailscaled lost its interface configuration (often after starting "
                    + "offline). Run: sudo systemctl restart tailscaled"));
            System.exit(1);
            return;
        }
        // Before the tmux check: printing the code for a server that is already serving needs nothing but
        // the token file, and refusing to print it because this process cannot see tmux would be absurd.
        if (options.pair()) {
            pairing(options, host, Token.load(options.tokenFile()));
            return;
        }

        if (Proc.run(java.time.Duration.ofSeconds(5), "tmux", "-V").exit() == -1) {
            System.err.println("tmux is not on PATH; nothing here works without it.");
            System.exit(1);
            return;
        }

        Token token = Token.load(options.tokenFile());
        Activity activity = new Activity();
        Ntfy ntfy = new Ntfy(options.ntfy());
        Routes routes = new Routes(token, activity, ntfy, version());

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jetty.modifyWebSocketServletFactory(factory ->
                    factory.setIdleTimeout(java.time.Duration.ofHours(12)));
        });
        routes.install(app);
        try {
            app.start(host, options.port());
        } catch (RuntimeException e) {
            taken(options, host, e);
            System.exit(1);
            return;
        }

        pairing(options, host, token);
        if (!Cswap.available()) System.out.println("note: cswap is not on PATH — new sessions cannot pick an account");
        if (!ntfy.configured()) System.out.println("note: no --ntfy topic — no background notifications");
    }

    /**
     * The pairing block: the QR, the URL, the token file. Printed by a server that has just started and by
     * {@code --pair} for one that started hours ago — the same three lines, because they describe the same
     * pairing.
     *
     * <p>{@code --quiet} drops the QR block and keeps the {@code pair:} line: the unit runs quiet so the
     * journal is readable, and a journal without the URL would leave no way to pair at all.
     */
    private static void pairing(Options options, String host, Token token) {
        String url = "http://" + host + ":" + options.port() + "/?token=" + token.value();
        if (!options.quiet()) {
            System.out.println();
            System.out.println(Qr.render(url, options.bigQr()));
        }
        System.out.println("botmaker-remote-server " + version() + " on " + host + ":" + options.port());
        System.out.println("pair: " + url);
        System.out.println("token file: " + options.tokenFile());
    }

    /**
     * What a refused bind says instead of Jetty's stack trace: the address, who holds it, and the three ways
     * out — stop the service, pick another port, or ask for nothing but the code.
     */
    private static void taken(Options options, String host, RuntimeException failure) {
        String where = host + ":" + options.port();
        System.err.println("Could not bind " + where + ": " + rootCause(failure).getMessage());
        Ports.holder(options.port()).ifPresent(who -> System.err.println("Held by " + who + "."));
        System.err.println("  systemctl --user stop botmaker-remote.service   # if that is the packaged server");
        System.err.println("  --port N                                        # serve somewhere else");
        System.err.println("  --pair                                          # just print the pairing QR"
                + " of what is already running");
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    static String version() {
        String v = RemoteServer.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}

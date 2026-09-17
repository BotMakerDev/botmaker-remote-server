package com.botmaker.remote;

import io.javalin.Javalin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code java -jar botmaker-remote-server-all.jar [--port 7788] [--bind <ip>] [--token-file <path>]
 * [--ntfy <topic url>] [--quiet]}
 *
 * <p>Starts, prints the pairing URL and its QR code, serves until killed. Meant to run as a systemd user
 * unit ({@code tools/botmaker-remote.service}); started by hand it prints the same and the QR is what the
 * phone scans.
 *
 * <p><b>Binds the Tailscale address and refuses to start without one</b> — see {@link Tailnet}. {@code --bind}
 * is the override, and it is deliberately a full address rather than a flag: typing {@code 0.0.0.0} is a
 * decision, and the decision should be typed.
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

    record Options(int port, Optional<String> bind, Path tokenFile, Optional<URI> ntfy, boolean quiet) {

        static Options parse(String[] args) {
            int port = DEFAULT_PORT;
            Optional<String> bind = Optional.empty();
            Path tokenFile = defaultTokenFile();
            Optional<URI> ntfy = Optional.empty();
            boolean quiet = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--bind" -> bind = Optional.of(args[++i]);
                    case "--token-file" -> tokenFile = Path.of(args[++i]);
                    case "--ntfy" -> ntfy = Optional.of(URI.create(args[++i]));
                    case "--quiet" -> quiet = true;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            return new Options(port, bind, tokenFile, ntfy, quiet);
        }
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (RuntimeException e) {
            System.err.println("botmaker-remote-server: " + e.getMessage());
            System.err.println("usage: --port N  --bind IP  --token-file PATH  --ntfy URL  --quiet");
            System.exit(2);
            return;
        }

        String host = options.bind().or(Tailnet::address).orElse(null);
        if (host == null) {
            System.err.println("No tailscale0 address found and no --bind given. This server hands out a "
                    + "shell; it binds the tailnet or nothing.");
            System.exit(1);
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
        app.start(host, options.port());

        String url = "http://" + host + ":" + options.port() + "/?token=" + token.value();
        if (!options.quiet()) {
            System.out.println();
            System.out.println(Qr.render(url));
        }
        System.out.println("botmaker-remote-server " + version() + " on " + host + ":" + options.port());
        System.out.println("pair: " + url);
        System.out.println("token file: " + options.tokenFile());
        if (!Cswap.available()) System.out.println("note: cswap is not on PATH — new sessions cannot pick an account");
        if (!ntfy.configured()) System.out.println("note: no --ntfy topic — no background notifications");
    }

    static String version() {
        String v = RemoteServer.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}

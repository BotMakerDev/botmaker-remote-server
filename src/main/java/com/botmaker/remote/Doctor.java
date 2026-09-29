package com.botmaker.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code --doctor}: why a phone cannot reach this server, answered from this machine.
 *
 * <p>Written the week the phone had been offline in the tailnet for nine days while the server looked fine: the
 * server's side was up, and the one fact that mattered — the phone's last-seen date — was only in
 * {@code tailscale status}. So this prints the chain in order: tailscaled, this node's address, each peer with
 * whether it is online and when it was last seen (phones first), the port, tmux, the token file. It binds
 * nothing and changes nothing.
 */
final class Doctor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One tailnet peer as {@code tailscale status --json} describes it. */
    record Peer(String name, String os, boolean online, Optional<Instant> lastSeen) {
        boolean phone() {
            return os.equals("android") || os.equals("ios");
        }
    }

    private Doctor() {
    }

    static int run(RemoteServer.Options options) {
        List<String> problems = new ArrayList<>();
        Proc status = Proc.run(Duration.ofSeconds(8), "tailscale", "status", "--json");
        if (status.exit() == -1 && status.out().contains("tailscale:")) {
            line(false, "tailscale CLI", "not installed");
            problems.add("Install Tailscale on this computer.");
        } else {
            JsonNode tree = parse(status.out());
            String backend = tree.path("BackendState").asText("unknown");
            boolean running = backend.equals("Running");
            line(running, "tailscaled", backend);
            if (!running) problems.add("Start Tailscale here: tailscale up (or sudo systemctl start tailscaled).");

            Optional<String> bound = Tailnet.address();
            Optional<String> reported = Tailnet.reported();
            line(bound.isPresent(), "tailscale0 address", bound.orElse("none")
                    + reported.filter(r -> bound.isEmpty()).map(r -> " (tailscale reports " + r + ")").orElse(""));
            if (bound.isEmpty() && reported.isPresent()) {
                problems.add("tailscaled lost its interface: sudo systemctl restart tailscaled");
            }

            List<Peer> peers = peers(tree);
            if (peers.isEmpty()) {
                line(false, "peers", "none — sign the phone in to this tailnet");
                problems.add("Sign the phone's Tailscale app in to the same account.");
            }
            Instant now = Instant.now();
            for (Peer peer : peers) {
                line(peer.online(), (peer.phone() ? "phone " : "peer ") + peer.name() + " (" + peer.os() + ")",
                        peer.online() ? "online" : "offline, last seen " + ago(peer.lastSeen(), now));
            }
            if (peers.stream().anyMatch(Peer::phone) && peers.stream().filter(Peer::phone).noneMatch(Peer::online)) {
                problems.add("The phone is offline in Tailscale. On it: open Tailscale and connect; turn on "
                        + "Settings ▸ Network ▸ VPN ▸ Tailscale ▸ Always-on VPN; set Tailscale's battery use to "
                        + "Unrestricted so Android does not stop it.");
            }
        }

        Optional<String> holder = Ports.holder(options.port());
        line(holder.isPresent(), "port " + options.port(), holder.map(h -> "served by " + h).orElse("nothing listening"));
        if (holder.isEmpty()) problems.add("Start the server: systemctl --user start botmaker-remote.service");

        boolean tmux = Proc.run(Duration.ofSeconds(5), "tmux", "-V").ok();
        line(tmux, "tmux", tmux ? "on PATH" : "missing");
        if (!tmux) problems.add("Install tmux.");

        boolean token = Files.isRegularFile(options.tokenFile());
        line(token, "token file", options.tokenFile() + (token ? "" : " (created on first start)"));

        System.out.println();
        if (problems.isEmpty()) {
            System.out.println("Nothing wrong on this side. If the phone still cannot connect, pair again: --pair");
            return 0;
        }
        System.out.println("To do:");
        problems.forEach(p -> System.out.println("  - " + p));
        return 1;
    }

    /** The peers in a {@code tailscale status --json} tree, phones first, then by name. */
    static List<Peer> peers(JsonNode status) {
        List<Peer> peers = new ArrayList<>();
        status.path("Peer").forEach(p -> peers.add(new Peer(
                p.path("HostName").asText("?"),
                p.path("OS").asText("").toLowerCase(Locale.ROOT),
                p.path("Online").asBoolean(false),
                instant(p.path("LastSeen").asText(null)))));
        peers.sort((a, b) -> a.phone() != b.phone() ? (a.phone() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        return peers;
    }

    /** How long ago, in the largest whole unit: {@code 9 days ago}. Tailscale's zero date means never. */
    static String ago(Optional<Instant> when, Instant now) {
        if (when.isEmpty() || when.get().getEpochSecond() <= 0) return "never";
        Duration d = Duration.between(when.get(), now);
        if (d.toDays() >= 1) return d.toDays() + (d.toDays() == 1 ? " day ago" : " days ago");
        if (d.toHours() >= 1) return d.toHours() + (d.toHours() == 1 ? " hour ago" : " hours ago");
        return Math.max(0, d.toMinutes()) + " min ago";
    }

    private static Optional<Instant> instant(String text) {
        try {
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(Instant.parse(text));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static void line(boolean ok, String what, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + ": " + detail);
    }
}

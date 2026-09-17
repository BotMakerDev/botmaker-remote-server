package com.botmaker.remote;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three text formats this server reads, with no process spawned. */
class ParsingTest {

    @Test
    void tmuxWindowsReadInIndexOrderAndTolerateStrayLines() {
        List<Tmux.Window> windows = Tmux.parse("""
                0\tbgroisne\tnode\t1
                1\taccount 2\tbash\t0
                not a window
                3\trenamed\tclaude\t0
                """);
        assertEquals(3, windows.size());
        assertEquals(new Tmux.Window(0, "bgroisne", "node", true), windows.get(0));
        assertTrue(windows.get(0).claudeRunning());
        assertFalse(windows.get(1).claudeRunning());
        assertTrue(windows.get(2).claudeRunning());
    }

    @Test
    void cswapAccountsCarrySlotLabelAndUsage() {
        List<Cswap.Account> accounts = Cswap.parse("""
                Accounts:
                  1: someone@example.com [someone@example.com's Organization] (active)
                     ├ 5h:  19%   resets 16:10         in 4h 10m
                     └ 7d:  20%   resets Sep 23 04:00  in 5d 16h · 4m ago

                  2: other.person@example.org [x]
                     ├ 5h:   0%
                     └ 7d:  17%   resets Sep 22 20:59  in 5d 9h · 6m ago

                Running instances:
                  1: pid 1234
                """);
        assertEquals(2, accounts.size());
        assertEquals(new Cswap.Account("1", "someone", 19, 20, true), accounts.get(0));
        assertEquals(new Cswap.Account("2", "other.person", 0, 17, false), accounts.get(1));
    }

    @Test
    void theListenerOnAPortIsNamedWithItsPidAndOnlyTheLocalColumnIsMatched() {
        // Trimmed from `ss -ltnp` on the dev box, 2026-09-17: the packaged server on the tailnet address,
        // and two lines that carry 7788 somewhere that is not the local address.
        String listening = """
                State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process
                LISTEN 0      4096     100.75.38.1:7788       0.0.0.0:*     users:(("java",pid=247245,fd=12))
                LISTEN 0      7788       127.0.0.1:6463       0.0.0.0:*     users:(("Discord",pid=109015,fd=85))
                LISTEN 0      128        127.0.0.1:44847      0.0.0.0:*     users:(("steam",pid=83902,fd=95))
                """;

        assertEquals(Optional.of("java (pid 247245)"), Ports.parse(listening, 7788));
        assertEquals(Optional.empty(), Ports.parse(listening, 7799));
        assertEquals(Optional.empty(), Ports.parse("", 7788));
    }

    @Test
    void aSocketOwnedByAnotherUserIsStillAnAnswer() {
        // ss prints no users:(()) tuple for a socket this user does not own. The port is taken either way,
        // and "something" beats a bind exception with nothing after it.
        assertEquals(Optional.of("another user's process"),
                Ports.parse("LISTEN 0 4096 0.0.0.0:7788 0.0.0.0:*\n", 7788));
    }

    @Test
    void pairIsAFlagAndKeepsTheOtherOptions() {
        RemoteServer.Options options = RemoteServer.Options.parse(
                new String[]{"--pair", "--port", "7799", "--big-qr"});

        assertTrue(options.pair());
        assertEquals(7799, options.port());
        assertTrue(options.bigQr());
        assertFalse(RemoteServer.Options.parse(new String[]{}).pair());
    }

    @Test
    void tailnetAddressIsTheInetOnTailscale0() {
        assertEquals(Optional.of("100.75.38.1"),
                Tailnet.parse("5: tailscale0    inet 100.75.38.1/32 scope global tailscale0\\       valid_lft forever"));
        assertEquals(Optional.empty(), Tailnet.parse(""));
    }
}

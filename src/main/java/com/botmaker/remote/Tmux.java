package com.botmaker.remote;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The tmux session the phone attaches to — {@code claude} — and its windows, one per Claude account.
 *
 * <p>tmux is the whole persistence story: a Claude session lives in a tmux window, the phone attaches to
 * that window and detaches when the app sleeps, and nothing about the conversation is held here. That is
 * also why an account is a <em>window name</em>: {@code cswap run <slot> -- claude} is what the window runs,
 * and the slot is the one fact the phone needs to tell two windows apart.
 *
 * <p>Every call is a fresh {@code tmux} process. The list is asked for when a screen is drawn; nothing is
 * cached, because tmux is the truth and a cached list would go on showing a window the user closed from
 * the desktop.
 */
public final class Tmux {

    /** The session every window lives in. */
    public static final String SESSION = "claude";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** The format {@link #list()} asks for; {@link #parse(String)} reads exactly this. */
    static final String FORMAT = "#{window_index}\t#{window_name}\t#{pane_current_command}\t#{window_active}";

    /**
     * One window.
     *
     * @param index   the tmux window index, the id every route uses
     * @param name    the window name — the cswap slot it was created for, or whatever the user renamed it
     * @param command the foreground command in the active pane ({@code node} while Claude runs, a shell when
     *                it has exited)
     * @param active  tmux's own current window, which the phone does not care about but a test can assert
     */
    public record Window(int index, String name, String command, boolean active) {

        /** Whether Claude is still running in this window: it is a node program, and a shell means it quit. */
        public boolean claudeRunning() {
            return "node".equals(command) || "claude".equals(command);
        }
    }

    private Tmux() {
    }

    /** The windows of the session, in index order; an absent session is an empty list, not a failure. */
    public static List<Window> list() {
        Proc listed = Proc.run(TIMEOUT, "tmux", "list-windows", "-t", SESSION, "-F", FORMAT);
        return listed.ok() ? parse(listed.out()) : List.of();
    }

    static List<Window> parse(String text) {
        List<Window> windows = new ArrayList<>();
        for (String line : text.lines().toList()) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 4) continue;
            try {
                windows.add(new Window(Integer.parseInt(parts[0].trim()), parts[1], parts[2],
                        "1".equals(parts[3].trim())));
            } catch (NumberFormatException e) {
                // A line that is not a window; tmux prints none, but a stray message must not empty the list.
            }
        }
        return List.copyOf(windows);
    }

    /** Whether the session exists at all. */
    public static boolean sessionExists() {
        return Proc.run(TIMEOUT, "tmux", "has-session", "-t", SESSION).ok();
    }

    /**
     * Opens a window running Claude under {@code slot}, creating the session when there is none.
     *
     * <p>The window's name is the slot's label so two accounts read apart in the list. {@code -d} keeps
     * tmux's own current window where it was: somebody typing at the desktop must not have their window
     * swapped under them because a phone opened another.
     */
    public static Optional<Window> create(String slot, String name) {
        String windowName = name == null || name.isBlank() ? "account " + slot : name;
        Proc made;
        if (sessionExists()) {
            made = Proc.run(TIMEOUT, "tmux", "new-window", "-d", "-P", "-F", "#{window_index}", "-t", SESSION,
                    "-n", windowName, "--", "cswap", "run", slot, "--", "claude");
        } else {
            made = Proc.run(TIMEOUT, "tmux", "new-session", "-d", "-P", "-F", "#{window_index}", "-s", SESSION,
                    "-n", windowName, "--", "cswap", "run", slot, "--", "claude");
        }
        if (!made.ok()) return Optional.empty();
        String printed = made.out().trim();
        try {
            int index = Integer.parseInt(printed);
            return list().stream().filter(w -> w.index() == index).findFirst();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Closes a window; whatever ran in it is gone. False when tmux refused (no such window). */
    public static boolean kill(int index) {
        return Proc.run(TIMEOUT, "tmux", "kill-window", "-t", target(index)).ok();
    }

    /**
     * Types {@code text} into a window as if at its keyboard, then presses Enter when {@code enter}.
     *
     * <p>{@code -l} sends the text literally, so {@code /clear} is not read as a key name. The specials a
     * quick-reply bar needs ({@code Escape}, {@code C-c}) go through {@link #key(int, String)} instead.
     */
    public static boolean type(int index, String text, boolean enter) {
        if (!text.isEmpty()
                && !Proc.run(TIMEOUT, "tmux", "send-keys", "-t", target(index), "-l", "--", text).ok()) {
            return false;
        }
        return !enter || Proc.run(TIMEOUT, "tmux", "send-keys", "-t", target(index), "Enter").ok();
    }

    /** Presses one tmux key name — {@code Escape}, {@code C-c}, {@code Enter}, {@code Up}. */
    public static boolean key(int index, String keyName) {
        return Proc.run(TIMEOUT, "tmux", "send-keys", "-t", target(index), keyName).ok();
    }

    /**
     * A private view of the session for one phone, looking at window {@code index}.
     *
     * <p><b>Not {@code tmux attach -t claude}.</b> Every client attached to one session shares its current
     * window, so a phone opening window 2 would swap the desktop terminal to window 2 as well. A
     * <em>grouped</em> session ({@code new-session -t claude}) shares the windows and keeps its own current
     * one, and {@code destroy-unattached} lets tmux remove it the moment the phone detaches — so a phone
     * that vanishes mid-session leaves nothing behind. The attach command for the PTY is
     * {@code tmux attach -t <the view>}.
     */
    public static Optional<String> openView(int index) {
        String view = SESSION + "-view-" + Long.toUnsignedString(System.nanoTime(), 36);
        if (!Proc.run(TIMEOUT, "tmux", "new-session", "-d", "-t", SESSION, "-s", view).ok()) {
            return Optional.empty();
        }
        Proc.run(TIMEOUT, "tmux", "select-window", "-t", view + ":" + index);
        return Optional.of(view);
    }

    /** Removes a view {@link #openView} made; harmless when tmux already destroyed it. */
    public static void closeView(String view) {
        Proc.run(TIMEOUT, "tmux", "kill-session", "-t", view);
    }

    /**
     * The command a terminal attaches a view with.
     *
     * <p>{@code destroy-unattached} is set <em>after</em> attaching, in the same command chain: set on the
     * detached session {@link #openView} just made, tmux destroys it on the spot, before anything attaches
     * (found the hard way — the first attach ended with "detached" a millisecond in).
     */
    public static String[] attachCommand(String view) {
        return new String[] {"tmux", "attach-session", "-t", view, ";", "set-option", "destroy-unattached", "on"};
    }

    static String target(int index) {
        return SESSION + ":" + index;
    }
}

package com.botmaker.remote;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What each window is doing, as far as Claude Code's own hooks say.
 *
 * <p>A terminal cannot tell whether Claude is thinking or waiting for the next prompt — both are a cursor.
 * Claude Code can: its {@code Stop} hook fires when a turn ends and {@code Notification} when it asks for
 * a permission or an answer, and {@code tools/claude-hook.sh} posts both here with the tmux window index.
 * Typing into the window from the phone (or any text the phone sends) turns it back to {@code running}.
 *
 * <p>This is the one piece of state the server holds, and losing it costs a badge: a restart shows every
 * window {@code idle} until its next hook, which is the honest answer.
 */
public final class Activity {

    /** What a window is doing. Serialised by {@link #id()}, which is what the app switches on. */
    public enum State {
        /** Claude is working on a turn, or nothing has been heard yet. */
        RUNNING("running"),
        /** Claude finished a turn or asked something — somebody should look. */
        WAITING("waiting"),
        /** No Claude in the window (the shell is back). */
        IDLE("idle");

        private final String id;

        State(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** One change, as pushed to every open socket and to the ntfy topic. */
    public record Event(int window, State state, String message, Instant at) {
    }

    private final Map<Integer, Event> latest = new ConcurrentHashMap<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    /** The last event for a window, when a hook has ever reported one. */
    public Optional<Event> of(int window) {
        return Optional.ofNullable(latest.get(window));
    }

    /**
     * The state to draw for a window: the hook's answer, unless tmux says Claude is not even running there.
     */
    public State stateOf(Tmux.Window window) {
        if (!window.claudeRunning()) return State.IDLE;
        Event last = latest.get(window.index());
        return last == null ? State.RUNNING : last.state();
    }

    /**
     * A hook fired. {@code event} is Claude Code's own name — {@code Stop}, {@code Notification},
     * {@code UserPromptSubmit} — read case-insensitively; anything else is ignored rather than refused, so
     * a hook added to the settings file tomorrow does not fail today's server.
     */
    public Optional<Event> hook(int window, String event, String message) {
        String name = event == null ? "" : event.trim().toLowerCase();
        State state = switch (name) {
            case "stop", "notification" -> State.WAITING;
            case "userpromptsubmit", "user-prompt-submit", "input" -> State.RUNNING;
            default -> null;
        };
        if (state == null) return Optional.empty();
        Event fired = new Event(window, state, message == null ? "" : message, Instant.now());
        latest.put(window, fired);
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(fired);
            } catch (RuntimeException e) {
                // One dead socket must not stop the others hearing it.
            }
        }
        return Optional.of(fired);
    }

    /** Text went into the window from the phone: Claude has something to do again. */
    public void typed(int window) {
        hook(window, "input", "");
    }

    /** A window closed; its last event is no longer worth anything. */
    public void forget(int window) {
        latest.remove(window);
    }

    public Runnable listen(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}

package com.botmaker.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.websocket.WsContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTTP and WebSocket surface, in one place so the whole of what a phone can do is readable top to bottom.
 *
 * <pre>
 *   GET    /api/status                    who am I, tmux present, cswap present, ntfy configured
 *   GET    /api/accounts                  the cswap slots
 *   GET    /api/sessions                  the tmux windows with their activity state
 *   POST   /api/sessions      {slot,name} open a window running Claude under that account
 *   DELETE /api/sessions/{i}              kill a window
 *   POST   /api/sessions/{i}/send {text,enter} | {key}   type into it without attaching
 *   POST   /api/hook          {event,window,message}    Claude Code's hooks report here
 *   WS     /ws/events                     every activity change, as JSON
 *   WS     /ws/term/{i}?cols=&rows=       the terminal: binary both ways, JSON control frames
 * </pre>
 *
 * <p>Every route wants the token — {@code X-Botmaker-Token} header, {@code Authorization: Bearer}, or
 * {@code ?token=} for the WebSocket handshakes a browser cannot add a header to — and answers 401 without
 * it. The hook route is the one exception in <em>where the token comes from</em>, not in whether: the hook
 * script reads it off the same file this server does.
 */
public final class Routes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Token token;
    private final Activity activity;
    private final Ntfy ntfy;
    private final String version;
    private final Map<WsContext, Terminal> terminals = new ConcurrentHashMap<>();
    private final Map<WsContext, Integer> attachedWindow = new ConcurrentHashMap<>();
    private final List<WsContext> eventSockets = new ArrayList<>();

    public Routes(Token token, Activity activity, Ntfy ntfy, String version) {
        this.token = token;
        this.activity = activity;
        this.ntfy = ntfy;
        this.version = version;
        activity.listen(this::broadcast);
    }

    public void install(Javalin app) {
        app.before("/api/*", ctx -> {
            if (!token.matches(tokenOf(ctx))) throw new UnauthorizedResponse("bad or missing token");
        });
        app.wsBefore(ws -> ws.onConnect(ctx -> {
            if (!token.matches(ctx.queryParam("token"))) {
                ctx.closeSession(4401, "bad or missing token");
            }
        }));

        app.get("/api/status", ctx -> ctx.json(Map.of(
                "version", version,
                "tmux", Tmux.sessionExists(),
                "cswap", Cswap.available(),
                "ntfy", ntfy.configured())));

        app.get("/api/accounts", ctx -> ctx.json(Cswap.list()));

        app.get("/api/sessions", ctx -> ctx.json(sessions()));

        app.post("/api/sessions", ctx -> {
            JsonNode body = JSON.readTree(ctx.body());
            String slot = body.path("slot").asText("").trim();
            if (slot.isEmpty()) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "slot is required"));
                return;
            }
            Optional<Tmux.Window> made = Tmux.create(slot, body.path("name").asText(""));
            if (made.isEmpty()) {
                ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of("error", "tmux could not open a window"));
                return;
            }
            ctx.status(HttpStatus.CREATED).json(session(made.get()));
        });

        app.delete("/api/sessions/{i}", ctx -> {
            int index = Integer.parseInt(ctx.pathParam("i"));
            if (!Tmux.kill(index)) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "no window " + index));
                return;
            }
            activity.forget(index);
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.post("/api/sessions/{i}/send", ctx -> {
            int index = Integer.parseInt(ctx.pathParam("i"));
            JsonNode body = JSON.readTree(ctx.body());
            boolean ok;
            if (body.hasNonNull("key")) {
                ok = Tmux.key(index, body.get("key").asText());
            } else {
                ok = Tmux.type(index, body.path("text").asText(""), body.path("enter").asBoolean(true));
            }
            if (!ok) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "no window " + index));
                return;
            }
            activity.typed(index);
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.post("/api/hook", ctx -> {
            JsonNode body = JSON.readTree(ctx.body());
            int window = body.path("window").asInt(-1);
            if (window < 0) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "window is required"));
                return;
            }
            Optional<Activity.Event> fired = activity.hook(window,
                    body.path("event").asText(""), body.path("message").asText(""));
            fired.filter(e -> e.state() == Activity.State.WAITING).ifPresent(e ->
                    ntfy.send("Claude is waiting (window " + e.window() + ")",
                            e.message().isBlank() ? "Turn finished." : e.message()));
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.ws("/ws/events", ws -> {
            ws.onConnect(ctx -> {
                ctx.enableAutomaticPings();
                synchronized (eventSockets) {
                    eventSockets.add(ctx);
                }
            });
            ws.onClose(ctx -> {
                synchronized (eventSockets) {
                    eventSockets.remove(ctx);
                }
            });
        });

        app.ws("/ws/term/{i}", ws -> {
            ws.onConnect(ctx -> {
                ctx.enableAutomaticPings();
                int index = Integer.parseInt(ctx.pathParam("i"));
                int cols = intParam(ctx.queryParam("cols"), 80);
                int rows = intParam(ctx.queryParam("rows"), 24);
                try {
                    Terminal terminal = Terminal.attach(index, cols, rows,
                            bytes -> {
                                if (ctx.session.isOpen()) ctx.send(ByteBuffer.wrap(bytes));
                            },
                            () -> {
                                if (ctx.session.isOpen()) ctx.closeSession(1000, "detached");
                            });
                    terminals.put(ctx, terminal);
                    attachedWindow.put(ctx, index);
                } catch (IOException e) {
                    ctx.send(event("error", Map.of("message", e.getMessage())));
                    ctx.closeSession(4404, e.getMessage());
                }
            });
            ws.onBinaryMessage(ctx -> {
                Terminal terminal = terminals.get(ctx);
                if (terminal == null) return;
                byte[] bytes = ctx.data();
                try {
                    terminal.write(bytes);
                    Integer index = attachedWindow.get(ctx);
                    if (index != null && bytes.length > 0) activity.typed(index);
                } catch (IOException e) {
                    ctx.closeSession(1011, "pty write failed");
                }
            });
            ws.onMessage(ctx -> {
                Terminal terminal = terminals.get(ctx);
                if (terminal == null) return;
                JsonNode control = JSON.readTree(ctx.message());
                if ("resize".equals(control.path("type").asText())) {
                    terminal.resize(control.path("cols").asInt(0), control.path("rows").asInt(0));
                }
            });
            ws.onClose(ctx -> {
                Terminal terminal = terminals.remove(ctx);
                attachedWindow.remove(ctx);
                if (terminal != null) terminal.close();
            });
            ws.onError(ctx -> {
                Terminal terminal = terminals.remove(ctx);
                attachedWindow.remove(ctx);
                if (terminal != null) terminal.close();
            });
        });
    }

    // --- shapes ------------------------------------------------------------------------------------------

    private List<Map<String, Object>> sessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tmux.Window window : Tmux.list()) out.add(session(window));
        return out;
    }

    private Map<String, Object> session(Tmux.Window window) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", window.index());
        row.put("name", window.name());
        row.put("command", window.command());
        row.put("state", activity.stateOf(window).id());
        activity.of(window.index()).ifPresent(e -> {
            row.put("message", e.message());
            row.put("at", e.at().toString());
        });
        return row;
    }

    private void broadcast(Activity.Event fired) {
        String text = event("activity", Map.of(
                "window", fired.window(),
                "state", fired.state().id(),
                "message", fired.message(),
                "at", fired.at().toString()));
        List<WsContext> targets;
        synchronized (eventSockets) {
            targets = new ArrayList<>(eventSockets);
        }
        targets.addAll(terminals.keySet());
        for (WsContext ctx : targets) {
            if (ctx.session.isOpen()) ctx.send(text);
        }
    }

    private static String event(String type, Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.putAll(fields);
        try {
            return JSON.writeValueAsString(out);
        } catch (IOException e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }

    static String tokenOf(Context ctx) {
        String header = ctx.header("X-Botmaker-Token");
        if (header != null) return header;
        String auth = ctx.header("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring("Bearer ".length());
        return ctx.queryParam("token");
    }

    private static int intParam(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

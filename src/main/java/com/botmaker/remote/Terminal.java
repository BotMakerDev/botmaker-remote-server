package com.botmaker.remote;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One pseudo-terminal running {@code tmux attach} on a private view of the session, for one WebSocket.
 *
 * <p>Bytes in, bytes out, and a size. The phone's xterm.js is the terminal; this is the wire between it and
 * the PTY, and it interprets nothing — every escape sequence tmux draws crosses untouched, which is what
 * makes the phone show exactly what a desktop terminal would.
 *
 * <p>The reader is one daemon thread per attached terminal, blocked on the PTY. A phone that drops the
 * socket closes the PTY, {@code tmux attach} exits, and tmux destroys the view (see {@link Tmux#openView}).
 */
public final class Terminal implements AutoCloseable {

    private final String view;
    private final PtyProcess process;
    private final OutputStream input;

    private Terminal(String view, PtyProcess process) {
        this.view = view;
        this.process = process;
        this.input = process.getOutputStream();
    }

    /**
     * Attaches to window {@code index} at {@code cols}×{@code rows}, delivering output to {@code sink} and
     * calling {@code exited} once the attach ends (the window was killed, or the phone detached).
     */
    public static Terminal attach(int index, int cols, int rows, Consumer<byte[]> sink, Runnable exited)
            throws IOException {
        String view = Tmux.openView(index).orElseThrow(() -> new IOException("tmux could not open window " + index));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", env.getOrDefault("LANG", "C.UTF-8"));
        PtyProcess process;
        try {
            process = new PtyProcessBuilder(Tmux.attachCommand(view))
                    .setEnvironment(env)
                    .setInitialColumns(Math.max(cols, 20))
                    .setInitialRows(Math.max(rows, 5))
                    .setRedirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            Tmux.closeView(view);
            throw e;
        }
        Terminal terminal = new Terminal(view, process);
        Thread reader = new Thread(() -> terminal.pump(sink, exited), "pty-" + view);
        reader.setDaemon(true);
        reader.start();
        return terminal;
    }

    private void pump(Consumer<byte[]> sink, Runnable exited) {
        byte[] buffer = new byte[8192];
        try (InputStream out = process.getInputStream()) {
            int n;
            while ((n = out.read(buffer)) >= 0) {
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buffer, 0, chunk, 0, n);
                    sink.accept(chunk);
                }
            }
        } catch (IOException e) {
            // The PTY closed under us — the same as EOF.
        } finally {
            exited.run();
        }
    }

    /** Keystrokes from the phone, raw. */
    public void write(byte[] bytes) throws IOException {
        input.write(bytes);
        input.flush();
    }

    public void resize(int cols, int rows) {
        if (cols < 20 || rows < 5) return;
        process.setWinSize(new WinSize(cols, rows));
    }

    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            input.close();
        } catch (IOException e) {
            // Closing anyway.
        }
        process.destroy();
        Tmux.closeView(view);
    }
}

package com.botmaker.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One external command, run to completion with a timeout — {@code tmux}, {@code cswap}, {@code ip}.
 *
 * <p>Stderr is merged into stdout: every caller wants the text either way, and a command that failed
 * says why in the same stream a human would read.
 */
public record Proc(int exit, String out) {

    public boolean ok() {
        return exit == 0;
    }

    public static Proc run(Duration timeout, String... command) {
        return run(timeout, List.of(command));
    }

    public static Proc run(Duration timeout, List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            // Read before waiting: a command that fills the pipe never exits otherwise.
            byte[] bytes = process.getInputStream().readAllBytes();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Proc(-1, command.getFirst() + " did not finish within " + timeout.toSeconds() + "s");
            }
            return new Proc(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Proc(-1, command.getFirst() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Proc(-1, command.getFirst() + ": interrupted");
        }
    }
}

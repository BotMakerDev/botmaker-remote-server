package com.botmaker.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The token and the activity state: the two things a wrong answer from would be a security or a UX bug. */
class GuardsTest {

    @TempDir
    Path dir;

    @Test
    void tokenIsCreatedOnceAndKeptPrivate() throws IOException {
        Path file = dir.resolve("remote").resolve("token");
        Token first = Token.load(file);
        Token again = Token.load(file);
        assertEquals(first.value(), again.value(), "a restart must hand the phone the same URL");
        assertTrue(first.value().length() >= 43, "256 bits of url-safe base64");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
    }

    @Test
    void tokenMatchesItselfAndNothingElse() {
        Token token = Token.of("abc123");
        assertTrue(token.matches("abc123"));
        assertFalse(token.matches("abc12"));
        assertFalse(token.matches("abc1234"));
        assertFalse(token.matches(""));
        assertFalse(token.matches(null));
    }

    @Test
    void hooksTurnAWindowWaitingAndTypingTurnsItBack() {
        Activity activity = new Activity();
        List<Activity.Event> heard = new ArrayList<>();
        activity.listen(heard::add);
        Tmux.Window running = new Tmux.Window(2, "x", "node", false);

        assertEquals(Activity.State.RUNNING, activity.stateOf(running), "nothing heard yet");
        assertTrue(activity.hook(2, "Stop", "done").isPresent());
        assertEquals(Activity.State.WAITING, activity.stateOf(running));
        activity.typed(2);
        assertEquals(Activity.State.RUNNING, activity.stateOf(running));
        assertTrue(activity.hook(2, "SomethingNew", "").isEmpty(), "an unknown hook is ignored, not refused");
        assertEquals(2, heard.size());
    }

    @Test
    void aWindowWithoutClaudeIsIdleWhateverTheHooksSaid() {
        Activity activity = new Activity();
        activity.hook(1, "Notification", "permission?");
        assertEquals(Activity.State.IDLE, activity.stateOf(new Tmux.Window(1, "x", "bash", false)));
    }

    @Test
    void qrRendersHalfBlockRows() {
        String qr = Qr.render("http://100.75.38.1:7788/?token=abc");
        assertTrue(qr.lines().count() > 10);
        assertTrue(qr.contains("█"));
    }
}

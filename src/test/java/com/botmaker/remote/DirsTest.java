package com.botmaker.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The directory browser stays under home, whatever the phone asks for; and a new window starts where it said. */
class DirsTest {

    @TempDir
    Path root;

    private Dirs dirs() throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Files.createDirectories(home.resolve("IdeaProjects/botmaker"));
        Files.createDirectories(home.resolve("Documents"));
        Files.createDirectories(home.resolve(".cache"));
        Files.writeString(home.resolve("notes.txt"), "a file, not a directory");
        Files.createDirectories(root.resolve("outside"));
        return new Dirs(home, root.resolve("config/recent-dirs"));
    }

    @Test
    void homeListsItsVisibleSubDirectoriesAndHasNoParent() throws IOException {
        Dirs dirs = dirs();
        Dirs.Listing listing = dirs.list("").orElseThrow();
        assertEquals(dirs.home().toString(), listing.path());
        assertTrue(listing.parent().isEmpty(), "the browser goes no higher than home");
        assertEquals(List.of("Documents", "IdeaProjects"), listing.dirs());
    }

    @Test
    void aSubDirectoryIsReachedRelativeOrAbsoluteAndNamesItsParent() throws IOException {
        Dirs dirs = dirs();
        Dirs.Listing relative = dirs.list("IdeaProjects").orElseThrow();
        Dirs.Listing absolute = dirs.list(dirs.home().resolve("IdeaProjects").toString()).orElseThrow();
        assertEquals(relative, absolute);
        assertEquals(List.of("botmaker"), relative.dirs());
        assertEquals(dirs.home().toString(), relative.parent().orElseThrow());
    }

    @Test
    void nothingOutsideHomeIsListedHoweverItIsSpelled() throws IOException {
        Dirs dirs = dirs();
        Path home = dirs.home();
        Files.createSymbolicLink(home.resolve("escape"), root.resolve("outside"));
        assertTrue(dirs.list("..").isEmpty());
        assertTrue(dirs.list("IdeaProjects/../../outside").isEmpty());
        assertTrue(dirs.list(root.resolve("outside").toString()).isEmpty());
        assertTrue(dirs.list("escape").isEmpty(), "a symlink out of home is outside home");
        assertTrue(dirs.list("/").isEmpty());
        assertTrue(dirs.list("notes.txt").isEmpty(), "a file is not a directory");
        assertTrue(dirs.list("missing").isEmpty());
    }

    @Test
    void recentIsNewestFirstDeduplicatedAndCapped() throws IOException {
        Dirs dirs = dirs();
        Path home = dirs.home();
        dirs.used(home.resolve("Documents"));
        dirs.used(home.resolve("IdeaProjects"));
        dirs.used(home.resolve("Documents"));
        assertEquals(List.of(home.resolve("Documents").toString(), home.resolve("IdeaProjects").toString()),
                dirs.recent());

        for (int i = 0; i < Dirs.RECENT + 3; i++) {
            dirs.used(Files.createDirectories(home.resolve("d" + i)));
        }
        assertEquals(Dirs.RECENT, dirs.recent().size());
        assertEquals(home.resolve("d" + (Dirs.RECENT + 2)).toString(), dirs.recent().getFirst());
    }

    @Test
    void aRecentDirectoryThatWasDeletedIsNotOffered() throws IOException {
        Dirs dirs = dirs();
        Path gone = Files.createDirectories(dirs.home().resolve("gone"));
        dirs.used(gone);
        Files.delete(gone);
        assertTrue(dirs.recent().isEmpty());
    }

    @Test
    void aNewWindowStartsInTheChosenDirectory() {
        Path cwd = Path.of("/home/me/IdeaProjects");
        assertEquals(List.of("tmux", "new-window", "-d", "-P", "-F", "#{window_index}", "-t", "claude",
                        "-c", "/home/me/IdeaProjects", "-n", "work", "--", "cswap", "run", "2", "--", "claude"),
                Tmux.createCommand(true, "2", "work", cwd));
        List<String> first = Tmux.createCommand(false, "2", "", cwd);
        assertEquals(List.of("tmux", "new-session", "-d", "-P", "-F", "#{window_index}", "-s", "claude",
                "-c", "/home/me/IdeaProjects", "-n", "account 2"), first.subList(0, 12));
    }

    @Test
    void onlyThePhonesViewTurnsTheMouseOn() {
        assertArrayEquals(new String[] {"tmux", "attach-session", "-t", "claude-view-x",
                        ";", "set-option", "destroy-unattached", "on",
                        ";", "set-option", "mouse", "on"},
                Tmux.attachCommand("claude-view-x"));
    }
}

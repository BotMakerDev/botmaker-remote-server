package com.botmaker.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The directories a phone may start a session in: the operator's home and everything under it, and the
 * few it started in last.
 *
 * <p><b>Bounded by the real path, not the spelling.</b> A requested path is resolved with
 * {@link Path#toRealPath}, so {@code ..} and a symlink pointing out of home both land outside it and are
 * refused. The token already hands the phone a shell, so this is not the last line of defence; it is what
 * keeps a directory browser a directory browser.
 *
 * <p>The recent list is a plain file beside the token, newest first, at most {@link #RECENT} lines. It is
 * a convenience: losing it costs a tap.
 */
public final class Dirs {

    /** How many recent directories are kept. */
    static final int RECENT = 8;

    /** A listing is capped: a directory with ten thousand children is not browsed from a phone. */
    static final int MAX_ENTRIES = 500;

    /**
     * One directory's children.
     *
     * @param path   the directory, absolute and real
     * @param parent its parent, or empty at home: the browser goes no higher
     * @param dirs   the names of its visible sub-directories, sorted, hidden ones left out
     */
    public record Listing(String path, Optional<String> parent, List<String> dirs) {
    }

    private final Path home;
    private final Path recentFile;

    /**
     * @param home       the root nothing may leave; resolved to its real path
     * @param recentFile where the recent list is kept
     */
    public Dirs(Path home, Path recentFile) throws IOException {
        this.home = home.toRealPath();
        this.recentFile = recentFile;
    }

    public Path home() {
        return home;
    }

    /**
     * The real directory {@code requested} names, when it is home or under it. A blank request is home; a
     * relative one is read against home, so {@code IdeaProjects} works as well as the absolute path.
     */
    public Optional<Path> resolve(String requested) {
        Path candidate = requested == null || requested.isBlank() ? home : home.resolve(requested.trim());
        try {
            Path real = candidate.toRealPath();
            return real.startsWith(home) && Files.isDirectory(real) ? Optional.of(real) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The children of {@code requested}, or empty when it is not a directory under home. */
    public Optional<Listing> list(String requested) {
        Optional<Path> dir = resolve(requested);
        if (dir.isEmpty()) return Optional.empty();
        Path real = dir.get();
        List<String> names;
        try (Stream<Path> children = Files.list(real)) {
            names = children
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.startsWith("."))
                    .sorted(Comparator.comparing(String::toLowerCase))
                    .limit(MAX_ENTRIES)
                    .toList();
        } catch (IOException e) {
            // Unreadable: shown as empty, so the phone can still pick it or go back up.
            names = List.of();
        }
        Optional<String> parent = real.equals(home) ? Optional.empty()
                : Optional.of(real.getParent().toString());
        return Optional.of(new Listing(real.toString(), parent, names));
    }

    /** The directories sessions started in, newest first, each still a directory under home. */
    public List<String> recent() {
        List<String> out = new ArrayList<>();
        for (String line : read()) {
            resolve(line).map(Path::toString).filter(p -> !out.contains(p)).ifPresent(out::add);
        }
        return out;
    }

    /** Puts {@code dir} first in the recent list. A failed write is ignored: the list is a convenience. */
    public synchronized void used(Path dir) {
        List<String> next = new ArrayList<>();
        next.add(dir.toString());
        for (String line : read()) {
            if (!next.contains(line) && next.size() < RECENT) next.add(line);
        }
        try {
            Files.createDirectories(recentFile.getParent());
            Files.writeString(recentFile, String.join("\n", next) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Nothing to do; the session opened, which is what the phone asked for.
        }
    }

    private List<String> read() {
        try {
            return Files.readAllLines(recentFile, StandardCharsets.UTF_8).stream()
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}

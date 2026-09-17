package com.botmaker.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * The pairing token: the one guard on every request, and therefore the one secret this program keeps.
 *
 * <p>Created once, kept in a {@code 0600} file so a restart hands the phone the same URL it already
 * scanned. 256 bits, URL-safe base64, no padding — it travels inside a URL the phone stores.
 *
 * <p>The compare is constant-time. The server binds the tailnet only, so nothing outside the WireGuard
 * mesh reaches it; the compare is still constant-time because the cost is nil and the day the bind rule is
 * loosened is not the day anybody will remember to change this.
 */
public final class Token {

    private final String value;

    private Token(String value) {
        this.value = value;
    }

    /** The token in {@code file}, or a fresh one written there first. */
    public static Token load(Path file) throws IOException {
        if (Files.exists(file)) {
            String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!stored.isEmpty()) return new Token(stored);
        }
        Files.createDirectories(file.getParent());
        Token fresh = fresh();
        Files.writeString(file, fresh.value + "\n", StandardCharsets.UTF_8);
        restrict(file);
        return fresh;
    }

    public static Token fresh() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new Token(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** For a test that must not touch a file. */
    static Token of(String value) {
        return new Token(value);
    }

    public String value() {
        return value;
    }

    /** Whether {@code provided} is this token, in constant time; a null or blank candidate never matches. */
    public boolean matches(String provided) {
        if (provided == null || provided.isBlank()) return false;
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException e) {
            // Not a POSIX file system: the file is still only ours by the directory it sits in.
        }
    }
}

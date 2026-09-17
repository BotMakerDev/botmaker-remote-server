package com.botmaker.remote;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Claude accounts {@code cswap} manages, read off {@code cswap list}.
 *
 * <p>The parser is the dashboard's ({@code CswapAccounts}), carried here rather than shared because the two
 * programs share no module and forty lines is cheaper than a dependency between an operator's window and a
 * terminal server. The one fact both agree on: an account is a <b>slot number</b>, never an address — the
 * email stays on this machine.
 */
public final class Cswap {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** {@code   1: someone@example.com [...] (active)} — the slot line. */
    private static final Pattern SLOT = Pattern.compile("^\\s{2}(\\d+):\\s+(\\S+)");

    /** {@code      ├ 5h:  19%   resets ...} — a usage line under a slot. */
    private static final Pattern USAGE = Pattern.compile("^\\s+\\S?\\s*(5h|7d):\\s+(\\d+)%");

    /**
     * One account.
     *
     * @param slot     the number {@code cswap run <slot>} takes
     * @param label    what the phone shows — the address's local part, so a list reads apart without
     *                 spelling the whole address on a lock screen
     * @param fiveHour percent of the five-hour window used
     * @param sevenDay percent of the seven-day window used
     * @param active   the account the terminal's default {@code claude} would use
     */
    public record Account(String slot, String label, int fiveHour, int sevenDay, boolean active) {
    }

    private Cswap() {
    }

    /** Whether {@code cswap} is on the {@code PATH} at all. */
    public static boolean available() {
        return Proc.run(Duration.ofSeconds(5), "cswap", "help").ok();
    }

    public static List<Account> list() {
        Proc listed = Proc.run(TIMEOUT, "cswap", "list");
        return listed.ok() ? parse(listed.out()) : List.of();
    }

    static List<Account> parse(String text) {
        List<Account> accounts = new ArrayList<>();
        String slot = null;
        String label = "";
        boolean active = false;
        int fiveHour = 0;
        int sevenDay = 0;
        for (String line : text.lines().toList()) {
            if (line.startsWith("Running instances:")) break;   // the sessions below are not accounts
            Matcher head = SLOT.matcher(line);
            if (head.find()) {
                if (slot != null) accounts.add(new Account(slot, label, fiveHour, sevenDay, active));
                slot = head.group(1);
                label = labelOf(head.group(2));
                active = line.contains("(active)");
                fiveHour = 0;
                sevenDay = 0;
                continue;
            }
            Matcher usage = USAGE.matcher(line);
            if (slot != null && usage.find()) {
                int percent = Integer.parseInt(usage.group(2));
                if ("5h".equals(usage.group(1))) fiveHour = percent;
                else sevenDay = percent;
            }
        }
        if (slot != null) accounts.add(new Account(slot, label, fiveHour, sevenDay, active));
        return List.copyOf(accounts);
    }

    /** {@code someone@example.com} → {@code someone}; anything without an {@code @} is kept whole. */
    static String labelOf(String address) {
        int at = address.indexOf('@');
        return at > 0 ? address.substring(0, at) : address;
    }
}

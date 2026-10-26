package org.tsdb.cli;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Время в аргументах CLI. java.time.Duration.parse понимает только PT1H, поэтому свой разбор.
//   длительность: 500ms, 15s, 1m, 1h30m, 2d, 1w; «0» — ноль
//   момент: now, -1h / now-1h (раньше «сейчас»), +5m, 1761480000000 (epoch ms), 2026-10-26T12:00:00Z
final class TimeArgs {
    private static final Pattern PART = Pattern.compile("(\\d+)(ms|s|m|h|d|w)");

    private TimeArgs() {
    }

    static long durationMs(String text) {
        String s = text.trim();
        if (s.equals("0")) {
            return 0;
        }
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find() && m.start() == end) {
            long n = Long.parseLong(m.group(1));
            total = Math.addExact(total, Math.multiplyExact(n, switch (m.group(2)) {
                case "ms" -> 1L;
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 604_800_000L; // w
            }));
            end = m.end();
        }
        if (end == 0 || end != s.length()) {
            throw new IllegalArgumentException("invalid duration '" + text + "', expected e.g. 15s, 1m, 1h30m");
        }
        return total;
    }

    static long timeMs(String text, long nowMs) {
        String s = text.trim();
        if (s.startsWith("now")) {
            s = s.substring(3);
            if (s.isEmpty()) {
                return nowMs;
            }
        }
        if (s.startsWith("-")) {
            return nowMs - durationMs(s.substring(1));
        }
        if (s.startsWith("+")) {
            return nowMs + durationMs(s.substring(1));
        }
        if (s.chars().allMatch(Character::isDigit) && !s.isEmpty()) {
            return Long.parseLong(s);
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "invalid time '" + text + "', expected now, -1h, epoch ms or 2026-10-26T12:00:00Z", e);
        }
    }
}

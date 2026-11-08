package org.tsdb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TimeArgsTest {
    private static final long NOW = 1_761_480_000_000L;

    @Test
    void durations() {
        assertEquals(0, TimeArgs.durationMs("0"));
        assertEquals(500, TimeArgs.durationMs("500ms"));
        assertEquals(15_000, TimeArgs.durationMs("15s"));
        assertEquals(60_000, TimeArgs.durationMs("1m"));
        assertEquals(5_400_000, TimeArgs.durationMs("1h30m"));
        assertEquals(2 * 86_400_000L, TimeArgs.durationMs("2d"));
        assertEquals(604_800_000L, TimeArgs.durationMs("1w"));
    }

    @Test
    void invalidDurations() {
        assertThrows(IllegalArgumentException.class, () -> TimeArgs.durationMs(""));
        assertThrows(IllegalArgumentException.class, () -> TimeArgs.durationMs("15"));
        assertThrows(IllegalArgumentException.class, () -> TimeArgs.durationMs("1x"));
        assertThrows(IllegalArgumentException.class, () -> TimeArgs.durationMs("m1"));
        assertThrows(IllegalArgumentException.class, () -> TimeArgs.durationMs("1h 30m"));
    }

    @Test
    void timePoints() {
        assertEquals(NOW, TimeArgs.timeMs("now", NOW));
        assertEquals(NOW - 3_600_000, TimeArgs.timeMs("-1h", NOW));
        assertEquals(NOW - 3_600_000, TimeArgs.timeMs("now-1h", NOW));
        assertEquals(NOW + 300_000, TimeArgs.timeMs("+5m", NOW));
        assertEquals(1234, TimeArgs.timeMs("1234", NOW));
        assertEquals(1_761_480_000_000L, TimeArgs.timeMs("2025-10-26T12:00:00Z", NOW));
        assertThrows(IllegalArgumentException.class, () -> TimeArgs.timeMs("yesterday", NOW));
    }
}

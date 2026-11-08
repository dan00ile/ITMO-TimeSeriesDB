package org.tsdb.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class LabelsTest {

    @Test
    void orderOfLabelsDoesNotMatter() {
        Labels a = Labels.of("cpu_usage", "host", "web-1", "core", "0");
        Labels b = Labels.of("cpu_usage", "core", "0", "host", "web-1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void canonicalString() {
        Labels l = Labels.of("cpu_usage", "host", "web-1", "core", "0");
        assertEquals("cpu_usage{core=\"0\",host=\"web-1\"}", l.canonical());
    }

    @Test
    void getReturnsValue() {
        Labels l = Labels.of("cpu_usage", "host", "web-1");
        assertEquals(Optional.of("web-1"), l.get("host"));
        assertEquals(Optional.of("cpu_usage"), l.get(Labels.METRIC_NAME));
        assertEquals(Optional.empty(), l.get("core"));
    }

    @Test
    void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> Labels.of("m", "host"));
        assertThrows(IllegalArgumentException.class, () -> Labels.of("m", "1host", "x"));
        assertThrows(IllegalArgumentException.class, () -> Labels.of("m", "host", "a", "host", "b"));
    }

    @Test
    void labelsAreImmutable() {
        List<Label> src = new java.util.ArrayList<>(List.of(new Label("a", "1")));
        Labels l = new Labels(src);
        src.add(new Label("b", "2"));
        assertEquals(1, l.labels().size());
    }
}

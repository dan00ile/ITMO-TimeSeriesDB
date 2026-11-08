package org.tsdb.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.tsdb.model.Matcher.Eq;
import org.tsdb.model.Matcher.NotEq;
import org.tsdb.model.Matcher.NotRegex;
import org.tsdb.model.Matcher.Regex;

class SelectorParserTest {

    @Test
    void metricOnly() {
        assertEquals(List.of(new Eq("__name__", "cpu_usage")), SelectorParser.parse("cpu_usage"));
        assertEquals(List.of(new Eq("__name__", "node:cpu:rate5m")), SelectorParser.parse("node:cpu:rate5m"));
    }

    @Test
    void allFourOperators() {
        assertEquals(List.of(
                        new Eq("__name__", "cpu_usage"),
                        new Eq("host", "web-1"),
                        new NotEq("core", "1"),
                        new Regex("dc", "eu-.*"),
                        new NotRegex("env", "test|dev")),
                SelectorParser.parse("cpu_usage{host=\"web-1\",core!=\"1\",dc=~\"eu-.*\",env!~\"test|dev\"}"));
    }

    @Test
    void spacesTrailingCommaAndSingleQuotes() {
        assertEquals(List.of(new Eq("__name__", "cpu"), new Eq("host", "a"), new Regex("core", "0|1")),
                SelectorParser.parse("  cpu { host = 'a' , core =~ \"0|1\", }  "));
    }

    @Test
    void nameInsideBraces() {
        assertEquals(List.of(new Eq("__name__", "cpu"), new Regex("host", "web-.*")),
                SelectorParser.parse("{__name__=\"cpu\", host=~\"web-.*\"}"));
    }

    @Test
    void escapesInValues() {
        assertEquals(List.of(new Eq("__name__", "m"), new Eq("path", "say \"hi\"\\n")),
                SelectorParser.parse("m{path=\"say \\\"hi\\\"\\\\n\"}"));
        // \d и \. в regex доходят до Pattern без изменений
        assertEquals(List.of(new Eq("__name__", "m"), new Regex("ip", "10\\.0\\.\\d+")),
                SelectorParser.parse("m{ip=~\"10\\.0\\.\\d+\"}"));
    }

    @Test
    void errorsPointAtPosition() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SelectorParser.parse("cpu{host=\"a\""));
        assertTrue(e.getMessage().contains("end of selector"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("cpu{host}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("cpu{host=a}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("cpu{host==\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("cpu{1host=\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("cpu{host=\"a\" core=\"0\"}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("cpu{host=\"a\"} extra"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parse("cpu{host=~\"web-(\"}"));
    }

    @Test
    void parseLabelsForWrite() {
        assertEquals(Labels.of("cpu_usage", "host", "web-1", "core", "0"),
                SelectorParser.parseLabels("cpu_usage{core=\"0\",host=\"web-1\"}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parseLabels("cpu{host=~\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parseLabels("{host=\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> SelectorParser.parseLabels("cpu{host=\"a\",host=\"b\"}"));
    }
}

package org.tsdb.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;

// Разбор селектора в стиле Prometheus в список Matcher:
//   cpu_usage                                  → [__name__="cpu_usage"]
//   cpu_usage{host="web-1", core!="1"}         → [__name__="cpu_usage", host="web-1", core!="1"]
//   {__name__="cpu_usage", host=~"web-.*"}     → то же без имени перед скобками
// Операторы: =, !=, =~, !~. Значение — в двойных или одинарных кавычках, экранирование \" \' \\ \n \t.
// Ошибки — IllegalArgumentException с позицией: «expected '}' at 17: cpu{host="a"».
public final class SelectorParser {
    private final String input;
    private int pos;

    private SelectorParser(String input) {
        this.input = input;
    }

    public static List<Matcher> parse(String selector) {
        return new SelectorParser(Objects.requireNonNull(selector, "selector")).selector();
    }

    // Точные лейблы серии для записи: cpu_usage{host="web-1"} → Labels. Разрешён только оператор «=».
    public static Labels parseLabels(String series) {
        List<Label> labels = new ArrayList<>();
        for (Matcher m : parse(series)) {
            if (!(m instanceof Matcher.Eq eq)) {
                throw new IllegalArgumentException("series labels allow only '=', got " + m + " in " + series);
            }
            labels.add(new Label(eq.name(), eq.value()));
        }
        Labels result = new Labels(labels);
        if (result.metric().orElse("").isEmpty()) {
            throw new IllegalArgumentException("series needs a metric name: " + series);
        }
        return result;
    }

    private List<Matcher> selector() {
        List<Matcher> matchers = new ArrayList<>();
        skipSpaces();
        if (pos < input.length() && input.charAt(pos) != '{') {
            matchers.add(new Matcher.Eq(Labels.METRIC_NAME, identifier(true)));
            skipSpaces();
        }
        if (pos < input.length() && input.charAt(pos) == '{') {
            pos++;
            skipSpaces();
            while (!peek('}')) {
                matchers.add(matcher());
                skipSpaces();
                if (peek(',')) {
                    pos++;
                    skipSpaces();
                } else if (!peek('}')) {
                    throw error("expected ',' or '}'");
                }
            }
            pos++; // '}'
            skipSpaces();
        }
        if (pos < input.length()) {
            throw error("unexpected '" + input.charAt(pos) + "'");
        }
        if (matchers.isEmpty()) {
            throw error("empty selector");
        }
        return List.copyOf(matchers);
    }

    private Matcher matcher() {
        String name = identifier(false);
        skipSpaces();
        String op = operator();
        skipSpaces();
        String value = string();
        try {
            return switch (op) {
                case "=" -> new Matcher.Eq(name, value);
                case "!=" -> new Matcher.NotEq(name, value);
                case "=~" -> new Matcher.Regex(name, value);
                default -> new Matcher.NotRegex(name, value);
            };
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex for " + name + ": " + e.getDescription(), e);
        }
    }

    // Имя лейбла: [a-zA-Z_][a-zA-Z0-9_]*; в имени метрики допускается ещё ':' (как в Prometheus).
    private String identifier(boolean metric) {
        int start = pos;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            boolean ok = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (pos > start && c >= '0' && c <= '9') || (metric && c == ':');
            if (!ok) {
                break;
            }
            pos++;
        }
        if (pos == start) {
            throw error(metric ? "expected metric name or '{'" : "expected label name");
        }
        return input.substring(start, pos);
    }

    private String operator() {
        if (input.startsWith("=~", pos) || input.startsWith("!=", pos) || input.startsWith("!~", pos)) {
            pos += 2;
            return input.substring(pos - 2, pos);
        }
        if (peek('=')) {
            pos++;
            return "=";
        }
        throw error("expected one of = != =~ !~");
    }

    private String string() {
        if (!peek('"') && !peek('\'')) {
            throw error("expected quoted value");
        }
        char quote = input.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= input.length()) {
                    break;
                }
                char e = input.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '\\', '"', '\'' -> sb.append(e);
                    // в regex \d, \. и т.п. должны дойти до Pattern как есть
                    default -> sb.append('\\').append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private boolean peek(char c) {
        if (pos >= input.length()) {
            throw error("unexpected end of selector");
        }
        return input.charAt(pos) == c;
    }

    private void skipSpaces() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + pos + ": " + input);
    }
}

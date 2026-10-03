package org.tsdb.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// Набор лейблов серии. Имя метрики хранится как лейбл
// Неизменяемый, всегда отсортирован по имени лейбла — поэтому equals hashCode корректны независимо от порядка на входе,
//  а canonical() годится как ключ(в лабе 5 — вход хеш-функции шардирования).
public record Labels(List<Label> labels) {
    public static final String METRIC_NAME = "__name__";

    public Labels {
        Objects.requireNonNull(labels, "labels");
        List<Label> sorted = new ArrayList<>(labels);
        sorted.sort(Comparator.comparing(Label::name));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).name().equals(sorted.get(i - 1).name())) {
                throw new IllegalArgumentException("duplicate label: " + sorted.get(i).name());
            }
        }
        labels = List.copyOf(sorted);
    }

    public static Labels of(String metric, String... nameValuePairs) {
        Objects.requireNonNull(metric, "metric");
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("odd number of name/value arguments");
        }
        List<Label> list = new ArrayList<>(nameValuePairs.length / 2 + 1);
        list.add(new Label(METRIC_NAME, metric));
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            list.add(new Label(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return new Labels(list);
    }

    public Optional<String> get(String name) {
        for (Label l : labels) {
            if (l.name().equals(name)) {
                return Optional.of(l.value());
            }
        }
        return Optional.empty();
    }

    public Optional<String> metric() {
        return get(METRIC_NAME);
    }

    // Каноническая строка: cpu_usage{core="0",host="web-1"}
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        metric().ifPresent(sb::append);
        sb.append('{');
        boolean first = true;
        for (Label l : labels) {
            if (l.name().equals(METRIC_NAME)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(l.name()).append("=\"")
                    .append(l.value().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.append('}').toString();
    }

    @Override
    public String toString() {
        return canonical();
    }
}

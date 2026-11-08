package org.tsdb.model;

import java.util.Objects;
import java.util.regex.Pattern;

// Фильтр по одному лейблу. Sealed, switch по Matcher проверяется компилятором на полноту.
public sealed interface Matcher {
    String name();

    record Eq(String name, String value) implements Matcher {
        public Eq {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    record NotEq(String name, String value) implements Matcher {
        public NotEq {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    // Совпадение со всем значением: шаблон оборачивается в ^(?:pattern)$ (host=~"web" не совпадает с web-1).
    // Синтаксис — java.util.regex. Невалидный шаблон — PatternSyntaxException (IllegalArgumentException) в конструкторе.
    // Конструктор компилирует только для проверки; для выполнения — один раз на запрос в MemInvertedIndex.select.
    record Regex(String name, String pattern) implements Matcher {
        public Regex {
            Objects.requireNonNull(name, "name");
            Pattern.compile(Objects.requireNonNull(pattern, "pattern"));
        }
    }

    // Отрицание Regex, та же семантика полного совпадения.
    record NotRegex(String name, String pattern) implements Matcher {
        public NotRegex {
            Objects.requireNonNull(name, "name");
            Pattern.compile(Objects.requireNonNull(pattern, "pattern"));
        }
    }
}

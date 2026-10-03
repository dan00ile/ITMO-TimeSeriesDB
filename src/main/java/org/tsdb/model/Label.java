package org.tsdb.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record Label(String name, String value) {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    public Label {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid label name: " + name);
        }
    }
}

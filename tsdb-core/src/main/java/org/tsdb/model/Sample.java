package org.tsdb.model;

// Точка серии. timestamp - миллисекунды UTC.
public record Sample(long timestamp, double value) {}

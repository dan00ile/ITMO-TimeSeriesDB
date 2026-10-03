package org.tsdb.model;

// Агрегатная функция запроса. {@code NONE} — сырые точки, без группировки и окон
public enum Aggregation { NONE, SUM, AVG, MIN, MAX, COUNT }

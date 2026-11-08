package org.tsdb.encoding;

/**
 * Однопроходный итератор по распакованным точкам.
 */
public interface SampleIterator {

    /**
     * Переходит к следующей точке.
     *
     * @return true, если следующая точка существует
     */
    boolean next();

    long timestamp();

    double value();
}
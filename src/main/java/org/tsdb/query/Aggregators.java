package org.tsdb.query;

// Реализации Aggregator. Создаются через Aggregator.of.
final class Aggregators {
    private Aggregators() {
    }

    static final class Sum implements Aggregator {
        private double sum;

        @Override
        public void add(double value) {
            sum += value;
        }

        @Override
        public double result() {
            return sum;
        }

        @Override
        public void reset() {
            sum = 0;
        }
    }

    static final class Avg implements Aggregator {
        private double sum;
        private long count;

        @Override
        public void add(double value) {
            sum += value;
            count++;
        }

        @Override
        public double result() {
            return count == 0 ? Double.NaN : sum / count;
        }

        @Override
        public void reset() {
            sum = 0;
            count = 0;
        }
    }

    static final class Min implements Aggregator {
        private double min = Double.NaN;
        private boolean empty = true;

        @Override
        public void add(double value) {
            min = empty ? value : Math.min(min, value);
            empty = false;
        }

        @Override
        public double result() {
            return min;
        }

        @Override
        public void reset() {
            min = Double.NaN;
            empty = true;
        }
    }

    static final class Max implements Aggregator {
        private double max = Double.NaN;
        private boolean empty = true;

        @Override
        public void add(double value) {
            max = empty ? value : Math.max(max, value);
            empty = false;
        }

        @Override
        public double result() {
            return max;
        }

        @Override
        public void reset() {
            max = Double.NaN;
            empty = true;
        }
    }

    static final class Count implements Aggregator {
        private long count;

        @Override
        public void add(double value) {
            count++;
        }

        @Override
        public double result() {
            return count;
        }

        @Override
        public void reset() {
            count = 0;
        }
    }
}

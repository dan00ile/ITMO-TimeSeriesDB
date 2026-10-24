package org.tsdb.index;

import java.util.Arrays;

// Postings поверх префикса long[]. Массив не меняется после создания (у MemInvertedIndex — только
// дописывается за пределами size), поэтому объект можно отдавать читателям без блокировок.
final class ArrayPostings implements Postings {
    static final ArrayPostings EMPTY = new ArrayPostings(new long[0], 0);

    private final long[] ids;
    private final int size;

    ArrayPostings(long[] ids, int size) {
        this.ids = ids;
        this.size = size;
    }

    @Override
    public PostingsIterator iterator() {
        return new PostingsIterator() {
            private int i = -1;

            @Override
            public boolean next() {
                return ++i < size;
            }

            @Override
            public long at() {
                return ids[i];
            }
        };
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public String toString() {
        return Arrays.toString(Arrays.copyOf(ids, size));
    }

    // Дописываемый буфер строго возрастающих id.
    static final class Builder {
        private long[] ids = new long[8];
        private int size;

        void add(long id) {
            if (size > 0 && id <= ids[size - 1]) {
                throw new IllegalArgumentException("ids must strictly increase: " + id + " after " + ids[size - 1]);
            }
            if (size == ids.length) {
                ids = Arrays.copyOf(ids, size * 2);
            }
            ids[size++] = id;
        }

        long last() {
            return size == 0 ? Long.MIN_VALUE : ids[size - 1];
        }

        // Снимок текущего содержимого. Дальнейшие add не меняют уже выданные элементы:
        // при росте создаётся новый массив, старый префикс остаётся прежним.
        ArrayPostings snapshot() {
            return size == 0 ? EMPTY : new ArrayPostings(ids, size);
        }
    }
}

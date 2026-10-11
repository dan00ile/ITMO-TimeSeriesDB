package org.tsdb.testing;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.tsdb.encoding.AppendableChunk;
import org.tsdb.encoding.Chunk;
import org.tsdb.encoding.ChunkCodec;
import org.tsdb.encoding.ChunkEncoding;
import org.tsdb.encoding.SampleIterator;
import org.tsdb.model.OutOfOrderSampleException;
import org.tsdb.model.Sample;

// Заглушка кодека для тестов head и движка, пока нет Gorilla: точки в ArrayList, байты — 16 на точку.
// encoding() формально GORILLA_XOR (другого значения в enum нет), формат при этом свой.
public final class ListChunkCodec implements ChunkCodec {
    private final int maxSamples;

    public ListChunkCodec(int maxSamples) {
        this.maxSamples = maxSamples;
    }

    @Override
    public AppendableChunk newAppendable() {
        return new ListChunk(new ArrayList<>(), maxSamples);
    }

    @Override
    public Chunk decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int n = buf.getInt();
        List<Sample> samples = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            samples.add(new Sample(buf.getLong(), buf.getDouble()));
        }
        return new ListChunk(List.copyOf(samples), n);
    }

    // Точки chunk'а списком — для ассертов.
    public static List<Sample> samples(Chunk chunk) {
        List<Sample> out = new ArrayList<>();
        SampleIterator it = chunk.iterator();
        while (it.next()) {
            out.add(new Sample(it.timestamp(), it.value()));
        }
        return out;
    }

    private static final class ListChunk implements AppendableChunk {
        private final List<Sample> samples;
        private final int maxSamples;

        ListChunk(List<Sample> samples, int maxSamples) {
            this.samples = samples;
            this.maxSamples = maxSamples;
        }

        @Override
        public ChunkEncoding encoding() {
            return ChunkEncoding.GORILLA_XOR;
        }

        @Override
        public int numSamples() {
            return samples.size();
        }

        @Override
        public long minTime() {
            return samples.isEmpty() ? Long.MAX_VALUE : samples.get(0).timestamp();
        }

        @Override
        public long maxTime() {
            return samples.isEmpty() ? Long.MIN_VALUE : samples.get(samples.size() - 1).timestamp();
        }

        @Override
        public byte[] bytes() {
            ByteBuffer buf = ByteBuffer.allocate(4 + 16 * samples.size());
            buf.putInt(samples.size());
            for (Sample s : samples) {
                buf.putLong(s.timestamp()).putDouble(s.value());
            }
            return buf.array();
        }

        @Override
        public SampleIterator iterator() {
            return new SampleIterator() {
                private int i = -1;

                @Override
                public boolean next() {
                    return ++i < samples.size();
                }

                @Override
                public long timestamp() {
                    return samples.get(i).timestamp();
                }

                @Override
                public double value() {
                    return samples.get(i).value();
                }
            };
        }

        @Override
        public void append(long timestamp, double value) {
            if (!samples.isEmpty() && timestamp <= maxTime()) {
                throw new OutOfOrderSampleException(-1, timestamp, maxTime());
            }
            samples.add(new Sample(timestamp, value));
        }

        @Override
        public boolean isFull() {
            return samples.size() >= maxSamples;
        }

        @Override
        public Chunk seal() {
            return new ListChunk(List.copyOf(samples), maxSamples);
        }
    }
}

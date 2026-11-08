package org.tsdb.testing;

import java.util.ArrayList;
import java.util.List;

import org.tsdb.encoding.Chunk;
import org.tsdb.encoding.ChunkEncoding;
import org.tsdb.encoding.SampleIterator;
import org.tsdb.model.Sample;

// Chunk без сжатия: точки просто лежат в списке. Пустым не бывает.
public record ListChunk(List<Sample> samples) implements Chunk {

    public ListChunk {
        samples = List.copyOf(samples);
    }

    public static List<Sample> samplesOf(Chunk chunk) {
        List<Sample> result = new ArrayList<>();
        SampleIterator it = chunk.iterator();
        while (it.next()) {
            result.add(new Sample(it.timestamp(), it.value()));
        }
        return result;
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
        return samples.get(0).timestamp();
    }

    @Override
    public long maxTime() {
        return samples.get(samples.size() - 1).timestamp();
    }

    @Override
    public byte[] bytes() {
        return new byte[0];
    }

    @Override
    public SampleIterator iterator() {
        return new SampleIterator() {
            private int i = -1;

            @Override
            public boolean next() {
                i++;
                return i < samples.size();
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
}

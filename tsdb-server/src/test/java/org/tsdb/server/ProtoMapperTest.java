package org.tsdb.server;

import org.junit.jupiter.api.Test;
import org.tsdb.model.Sample;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.SeriesResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtoMapperTest {

    @Test
    void sampleRoundTrip() {
        Sample original = new Sample(1000L, 0.75);

        org.tsdb.proto.Sample proto =
                ProtoMapper.toProto(original);

        Sample restored = ProtoMapper.toModel(proto);

        assertEquals(original, restored);
    }

    @Test
    void labelsRoundTrip() {
        Labels original = Labels.of(
                "cpu_usage",
                "host", "web-1",
                "core", "0"
        );

        var proto = ProtoMapper.toProtoLabels(original);
        Labels restored = ProtoMapper.toModelLabels(proto);

        assertEquals(original, restored);
    }

    @Test
    void matchersRoundTrip() {
        List<Matcher> matchers = List.of(
                new Matcher.Eq("host", "web-1"),
                new Matcher.NotEq("core", "1"),
                new Matcher.Regex("host", "web-.*"),
                new Matcher.NotRegex("host", "db-.*")
        );

        for (Matcher original : matchers) {
            var proto = ProtoMapper.toProto(original);
            Matcher restored = ProtoMapper.toModel(proto);

            assertEquals(original, restored);
        }
    }

    @Test
    void aggregationRoundTrip() {
        for (Aggregation original : Aggregation.values()) {
            var proto = ProtoMapper.toProto(original);
            Aggregation restored = ProtoMapper.toModel(proto);

            assertEquals(original, restored);
        }
    }

    @Test
    void queryRequestConversion() {
        var request = org.tsdb.proto.QueryRequest.newBuilder()
                .addMatchers(
                        org.tsdb.proto.Matcher.newBuilder()
                                .setType(org.tsdb.proto.Matcher.Type.EQ)
                                .setName("__name__")
                                .setValue("cpu_usage")
                                .build()
                )
                .setStartMs(1000)
                .setEndMs(5000)
                .setAggregation(org.tsdb.proto.Aggregation.AVG)
                .addGroupBy("host")
                .setStepMs(1000)
                .build();

        Query result = ProtoMapper.toModel(request);

        assertEquals(1000, result.start());
        assertEquals(5000, result.end());
        assertEquals(Aggregation.AVG, result.aggregation());
        assertEquals(List.of("host"), result.groupBy());
        assertEquals(1000, result.step());
        assertEquals(
                List.of(new Matcher.Eq("__name__", "cpu_usage")),
                result.matchers()
        );
    }

    @Test
    void queryResultConversion() {
        Labels labels = Labels.of("cpu_usage", "host", "web-1");

        QueryResult original = new QueryResult(List.of(
                new SeriesResult(
                        labels,
                        List.of(
                                new Sample(1000, 0.75),
                                new Sample(2000, 0.82)
                        )
                )
        ));

        var response = ProtoMapper.toProto(original);

        assertEquals(1, response.getSeriesCount());

        var series = response.getSeries(0);

        assertEquals(labels,
                ProtoMapper.toModelLabels(series.getLabelsList()));

        assertEquals(
                original.series().get(0).samples(),
                series.getSamplesList().stream()
                        .map(ProtoMapper::toModel)
                        .toList()
        );
    }
}

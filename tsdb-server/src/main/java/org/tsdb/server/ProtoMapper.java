
package org.tsdb.server;

import org.tsdb.model.Aggregation;
import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.model.SeriesResult;

import java.util.ArrayList;
import java.util.List;

public final class ProtoMapper {

    private ProtoMapper() {
    }

    // Proto Sample -> DB Sample
    public static Sample toModel(org.tsdb.proto.Sample sample) {
        return new Sample(
                sample.getTimestampMs(),
                sample.getValue()
        );
    }

    // DB Sample -> Proto Sample
    public static org.tsdb.proto.Sample toProto(Sample sample) {
        return org.tsdb.proto.Sample.newBuilder()
                .setTimestampMs(sample.timestamp())
                .setValue(sample.value())
                .build();
    }

    // Proto labels -> DB Labels
    public static Labels toModelLabels(
            List<org.tsdb.proto.Label> protoLabels) {

        List<Label> labels = new ArrayList<>();

        for (org.tsdb.proto.Label label : protoLabels) {
            labels.add(new Label(
                    label.getName(),
                    label.getValue()
            ));
        }

        return new Labels(labels);
    }

    // DB Labels -> Proto labels
    public static List<org.tsdb.proto.Label> toProtoLabels(
            Labels labels) {

        List<org.tsdb.proto.Label> result = new ArrayList<>();

        for (Label label : labels.labels()) {
            result.add(org.tsdb.proto.Label.newBuilder()
                    .setName(label.name())
                    .setValue(label.value())
                    .build());
        }

        return result;
    }

    // Proto Matcher -> DB Matcher
    public static Matcher toModel(org.tsdb.proto.Matcher matcher) {
        return switch (matcher.getType()) {
            case EQ -> new Matcher.Eq(
                    matcher.getName(), matcher.getValue());
            case NEQ -> new Matcher.NotEq(
                    matcher.getName(), matcher.getValue());
            case RE -> new Matcher.Regex(
                    matcher.getName(), matcher.getValue());
            case NRE -> new Matcher.NotRegex(
                    matcher.getName(), matcher.getValue());
            case UNRECOGNIZED ->
                    throw new IllegalArgumentException(
                            "Unknown matcher type: " + matcher.getType());
        };
    }

    // DB Matcher -> Proto Matcher
    public static org.tsdb.proto.Matcher toProto(Matcher matcher) {
        org.tsdb.proto.Matcher.Type type;
        String value;

        if (matcher instanceof Matcher.Eq m) {
            type = org.tsdb.proto.Matcher.Type.EQ;
            value = m.value();
        } else if (matcher instanceof Matcher.NotEq m) {
            type = org.tsdb.proto.Matcher.Type.NEQ;
            value = m.value();
        } else if (matcher instanceof Matcher.Regex m) {
            type = org.tsdb.proto.Matcher.Type.RE;
            value = m.pattern();
        } else if (matcher instanceof Matcher.NotRegex m) {
            type = org.tsdb.proto.Matcher.Type.NRE;
            value = m.pattern();
        } else {
            throw new IllegalArgumentException(
                    "Unknown matcher: " + matcher);
        }

        return org.tsdb.proto.Matcher.newBuilder()
                .setType(type)
                .setName(matcher.name())
                .setValue(value)
                .build();
    }

    // Proto Aggregation -> DB Aggregation
    public static Aggregation toModel(
            org.tsdb.proto.Aggregation aggregation) {

        return switch (aggregation) {
            case NONE -> Aggregation.NONE;
            case SUM -> Aggregation.SUM;
            case AVG -> Aggregation.AVG;
            case MIN -> Aggregation.MIN;
            case MAX -> Aggregation.MAX;
            case COUNT -> Aggregation.COUNT;
            case UNRECOGNIZED ->
                    throw new IllegalArgumentException(
                            "Unknown aggregation: " + aggregation);
        };
    }

    // DB Aggregation -> Proto Aggregation
    public static org.tsdb.proto.Aggregation toProto(
            Aggregation aggregation) {

        return switch (aggregation) {
            case NONE -> org.tsdb.proto.Aggregation.NONE;
            case SUM -> org.tsdb.proto.Aggregation.SUM;
            case AVG -> org.tsdb.proto.Aggregation.AVG;
            case MIN -> org.tsdb.proto.Aggregation.MIN;
            case MAX -> org.tsdb.proto.Aggregation.MAX;
            case COUNT -> org.tsdb.proto.Aggregation.COUNT;
        };
    }

    // Proto QueryRequest -> DB Query
    public static Query toModel(org.tsdb.proto.QueryRequest request) {
        List<Matcher> matchers = request.getMatchersList()
                .stream()
                .map(ProtoMapper::toModel)
                .toList();

        return new Query(
                matchers,
                request.getStartMs(),
                request.getEndMs(),
                toModel(request.getAggregation()),
                request.getGroupByList(),
                request.getStepMs()
        );
    }

    // DB QueryResult -> Proto QueryResponse
    public static org.tsdb.proto.QueryResponse toProto(
            QueryResult result) {

        org.tsdb.proto.QueryResponse.Builder response =
                org.tsdb.proto.QueryResponse.newBuilder();

        for (SeriesResult series : result.series()) {
            org.tsdb.proto.TimeSeries.Builder protoSeries =
                    org.tsdb.proto.TimeSeries.newBuilder();

            protoSeries.addAllLabels(toProtoLabels(series.labels()));

            for (Sample sample : series.samples()) {
                protoSeries.addSamples(toProto(sample));
            }

            response.addSeries(protoSeries.build());
        }

        return response.build();
    }
}


package org.tsdb.server;

import io.grpc.stub.StreamObserver;

import org.tsdb.db.TimeSeriesDB;
import org.tsdb.model.Labels;
import org.tsdb.model.OutOfOrderSampleException;

import org.tsdb.proto.TimeSeries;
import org.tsdb.proto.TimeSeriesServiceGrpc;
import org.tsdb.proto.WriteRequest;
import org.tsdb.proto.WriteResponse;

public final class TimeSeriesServiceImpl
        extends TimeSeriesServiceGrpc.TimeSeriesServiceImplBase {

    private final TimeSeriesDB db;

    public TimeSeriesServiceImpl(TimeSeriesDB db) {
        this.db = java.util.Objects.requireNonNull(db);
    }


    private void writeAll(WriteRequest request, WriteCounters counters) {

        for (TimeSeries series : request.getSeriesList()) {

            Labels labels =
                    ProtoMapper.toModelLabels(series.getLabelsList());

            if (labels.metric().isEmpty()
                    || labels.metric().orElseThrow().isBlank()) {
                throw new IllegalArgumentException(
                        "Missing metric name (__name__)");
            }

            for (org.tsdb.proto.Sample sample : series.getSamplesList()) {
                try {
                    db.append(
                            labels,
                            sample.getTimestampMs(),
                            sample.getValue()
                    );

                    counters.accepted();

                } catch (OutOfOrderSampleException e) {
                    counters.rejected(e.getMessage());
                }
            }
        }
    }



    @Override
    public void write(
            WriteRequest request,
            StreamObserver<WriteResponse> responseObserver) {

        try {
            WriteCounters counters = new WriteCounters();

            writeAll(request, counters);

            responseObserver.onNext(counters.toResponse());
            responseObserver.onCompleted();

        } catch (RuntimeException e) {
            responseObserver.onError(GrpcErrors.toStatus(e));
        }
    }



    @Override
    public StreamObserver<WriteRequest> writeStream(
            StreamObserver<WriteResponse> responseObserver) {

        WriteCounters counters = new WriteCounters();

        return new StreamObserver<>() {

            private boolean terminated;

            @Override
            public void onNext(WriteRequest request) {
                if (terminated) {
                    return;
                }

                try {
                    writeAll(request, counters);
                } catch (RuntimeException e) {
                    terminated = true;
                    responseObserver.onError(GrpcErrors.toStatus(e));
                }
            }

            @Override
            public void onError(Throwable error) {
                terminated = true;
                // Клиент прервал поток.
                // Уже записанные точки остаются в БД.
            }

            @Override
            public void onCompleted() {
                if (terminated) {
                    return;
                }

                terminated = true;
                responseObserver.onNext(counters.toResponse());
                responseObserver.onCompleted();
            }
        };
    }


    @Override
    public void query(
            org.tsdb.proto.QueryRequest request,
            StreamObserver<org.tsdb.proto.QueryResponse> responseObserver) {

        try {
            org.tsdb.model.Query query = ProtoMapper.toModel(request);

            org.tsdb.model.QueryResult result = db.query(query);

            org.tsdb.proto.QueryResponse response =
                    ProtoMapper.toProto(result);

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (RuntimeException e) {
            responseObserver.onError(GrpcErrors.toStatus(e));
        }
    }


    @Override
    public void labelNames(
            org.tsdb.proto.LabelNamesRequest request,
            StreamObserver<org.tsdb.proto.LabelNamesResponse> responseObserver) {

        try {
            var names = db.labelNames();

            var response = org.tsdb.proto.LabelNamesResponse.newBuilder()
                    .addAllNames(names)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (RuntimeException e) {
            responseObserver.onError(GrpcErrors.toStatus(e));
        }
    }

    @Override
    public void labelValues(
            org.tsdb.proto.LabelValuesRequest request,
            StreamObserver<org.tsdb.proto.LabelValuesResponse> responseObserver) {

        try {
            var values = db.labelValues(request.getName());

            var response = org.tsdb.proto.LabelValuesResponse.newBuilder()
                    .addAllValues(values)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (RuntimeException e) {
            responseObserver.onError(GrpcErrors.toStatus(e));
        }
    }

    @Override
    public void flush(
            org.tsdb.proto.Empty request,
            StreamObserver<org.tsdb.proto.Empty> responseObserver) {

        try {
            db.flush();

            responseObserver.onNext(
                    org.tsdb.proto.Empty.getDefaultInstance()
            );
            responseObserver.onCompleted();

        } catch (RuntimeException e) {
            responseObserver.onError(GrpcErrors.toStatus(e));
        }
    }


    @Override
    public void getStats(
            org.tsdb.proto.Empty request,
            StreamObserver<org.tsdb.proto.Stats> responseObserver) {

        try {
            org.tsdb.db.DbStats stats = db.stats();

            org.tsdb.proto.Stats response =
                    org.tsdb.proto.Stats.newBuilder()
                            .setSeries(stats.numSeries())
                            .setBlocks(stats.numBlocks())
                            .setHeadSamples(stats.headSamples())
                            .setDiskBytes(stats.diskBytes())
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (RuntimeException e) {
            responseObserver.onError(GrpcErrors.toStatus(e));
        }
    }

}

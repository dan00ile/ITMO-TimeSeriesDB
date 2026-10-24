package org.tsdb.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;

// Проверка сборки: код от protoc совместим с runtime protobuf-java, сервис описан полностью.
class TsdbProtoTest {

    @Test
    void writeRequestSurvivesRoundTrip() throws Exception {
        WriteRequest request = WriteRequest.newBuilder()
                .addSeries(TimeSeries.newBuilder()
                        .addLabels(Label.newBuilder().setName("__name__").setValue("cpu_usage"))
                        .addLabels(Label.newBuilder().setName("host").setValue("web-1"))
                        .addSamples(Sample.newBuilder().setTimestampMs(1_000).setValue(0.42)))
                .build();

        WriteRequest parsed = WriteRequest.parseFrom(request.toByteArray());

        assertEquals(request, parsed);
        assertEquals(0.42, parsed.getSeries(0).getSamples(0).getValue());
    }

    @Test
    void defaultsAreZeroValues() {
        QueryRequest request = QueryRequest.getDefaultInstance();
        assertEquals(Aggregation.NONE, request.getAggregation());
        assertEquals(0, request.getStepMs());
        assertEquals(Matcher.Type.EQ, Matcher.getDefaultInstance().getType());
    }

    @Test
    void serviceHasAllMethods() {
        ServiceDescriptor service = TimeSeriesServiceGrpc.getServiceDescriptor();
        Set<String> methods = service.getMethods().stream()
                .map(MethodDescriptor::getBareMethodName)
                .collect(Collectors.toSet());

        assertEquals("tsdb.v1.TimeSeriesService", service.getName());
        assertEquals(Set.of("Write", "WriteStream", "Query", "LabelNames", "LabelValues", "Flush", "GetStats"),
                methods);
        assertEquals(MethodDescriptor.MethodType.CLIENT_STREAMING,
                TimeSeriesServiceGrpc.getWriteStreamMethod().getType());
    }
}

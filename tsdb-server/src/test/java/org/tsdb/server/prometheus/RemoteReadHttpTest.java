package org.tsdb.server.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.db.DbOptions;
import org.tsdb.db.TimeSeriesDB;
import org.tsdb.model.Labels;
import org.tsdb.proto.prometheus.Label;
import org.tsdb.proto.prometheus.LabelMatcher;
import org.tsdb.proto.prometheus.Query;
import org.tsdb.proto.prometheus.ReadRequest;
import org.tsdb.proto.prometheus.ReadResponse;
import org.tsdb.proto.prometheus.Sample;
import org.tsdb.proto.prometheus.TimeSeries;

// Настоящий HTTP-запрос в формате Prometheus remote read к серверу поверх настоящей БД.
class RemoteReadHttpTest {
    @TempDir
    Path dir;

    private TimeSeriesDB db;
    private PrometheusHttpServer http;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        db = TimeSeriesDB.open(DbOptions.builder(dir).compactionIntervalMs(0).build());
        http = PrometheusHttpServer.start(0, db);
        db.append(Labels.of("cpu_usage", "host", "web-1"), 1_000, 0.5);
        db.append(Labels.of("cpu_usage", "host", "web-1"), 2_000, 0.6);
        db.append(Labels.of("cpu_usage", "host", "db-1"), 1_000, 0.9);
        db.append(Labels.of("mem_usage", "host", "web-1"), 1_000, 42);
    }

    @AfterEach
    void stop() {
        http.close();
        db.close();
    }

    private static LabelMatcher m(LabelMatcher.Type type, String name, String value) {
        return LabelMatcher.newBuilder().setType(type).setName(name).setValue(value).build();
    }

    private HttpResponse<byte[]> post(byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + http.port() + "/api/v1/read"))
                        .header("Content-Type", "application/x-protobuf")
                        .header("Content-Encoding", "snappy")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void readReturnsSeriesPerQueryInOrder() throws Exception {
        ReadRequest request = ReadRequest.newBuilder()
                .addQueries(Query.newBuilder().setStartTimestampMs(0).setEndTimestampMs(5_000)
                        .addMatchers(m(LabelMatcher.Type.EQ, "__name__", "cpu_usage"))
                        .addMatchers(m(LabelMatcher.Type.RE, "host", "web-.*")))
                .addQueries(Query.newBuilder().setStartTimestampMs(0).setEndTimestampMs(5_000)
                        .addMatchers(m(LabelMatcher.Type.EQ, "__name__", "cpu_usage"))
                        .addMatchers(m(LabelMatcher.Type.NEQ, "host", "web-1")))
                .build();

        HttpResponse<byte[]> response = post(Snappy.compress(request.toByteArray()));

        assertEquals(200, response.statusCode());
        assertEquals("snappy", response.headers().firstValue("Content-Encoding").orElseThrow());
        ReadResponse read = ReadResponse.parseFrom(Snappy.decompress(response.body(), 1 << 20));
        assertEquals(2, read.getResultsCount());

        TimeSeries web1 = read.getResults(0).getTimeseries(0);
        assertEquals(1, read.getResults(0).getTimeseriesCount());
        assertEquals(List.of(label("__name__", "cpu_usage"), label("host", "web-1")), web1.getLabelsList());
        assertEquals(List.of(sample(1_000, 0.5), sample(2_000, 0.6)), web1.getSamplesList());

        assertEquals(List.of(label("__name__", "cpu_usage"), label("host", "db-1")),
                read.getResults(1).getTimeseries(0).getLabelsList());
    }

    @Test
    void queryWithoutEqualityMatcherIsBadRequest() throws Exception {
        ReadRequest request = ReadRequest.newBuilder()
                .addQueries(Query.newBuilder().setEndTimestampMs(5_000)
                        .addMatchers(m(LabelMatcher.Type.RE, "__name__", "cpu.*")))
                .build();
        HttpResponse<byte[]> response = post(Snappy.compress(request.toByteArray()));
        assertEquals(400, response.statusCode());
        assertTrue(new String(response.body()).contains("Eq"), new String(response.body()));
    }

    @Test
    void garbageAndWrongMethodRejected() throws Exception {
        assertEquals(400, post(new byte[] {0x05, 0x08, 'a'}).statusCode());
        HttpResponse<byte[]> get = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + http.port() + "/api/v1/read")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(405, get.statusCode());
    }

    private static Label label(String name, String value) {
        return Label.newBuilder().setName(name).setValue(value).build();
    }

    private static Sample sample(long ts, double value) {
        return Sample.newBuilder().setTimestamp(ts).setValue(value).build();
    }
}

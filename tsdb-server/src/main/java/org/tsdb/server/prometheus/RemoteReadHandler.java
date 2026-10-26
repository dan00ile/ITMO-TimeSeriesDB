package org.tsdb.server.prometheus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tsdb.db.TimeSeriesDB;
import org.tsdb.model.Label;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.model.SeriesResult;
import org.tsdb.proto.prometheus.LabelMatcher;
import org.tsdb.proto.prometheus.ReadRequest;
import org.tsdb.proto.prometheus.ReadResponse;
import org.tsdb.proto.prometheus.TimeSeries;

// POST /api/v1/read — remote read Prometheus. Prometheus присылает matchers + диапазон, мы отдаём
// сырые точки, а PromQL (rate, avg by, …) он считает сам. Так Grafana с обычным источником
// «Prometheus» видит данные нашей БД. Тело запроса и ответа — protobuf, сжатый snappy.
public final class RemoteReadHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteReadHandler.class);
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;

    private final TimeSeriesDB db;

    public RemoteReadHandler(TimeSeriesDB db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendText(exchange, 405, "use POST");
                return;
            }
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (body.length > MAX_BODY_BYTES) {
                sendText(exchange, 413, "request body too large");
                return;
            }
            byte[] response;
            try {
                ReadRequest request = ReadRequest.parseFrom(Snappy.decompress(body, MAX_UNCOMPRESSED_BYTES));
                response = Snappy.compress(read(request).toByteArray());
            } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
                // битый snappy/protobuf, запрос без единого «=», end < start, плохой regex
                sendText(exchange, 400, e.getMessage());
                return;
            } catch (RuntimeException e) {
                LOG.error("remote read failed", e);
                sendText(exchange, 500, "internal error: " + e);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.getResponseHeaders().set("Content-Encoding", "snappy");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        }
    }

    // По одному QueryResult на каждый Query, в том же порядке.
    ReadResponse read(ReadRequest request) {
        ReadResponse.Builder response = ReadResponse.newBuilder();
        for (org.tsdb.proto.prometheus.Query q : request.getQueriesList()) {
            List<Matcher> matchers = new ArrayList<>(q.getMatchersCount());
            for (LabelMatcher m : q.getMatchersList()) {
                matchers.add(toModel(m));
            }
            QueryResult result = db.query(Query.range(matchers, q.getStartTimestampMs(), q.getEndTimestampMs()));
            org.tsdb.proto.prometheus.QueryResult.Builder out = org.tsdb.proto.prometheus.QueryResult.newBuilder();
            for (SeriesResult s : result.series()) {
                out.addTimeseries(toProto(s));
            }
            response.addResults(out);
        }
        return response.build();
    }

    // Семантика совпадает: regex Prometheus тоже якорится целиком, отсутствующий лейбл = "".
    static Matcher toModel(LabelMatcher m) {
        return switch (m.getType()) {
            case EQ -> new Matcher.Eq(m.getName(), m.getValue());
            case NEQ -> new Matcher.NotEq(m.getName(), m.getValue());
            case RE -> new Matcher.Regex(m.getName(), m.getValue());
            case NRE -> new Matcher.NotRegex(m.getName(), m.getValue());
            case UNRECOGNIZED -> throw new IllegalArgumentException("unknown matcher type " + m.getTypeValue());
        };
    }

    // Labels уже отсортированы по имени — тот же порядок, что ждёт Prometheus.
    static TimeSeries toProto(SeriesResult s) {
        TimeSeries.Builder ts = TimeSeries.newBuilder();
        for (Label l : s.labels().labels()) {
            ts.addLabels(org.tsdb.proto.prometheus.Label.newBuilder().setName(l.name()).setValue(l.value()));
        }
        for (Sample p : s.samples()) {
            ts.addSamples(org.tsdb.proto.prometheus.Sample.newBuilder()
                    .setTimestamp(p.timestamp()).setValue(p.value()));
        }
        return ts.build();
    }

    private static void sendText(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = String.valueOf(message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

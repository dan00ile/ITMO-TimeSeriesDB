package org.tsdb.server.prometheus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import org.tsdb.db.TimeSeriesDB;

// HTTP рядом с gRPC — только для Prometheus remote read (а через него — Grafana).
public final class PrometheusHttpServer implements AutoCloseable {
    public static final String READ_PATH = "/api/v1/read";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private PrometheusHttpServer(HttpServer server) {
        this.server = server;
    }

    public static PrometheusHttpServer start(int port, TimeSeriesDB db) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            PrometheusHttpServer result = new PrometheusHttpServer(server);
            server.createContext(READ_PATH, new RemoteReadHandler(db));
            server.setExecutor(result.executor);
            server.start();
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start HTTP server on port " + port, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // Даём текущим запросам до 1 с, потом закрываем.
    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
    }
}

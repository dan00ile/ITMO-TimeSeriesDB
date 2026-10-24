package org.tsdb.server;

import java.io.IOException;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tsdb.db.TimeSeriesDB;

// Процесс сервера: конфиг из окружения, БД, gRPC-сервер на TSDB_PORT.
// TimeSeriesServiceImpl, health, reflection, shutdown hook и interceptor добавляет A.
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ServerConfig cfg = ServerConfig.fromEnv();
        TimeSeriesDB db = TimeSeriesDB.open(cfg.toDbOptions());

        Server server = ServerBuilder.forPort(cfg.port())
                .maxInboundMessageSize(MAX_MESSAGE_BYTES)
                .build()
                .start();
        LOG.info("tsdb-server started on port {}, {}", server.getPort(), cfg);

        server.awaitTermination();
    }
}

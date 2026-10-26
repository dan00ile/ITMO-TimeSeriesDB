
package org.tsdb.server;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.health.v1.HealthCheckResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tsdb.db.TimeSeriesDB;

public final class Main {

    private static final Logger LOG =
            LoggerFactory.getLogger(Main.class);

    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private Main() {
    }

    public static void main(String[] args)
            throws IOException, InterruptedException {

        // Загружаем конфигурацию сервера
        ServerConfig cfg = ServerConfig.fromEnv();

        // Открываем базу данных
        TimeSeriesDB db = TimeSeriesDB.open(cfg.toDbOptions());

        // Создаём сервис проверки состояния сервера
        HealthStatusManager health = new HealthStatusManager();

        // Создаём и запускаем gRPC-сервер
        Server server = ServerBuilder.forPort(cfg.port())
                .addService(new TimeSeriesServiceImpl(db))
                .addService(health.getHealthService())
                .addService(ProtoReflectionService.newInstance())
                .intercept(new SlowRequestInterceptor())
                .maxInboundMessageSize(MAX_MESSAGE_BYTES)
                .build()
                .start();

        // Сервер готов принимать запросы
        health.setStatus(
                "",
                HealthCheckResponse.ServingStatus.SERVING
        );

        LOG.info("tsdb-server started on port {}, {}",
                server.getPort(), cfg);

        // Обработка остановки приложения
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            LOG.info("Shutting down tsdb-server...");

            // Сообщаем, что сервер прекращает работу
            health.enterTerminalState();

            // Перестаём принимать новые запросы
            server.shutdown();

            try {
                // Даём текущим запросам завершиться
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                // Закрываем БД и синхронизируем WAL
                db.close();
            }

            LOG.info("tsdb-server stopped");

        }, "tsdb-shutdown"));

        // Основной поток ожидает завершения сервера
        server.awaitTermination();
    }
}

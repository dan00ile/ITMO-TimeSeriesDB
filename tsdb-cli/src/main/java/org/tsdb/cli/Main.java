package org.tsdb.cli;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.tsdb.proto.TimeSeriesServiceGrpc;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

// tsdb-cli --addr localhost:2222 <команда>. Каждая команда открывает канал и закрывает его в конце.
@Command(name = "tsdb-cli",
        mixinStandardHelpOptions = true,
        version = "tsdb-cli 0.1",
        description = "Клиент TimeSeriesDB по gRPC.",
        subcommands = {
                WriteCommand.class, QueryCommand.class, LoadCommand.class,
                StatsCommand.class, LabelsCommand.class, FlushCommand.class,
                CommandLine.HelpCommand.class})
public final class Main implements Runnable {

    // Тесты подменяют на in-process канал.
    static Function<String, ManagedChannel> channels =
            addr -> ManagedChannelBuilder.forTarget(addr).usePlaintext().build();

    @Option(names = "--addr", defaultValue = "${env:TSDB_ADDR:-localhost:2222}",
            description = "Адрес сервера host:port (по умолчанию $TSDB_ADDR или ${DEFAULT-VALUE}).")
    String addr;

    @Option(names = "--timeout", defaultValue = "30s",
            description = "Дедлайн одного запроса (кроме load), по умолчанию ${DEFAULT-VALUE}.")
    String timeout;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    ManagedChannel channel() {
        return channels.apply(addr);
    }

    // Дедлайн у stub'а абсолютный — создаём stub на каждый вызов.
    TimeSeriesServiceGrpc.TimeSeriesServiceBlockingStub blocking(ManagedChannel channel) {
        return TimeSeriesServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(TimeArgs.durationMs(timeout), TimeUnit.MILLISECONDS);
    }

    static void close(ManagedChannel channel) {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Ошибки сервера и плохие аргументы — одной строкой, без стектрейса. Код выхода 2.
    static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new Main());
        cli.setCaseInsensitiveEnumValuesAllowed(true);
        cli.setExecutionExceptionHandler((e, cmd, parsed) -> {
            PrintWriter err = cmd.getErr();
            if (e instanceof StatusRuntimeException s) {
                err.println("error: " + s.getStatus().getCode() + ": " + s.getStatus().getDescription());
            } else if (e instanceof IllegalArgumentException) {
                err.println("error: " + e.getMessage());
            } else {
                throw e;
            }
            err.flush();
            return 2;
        });
        return cli;
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}

package org.tsdb.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

import io.grpc.ManagedChannel;
import org.tsdb.model.Labels;
import org.tsdb.model.SelectorParser;
import org.tsdb.proto.TimeSeries;
import org.tsdb.proto.WriteRequest;
import org.tsdb.proto.WriteResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

// tsdb-cli write 'cpu_usage{host="web-1"}' 0.42 [--ts -1m]
@Command(name = "write", description = "Записать одну точку. Код выхода 1, если точку отклонили (out-of-order).")
final class WriteCommand implements Callable<Integer> {
    @ParentCommand
    Main root;

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "SERIES", description = "Серия: cpu_usage{host=\"web-1\"} (только '=').")
    String series;

    @Parameters(index = "1", paramLabel = "VALUE", description = "Значение.")
    double value;

    @Option(names = "--ts", defaultValue = "now",
            description = "Время точки: now, -1m, epoch ms или ISO-8601 (по умолчанию ${DEFAULT-VALUE}).")
    String ts;

    @Override
    public Integer call() {
        Labels labels = SelectorParser.parseLabels(series);
        long timestamp = TimeArgs.timeMs(ts, System.currentTimeMillis());
        WriteRequest request = WriteRequest.newBuilder()
                .addSeries(TimeSeries.newBuilder()
                        .addAllLabels(ProtoConvert.labels(labels))
                        .addSamples(org.tsdb.proto.Sample.newBuilder().setTimestampMs(timestamp).setValue(value)))
                .build();

        ManagedChannel channel = root.channel();
        try {
            WriteResponse response = root.blocking(channel).write(request);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("accepted %d, rejected %d%n", response.getAccepted(), response.getRejected());
            response.getErrorsList().forEach(e -> out.println("  " + e));
            out.flush();
            return response.getRejected() > 0 ? 1 : 0;
        } finally {
            Main.close(channel);
        }
    }
}

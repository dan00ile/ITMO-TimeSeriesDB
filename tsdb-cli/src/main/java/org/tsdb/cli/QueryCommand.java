package org.tsdb.cli;

import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import io.grpc.ManagedChannel;
import org.tsdb.model.Aggregation;
import org.tsdb.model.SelectorParser;
import org.tsdb.proto.QueryRequest;
import org.tsdb.proto.QueryResponse;
import org.tsdb.proto.TimeSeries;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

// tsdb-cli query 'cpu_usage{host=~"web-.*"}' --start -1h --agg avg --by host --step 1m
@Command(name = "query", description = "Выбрать серии по селектору и диапазону, опционально агрегировать.")
final class QueryCommand implements Callable<Integer> {
    // 0.5362499999999998 → 0.53625; NaN и Infinity — как Double.toString
    private static final DecimalFormat VALUE = new DecimalFormat("0.######", DecimalFormatSymbols.getInstance(Locale.ROOT));

    @ParentCommand
    Main root;

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "SELECTOR",
            description = "Селектор: cpu_usage{host=~\"web-.*\",core!=\"1\"}.")
    String selector;

    @Option(names = "--start", defaultValue = "-1h", description = "Начало (включительно), по умолчанию ${DEFAULT-VALUE}.")
    String start;

    @Option(names = "--end", defaultValue = "now", description = "Конец (включительно), по умолчанию ${DEFAULT-VALUE}.")
    String end;

    @Option(names = "--agg", defaultValue = "none",
            description = "Агрегация: ${COMPLETION-CANDIDATES} (по умолчанию ${DEFAULT-VALUE}).")
    Aggregation aggregation;

    @Option(names = "--by", split = ",", description = "Лейблы группировки через запятую: host,core.")
    List<String> groupBy = List.of();

    @Option(names = "--step", defaultValue = "0", description = "Окно агрегации: 1m, 15m; 0 — одно окно на диапазон.")
    String step;

    @Override
    public Integer call() {
        long now = System.currentTimeMillis();
        QueryRequest.Builder request = QueryRequest.newBuilder()
                .setStartMs(TimeArgs.timeMs(start, now))
                .setEndMs(TimeArgs.timeMs(end, now))
                .setAggregation(org.tsdb.proto.Aggregation.valueOf(aggregation.name()))
                .addAllGroupBy(groupBy)
                .setStepMs(TimeArgs.durationMs(step));
        SelectorParser.parse(selector).forEach(m -> request.addMatchers(ProtoConvert.matcher(m)));

        ManagedChannel channel = root.channel();
        try {
            print(root.blocking(channel).query(request.build()), spec.commandLine().getOut());
            return 0;
        } finally {
            Main.close(channel);
        }
    }

    static void print(QueryResponse response, PrintWriter out) {
        long points = 0;
        for (TimeSeries series : response.getSeriesList()) {
            out.printf("%s  (%d points)%n", ProtoConvert.labels(series.getLabelsList()).canonical(),
                    series.getSamplesCount());
            for (org.tsdb.proto.Sample s : series.getSamplesList()) {
                out.printf("  %-24s %s%n", Instant.ofEpochMilli(s.getTimestampMs()), format(s.getValue()));
            }
            points += series.getSamplesCount();
        }
        out.printf("%d series, %d points%n", response.getSeriesCount(), points);
        out.flush();
    }

    static String format(double v) {
        return Double.isFinite(v) ? VALUE.format(v) : Double.toString(v);
    }
}

package org.tsdb.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;

import io.grpc.ManagedChannel;
import org.tsdb.proto.LabelNamesRequest;
import org.tsdb.proto.LabelValuesRequest;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

// tsdb-cli labels        — имена лейблов
// tsdb-cli labels host   — значения лейбла host
@Command(name = "labels", description = "Имена лейблов или значения одного лейбла.")
final class LabelsCommand implements Callable<Integer> {
    @ParentCommand
    Main root;

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "NAME", description = "Лейбл, чьи значения показать.")
    String name;

    @Override
    public Integer call() {
        ManagedChannel channel = root.channel();
        try {
            var stub = root.blocking(channel);
            List<String> items = name == null
                    ? stub.labelNames(LabelNamesRequest.getDefaultInstance()).getNamesList()
                    : stub.labelValues(LabelValuesRequest.newBuilder().setName(name).build()).getValuesList();
            PrintWriter out = spec.commandLine().getOut();
            items.stream().sorted().forEach(out::println);
            out.flush();
            return 0;
        } finally {
            Main.close(channel);
        }
    }
}

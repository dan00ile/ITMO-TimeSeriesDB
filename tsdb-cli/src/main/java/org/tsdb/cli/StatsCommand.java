package org.tsdb.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

import io.grpc.ManagedChannel;
import org.tsdb.proto.Empty;
import org.tsdb.proto.Stats;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "stats", description = "Серии, блоки, точки в head, размер на диске.")
final class StatsCommand implements Callable<Integer> {
    @ParentCommand
    Main root;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        ManagedChannel channel = root.channel();
        try {
            Stats s = root.blocking(channel).getStats(Empty.getDefaultInstance());
            PrintWriter out = spec.commandLine().getOut();
            out.printf("series        %,d%n", s.getSeries());
            out.printf("blocks        %,d%n", s.getBlocks());
            out.printf("head samples  %,d%n", s.getHeadSamples());
            out.printf("disk bytes    %,d%n", s.getDiskBytes());
            out.flush();
            return 0;
        } finally {
            Main.close(channel);
        }
    }
}

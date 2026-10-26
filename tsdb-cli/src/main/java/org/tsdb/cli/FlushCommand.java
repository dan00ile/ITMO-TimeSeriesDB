package org.tsdb.cli;

import java.util.concurrent.Callable;

import io.grpc.ManagedChannel;
import org.tsdb.proto.Empty;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "flush", description = "Сбросить head в блок на диске сейчас (админ).")
final class FlushCommand implements Callable<Integer> {
    @ParentCommand
    Main root;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        ManagedChannel channel = root.channel();
        try {
            root.blocking(channel).flush(Empty.getDefaultInstance());
            spec.commandLine().getOut().println("flushed");
            spec.commandLine().getOut().flush();
            return 0;
        } finally {
            Main.close(channel);
        }
    }
}

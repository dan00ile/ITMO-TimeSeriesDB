package org.tsdb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.db.DbOptions;
import org.tsdb.db.TimeSeriesDB;
import org.tsdb.server.TimeSeriesServiceImpl;

// Команды CLI против настоящего сервера и БД в той же JVM: --addr — имя in-process сервера.
class CliTest {
    @TempDir
    Path dir;

    private TimeSeriesDB db;
    private Server server;
    private String name;
    private Function<String, ManagedChannel> savedChannels;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        db = TimeSeriesDB.open(DbOptions.builder(dir).compactionIntervalMs(0).build());
        name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(new TimeSeriesServiceImpl(db)).build().start();
        savedChannels = Main.channels;
        Main.channels = addr -> InProcessChannelBuilder.forName(addr).build();
    }

    @AfterEach
    void stop() {
        Main.channels = savedChannels;
        server.shutdownNow();
        db.close();
    }

    private Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        var cli = Main.commandLine();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int code = cli.execute(Stream.concat(Stream.of("--addr", name), Stream.of(args)).toArray(String[]::new));
        return new Result(code, out.toString(), err.toString());
    }

    @Test
    void writeThenQuery() {
        Result w = run("write", "cpu_usage{host=\"web-1\"}", "0.42", "--ts", "1000");
        assertEquals(0, w.code(), w.err());
        assertTrue(w.out().contains("accepted 1, rejected 0"), w.out());

        Result q = run("query", "cpu_usage{host=~\"web-.*\"}", "--start", "0", "--end", "2000");
        assertEquals(0, q.code(), q.err());
        assertTrue(q.out().contains("cpu_usage{host=\"web-1\"}  (1 points)"), q.out());
        assertTrue(q.out().contains("1970-01-01T00:00:01Z"), q.out());
        assertTrue(q.out().contains("0.42"), q.out());
        assertTrue(q.out().contains("1 series, 1 points"), q.out());
    }

    @Test
    void outOfOrderWriteExitsWithOne() {
        run("write", "cpu{host=\"a\"}", "1", "--ts", "1000");
        Result again = run("write", "cpu{host=\"a\"}", "2", "--ts", "1000");
        assertEquals(1, again.code());
        assertTrue(again.out().contains("rejected 1"), again.out());
    }

    @Test
    void aggregatedQueryGroupsByHost() {
        run("write", "cpu{host=\"a\",core=\"0\"}", "1", "--ts", "1000");
        run("write", "cpu{host=\"a\",core=\"1\"}", "3", "--ts", "1000");
        run("write", "cpu{host=\"b\",core=\"0\"}", "10", "--ts", "1000");

        Result q = run("query", "cpu", "--start", "0", "--end", "2000", "--agg", "avg", "--by", "host");
        assertEquals(0, q.code(), q.err());
        assertTrue(q.out().contains("{host=\"a\"}  (1 points)"), q.out());
        assertTrue(q.out().replace("\r", "").contains("1970-01-01T00:00:02Z     2\n"), q.out()); // avg(1, 3), ts = end
        assertTrue(q.out().contains("{host=\"b\"}  (1 points)"), q.out());
    }

    @Test
    void badSelectorIsReportedWithoutStacktrace() {
        Result r = run("query", "cpu{host=\"a\"");
        assertEquals(2, r.code());
        assertTrue(r.err().startsWith("error: "), r.err());
    }

    @Test
    void serverErrorShowsStatusCode() {
        // groupBy без агрегации сервер отклоняет: INVALID_ARGUMENT
        Result r = run("query", "cpu", "--by", "host");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("INVALID_ARGUMENT"), r.err());
    }

    @Test
    void loadFastThenStatsAndLabels() {
        Result load = run("load", "--series", "10", "--interval", "1s", "--duration", "1m", "--fast");
        assertEquals(0, load.code(), load.err());
        assertTrue(load.out().contains("accepted 600, rejected 0"), load.out());

        Result stats = run("stats");
        assertTrue(stats.out().matches("(?s)series\\s+10\\b.*"), stats.out());

        assertTrue(run("labels").out().contains("host"));
        Result hosts = run("labels", "host");
        assertEquals("host-0000\nhost-0001\nhost-0002\n", hosts.out().replace("\r", ""));
    }

    @Test
    void flushCreatesBlock() {
        run("write", "cpu{host=\"a\"}", "1", "--ts", "1000");
        assertTrue(run("flush").out().contains("flushed"));
        assertTrue(run("stats").out().matches("(?s).*blocks\\s+1\\b.*"));
    }
}

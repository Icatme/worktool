import org.yameida.worktool.protocol.CommandLedger;
import org.yameida.worktool.protocol.ExportPolicy;
import org.yameida.worktool.protocol.UploadOutcome;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** SDK-independent regression suite against production policy classes. No network or Android app. */
public final class ProtocolSafetyTest {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    private static void rejected(File root, String name) throws Exception {
        try {
            ExportPolicy.readReviewedFile(root, name);
            throw new AssertionError("Unexpected export: " + name);
        } catch (IOException expected) { check(true, "reject " + name); }
    }
    public static void main(String[] args) throws Exception {
        String first = CommandLedger.identity("packet", 0);
        String second = CommandLedger.identity("packet", 1);
        CommandLedger ledger = new CommandLedger(4, 1000);
        check(ledger.reserve(first, 0) == CommandLedger.Admission.ACCEPTED, "batch first command");
        check(ledger.reserve(second, 0) == CommandLedger.Admission.ACCEPTED, "batch second command, even identical text");
        check(ledger.reserve(first, 60000) == CommandLedger.Admission.DUPLICATE, "queued over 30 seconds remains deduplicated");
        check(ledger.start(first), "start first");
        check(ledger.reserve(first, 120000) == CommandLedger.Admission.DUPLICATE, "busy/paused running command remains deduplicated");
        ledger.releaseQueued();
        check(ledger.reserve(first, 120000) == CommandLedger.Admission.DUPLICATE, "clear preserves running command");
        check(!ledger.start(second), "cancelled command cannot race into execution");
        check(ledger.reserve(second, 120000) == CommandLedger.Admission.ACCEPTED, "cancelled queued command can be redelivered");
        ledger.finish(first, 120000);
        check(ledger.reserve(first, 120999) == CommandLedger.Admission.DUPLICATE, "finished replay retained");
        check(ledger.reserve(first, 121000) == CommandLedger.Admission.ACCEPTED, "explicit retention boundary");
        check(ledger.reserve(CommandLedger.identity("other", 0), 121000) == CommandLedger.Admission.ACCEPTED, "new packet with same payload independent");
        check(!CommandLedger.identity("a:1", 2).equals(CommandLedger.identity("a", 12)), "identity delimiters unambiguous");
        CommandLedger bounded = new CommandLedger(1, 100);
        bounded.reserve(first, 0);
        check(bounded.reserve(second, 99999) == CommandLedger.Admission.FULL, "capacity never evicts active work");
        String clear = CommandLedger.identity("clear", 0);
        check(bounded.reserveControl(clear, 99999) == CommandLedger.Admission.ACCEPTED, "queue clear admitted at capacity");
        bounded.start(clear);
        bounded.releaseQueued();
        bounded.finish(clear, 99999);
        check(bounded.reserveControl(clear, 100000) == CommandLedger.Admission.DUPLICATE, "replayed clear cannot cancel new work");
        check(!bounded.start(first), "queue clear cancels full paused backlog");
        CommandLedger completedFull = new CommandLedger(1, 1000);
        completedFull.reserve(first, 0);
        completedFull.start(first);
        completedFull.finish(first, 1);
        completedFull.reserveControl(clear, 2);
        check(completedFull.reserve(first, 3) == CommandLedger.Admission.DUPLICATE,
                "queue clear never evicts completed side-effect identities");
        try { CommandLedger.identity("", 0); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { check(true, "reject missing stable ID"); }

        CommandLedger concurrent = new CommandLedger(20, 1000);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Thread thread = new Thread(() -> {
                try { gate.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (concurrent.reserve(first, 0) == CommandLedger.Admission.ACCEPTED) admitted.incrementAndGet();
            });
            threads.add(thread);
            thread.start();
        }
        gate.countDown();
        for (Thread thread : threads) thread.join();
        check(admitted.get() == 1, "concurrent redelivery admitted once");

        Path fixture = Files.createTempDirectory(Path.of(args[0]), "export-test-");
        File exports = Files.createDirectory(fixture.resolve("exports")).toFile();
        Files.writeString(exports.toPath().resolve("reviewed.txt"), "redacted diagnostic");
        check(new String(ExportPolicy.readReviewedFile(exports, "reviewed.txt")).equals("redacted diagnostic"), "reviewed export snapshot");
        rejected(exports, null);
        rejected(exports, "");
        rejected(exports, "../reviewed.txt");
        rejected(exports, exports.toPath().resolve("reviewed.txt").toString());
        rejected(exports, "private.log");
        rejected(exports, ".secret.json");
        rejected(exports, "missing.txt");
        Files.createDirectory(exports.toPath().resolve("directory.txt"));
        rejected(exports, "directory.txt");
        Files.write(exports.toPath().resolve("empty.txt"), new byte[0]);
        rejected(exports, "empty.txt");
        Files.write(exports.toPath().resolve("large.txt"), new byte[ExportPolicy.MAX_BYTES + 1]);
        rejected(exports, "large.txt");
        Files.write(exports.toPath().resolve("limit.json"), new byte[ExportPolicy.MAX_BYTES]);
        check(ExportPolicy.readReviewedFile(exports, "limit.json").length == ExportPolicy.MAX_BYTES, "size upper boundary");
        // Symlink creation is deliberately not attempted on Windows (requires extra OS privileges).
        check(UploadOutcome.accepted(200, 200), "HTTP and business success");
        check(!UploadOutcome.accepted(200, 500), "business failure");
        check(!UploadOutcome.accepted(500, 200), "HTTP failure");
        check(!UploadOutcome.accepted(200, null), "empty/malformed body is not success");
        check(!UploadOutcome.accepted(302, 200), "unaccepted redirect is not success");
        System.out.println("RESULT: " + checks + " checks passed. Symlink and Android integration tests pending.");
    }
}

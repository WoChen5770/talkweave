package io.github.wochen5770.talkweave.runtime.probe;

import io.github.wochen5770.talkweave.persistence.ConversationRepository;
import io.github.wochen5770.talkweave.persistence.SqliteStore;
import io.github.wochen5770.talkweave.runtime.AssistantProperties;
import io.github.wochen5770.talkweave.runtime.PrivateStateFiles;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static io.github.wochen5770.talkweave.persistence.ConversationRepository.*;

/** Explicit CI entry point: synthetic local storage only, never boots Spring or constructs an external client. */
public final class ContainerStorageProbe {
    private static final String MARKER = "ci-storage-fixture.txt";
    private static final String PREFIX = "personal-assistant-ci-storage-v1\n";
    private ContainerStorageProbe() { }
    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("Expected mode, new fixture directory and architecture");
            String expected = args[2];
            String actual = System.getProperty("os.arch");
            if (!(expected.equals("amd64") && (actual.equals("amd64") || actual.equals("x86_64")))
                    && !(expected.equals("arm64") && actual.equals("aarch64"))) throw new IllegalStateException("Architecture mismatch");
            run(args[0], Path.of(args[1]));
            System.out.println("CI_STORAGE_OK mode=" + args[0] + " arch=" + actual + " java=" + System.getProperty("java.version") + " schema=2 externalCalls=0");
        } catch (Exception failure) {
            String category = failure instanceof io.github.wochen5770.talkweave.persistence.StorageProblem storage
                    ? storage.reason().name() : failure.getClass().getSimpleName();
            System.err.println("CI_STORAGE_FAILED category=" + category + ": check fixture directory, architecture and filesystem permissions; no external requests were made.");
            System.exit(1);
        }
    }
    static void run(String mode, Path directory) throws Exception {
        if (!mode.equals("--write") && !mode.equals("--verify")) throw new IllegalArgumentException("Invalid CI mode");
        Path marker = directory.resolve(MARKER);
        if (mode.equals("--write")) {
            Files.createDirectory(directory); // Never initialize or mutate an existing production directory.
            PrivateStateFiles.restrict(directory, true);
            Files.writeString(marker, PREFIX + "INCOMPLETE", StandardOpenOption.CREATE_NEW);
            PrivateStateFiles.restrict(marker, false);
        } else {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 256) throw new IllegalArgumentException("Not a CI fixture");
            String value = Files.readString(marker);
            if (!value.matches(PREFIX + "[0-9a-f-]{36}")) throw new IllegalArgumentException("Not a completed CI fixture");
        }
        var config = new AssistantProperties.Storage(directory.toString(), 10, 0, Duration.ofSeconds(5));
        try (var store = SqliteStore.open(config)) {
            if (store.schemaVersion() != 2) throw new IllegalStateException("Schema mismatch");
            var repository = new ConversationRepository(store, new Binding("ci-bot", "ci-owner"), config);
            if (mode.equals("--write")) {
                var session = repository.installSession("ci-bot", "https://example.invalid", "synthetic-ci-token", "ci-scanner");
                if (!repository.acceptBatch(session.generation(), "", "ci-cursor-1", List.of(message("1", "ci-question")))) throw new IllegalStateException("Write failed");
                var work = repository.claimNext().orElseThrow();
                repository.saveReply(work.sequence(), ModelStatus.SUCCEEDED, "ci-answer", "ci-answer");
                var delivery = repository.claimNext().orElseThrow();
                repository.finishSend(delivery.sequence(), Delivery.CONFIRMED); // Simulated delivery, not a network send.
                Files.writeString(marker, PREFIX + work.conversationId(), StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                String conversationId = Files.readString(marker).substring(PREFIX.length());
                var session = repository.session().orElseThrow();
                if (!session.cursor().equals("ci-cursor-1") || repository.pendingCount() != 0) throw new IllegalStateException("Persistent state mismatch");
                repository.acceptBatch(session.generation(), session.cursor(), "ci-cursor-2", List.of(message("2", "ci-followup")));
                var work = repository.claimNext().orElseThrow();
                if (!conversationId.equals(work.conversationId()) || !repository.history(work).stream().map(m -> m.text()).toList().equals(List.of("ci-question", "ci-answer"))) {
                    throw new IllegalStateException("Conversation did not survive container replacement");
                }
                repository.interruptProcessing(work.sequence()); // Leave no unfinished fixture work.
            }
        }
    }
    private static Inbound message(String id, String text) { return new Inbound(id, "ci-owner", text, "synthetic-ci-context", false, true, true, true); }
}

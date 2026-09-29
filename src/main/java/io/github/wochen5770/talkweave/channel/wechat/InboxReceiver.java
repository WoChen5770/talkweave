package io.github.wochen5770.talkweave.channel.wechat;

import io.github.wochen5770.talkweave.persistence.ConversationRepository;
import io.github.wochen5770.talkweave.persistence.StorageProblem;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.net.URI;
import java.util.List;

/** One polling step; lifecycle/backoff belongs to the caller. No network operation holds a database transaction. */
public final class InboxReceiver {
    public record Batch(String cursor, List<ConversationRepository.Inbound> messages) {
        public Batch { messages = List.copyOf(messages); }
        @Override public String toString() { return "Batch[count=" + messages.size() + "]"; }
    }
    @FunctionalInterface public interface Poller { Batch poll(ConversationRepository.Session session); }
    public enum Result { SAVED, STALE_RESPONSE, PAUSED }
    private final ConversationRepository repository;
    private final Poller poller;
    private final java.util.function.BiConsumer<ConversationRepository.Session, List<ConversationRepository.Inbound>> observer;

    public InboxReceiver(ConversationRepository repository, Poller poller) {
        this(repository, poller, (session, messages) -> { });
    }
    public InboxReceiver(ConversationRepository repository, Poller poller,
            java.util.function.BiConsumer<ConversationRepository.Session, List<ConversationRepository.Inbound>> observer) {
        this.repository = repository;
        this.poller = poller;
        this.observer = observer;
    }
    public static InboxReceiver forWechat(ConversationRepository repository, WechatApiClient client) {
        return forWechat(repository, client, (session, messages) -> { });
    }
    public static InboxReceiver forWechat(ConversationRepository repository, WechatApiClient client,
            java.util.function.BiConsumer<ConversationRepository.Session, List<ConversationRepository.Inbound>> observer) {
        return new InboxReceiver(repository, session -> {
            var updates = client.getUpdates(credentials(session), session.cursor());
            return new Batch(updates.cursor(), updates.messages().stream().map(m -> new ConversationRepository.Inbound(
                    m.messageId(), m.sender(), m.text(), m.contextToken(), m.group(), m.messageType() == 1,
                    m.messageState() == 2, m.text() != null && !m.text().isBlank())).toList());
        }, observer);
    }
    public Result receiveOnce() { return receiveOnce(() -> true); }
    public Result receiveOnce(java.util.function.BooleanSupplier accepting) {
        if (!accepting.getAsBoolean()) return Result.PAUSED;
        if (repository.intakeState() != ConversationRepository.Intake.READY) return Result.PAUSED;
        var snapshot = repository.session();
        if (snapshot.isEmpty() || !snapshot.get().active()) return Result.PAUSED;
        var session = snapshot.get();
        Batch batch;
        try { batch = poller.poll(session); }
        catch (RemoteFailure failure) {
            if (failure.kind() == RemoteFailure.Kind.STALE_TOKEN) repository.invalidateSession(session.generation());
            throw failure;
        }
        if (!accepting.getAsBoolean()) return Result.PAUSED;
        observer.accept(session, batch.messages());
        try {
            return repository.acceptBatch(session.generation(), session.cursor(), batch.cursor(), batch.messages()) ? Result.SAVED : Result.STALE_RESPONSE;
        } catch (StorageProblem failure) {
            if (failure.reason() == StorageProblem.Reason.BACKLOG || failure.reason() == StorageProblem.Reason.LOW_DISK) return Result.PAUSED;
            throw failure;
        }
    }
    public static WechatApiClient.Credentials credentials(ConversationRepository.Session session) {
        return new WechatApiClient.Credentials(session.botId(), session.token(), URI.create(session.origin()), session.scanUserId());
    }
}
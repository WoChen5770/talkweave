package io.github.wochen5770.talkweave.channel.wechat;

import io.github.wochen5770.talkweave.conversation.ConversationWorker;
import io.github.wochen5770.talkweave.persistence.ConversationRepository;
import io.github.wochen5770.talkweave.persistence.StorageProblem;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import static io.github.wochen5770.talkweave.persistence.ConversationRepository.*;

/** Captures one authorized generation and one inbound context. Auxiliary state is never reused across turns. */
public final class WechatOutbound implements ConversationWorker.ReplySender, ConversationWorker.Activity {
    private final ConversationRepository repository;
    private final WechatApiClient client;
    private long startedGeneration = -1;
    public WechatOutbound(ConversationRepository repository, WechatApiClient client) { this.repository = repository; this.client = client; }
    @Override public void send(Session captured, Work work) {
        Session current = repository.sessionForSend(work);
        if (current.generation() != captured.generation() || !current.botId().equals(captured.botId())) throw new StorageProblem(StorageProblem.Reason.UNAUTHORIZED);
        try { client.sendText(InboxReceiver.credentials(current), work.senderId(), work.contextToken(), work.clientId(), work.replyText()); }
        catch (RemoteFailure failure) { invalidate(current, failure); throw failure; }
    }
    @Override public Runnable begin(Work work) {
        Session session = repository.sessionForActivity(work);
        String ticket;
        try {
            ticket = client.getTypingTicket(InboxReceiver.credentials(session), work.senderId(), work.contextToken());
            if (ticket.isBlank()) return () -> { };
            if (!stillCurrent(session)) return () -> { };
        } catch (RemoteFailure failure) { invalidate(session, failure); return () -> { }; }
        bestEffort(session, () -> client.sendTyping(InboxReceiver.credentials(session), work.senderId(), ticket, true));
        return () -> {
            if (stillCurrent(session)) bestEffort(session, () -> client.sendTyping(InboxReceiver.credentials(session), work.senderId(), ticket, false));
        };
    }
    public synchronized void connected(Session session) {
        if (!stillCurrent(session) || startedGeneration == session.generation()) return;
        startedGeneration = session.generation(); // No repeated auxiliary requests on failure.
        bestEffort(session, () -> client.notifyLifecycle(InboxReceiver.credentials(session), true));
    }
    public void stopping() {
        var session = repository.session();
        if (session.isPresent() && session.get().active()) bestEffort(session.get(), () -> client.notifyLifecycle(InboxReceiver.credentials(session.get()), false));
    }
    private boolean stillCurrent(Session captured) {
        var current = repository.session();
        return current.isPresent() && current.get().active() && current.get().generation() == captured.generation()
                && current.get().botId().equals(captured.botId());
    }
    private void bestEffort(Session session, Runnable action) {
        try { action.run(); } catch (RemoteFailure failure) { invalidate(session, failure); }
        catch (RuntimeException ignored) { }
    }
    private void invalidate(Session session, RemoteFailure failure) {
        if (failure.kind() == RemoteFailure.Kind.STALE_TOKEN) repository.invalidateSession(session.generation());
    }
}

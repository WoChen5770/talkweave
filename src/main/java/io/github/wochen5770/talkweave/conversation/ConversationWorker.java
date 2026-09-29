package io.github.wochen5770.talkweave.conversation;

import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.persistence.ConversationRepository;
import io.github.wochen5770.talkweave.persistence.StorageProblem;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import static io.github.wochen5770.talkweave.persistence.ConversationRepository.*;

/** One serial work step. Repository claims fence concurrent callers, network calls occur outside transactions. */
public final class ConversationWorker {
    @FunctionalInterface public interface ReplySender { void send(Session session, Work work); }
    @FunctionalInterface public interface Activity { Runnable begin(Work work); }
    private static final String HELP = "当前仅支持文字对话。/new 开始新会话（保留旧记录）；/help 查看帮助。暂不支持图片、语音、文件或工具执行。";
    private final Object gate = new Object();
    private boolean accepting = true;
    private volatile boolean cancelled;
    private Work active;
    private final ConversationRepository repository;
    private final AssistantService assistant;
    private final ReplySender sender;
    private final ContextBudget context;
    private final ReplyFormatter formatter;
    private final Activity activity;

    public ConversationWorker(ConversationRepository repository, AssistantService assistant, ReplySender sender,
                              ContextBudget context, ReplyFormatter formatter) {
        this(repository, assistant, sender, context, formatter, work -> () -> { });
    }
    public ConversationWorker(ConversationRepository repository, AssistantService assistant, ReplySender sender,
                              ContextBudget context, ReplyFormatter formatter, Activity activity) {
        this.activity = activity;
        this.repository = repository;
        this.assistant = assistant;
        this.sender = sender;
        this.context = context;
        this.formatter = formatter;
    }
    public boolean step() {
        Work work;
        synchronized (gate) {
            if (!accepting) return false;
            var next = repository.claimNext();
            if (next.isEmpty()) return false;
            active = work = next.get();
        }
        try { return process(work); }
        finally { synchronized (gate) { if (active == work) active = null; } }
    }
    public void stopAccepting() { synchronized (gate) { accepting = false; } }
    public void cancelCurrent() {
        synchronized (gate) {
            accepting = false; cancelled = true;
            if (active != null) {
                if (active.stage() == Stage.SENDING) repository.finishSend(active.sequence(), Delivery.UNKNOWN);
                else repository.interruptProcessing(active.sequence());
                active = null;
            }
        }
    }
    private void commit(Runnable operation) {
        synchronized (gate) { if (!cancelled) { operation.run(); active = null; } }
    }
    private boolean process(Work work) {
        if (cancelled) return true;
        if (work.stage() == Stage.SENDING) {
            Session session;
            try { session = repository.sessionForSend(work); }
            catch (StorageProblem failure) {
                if (failure.reason() != StorageProblem.Reason.UNAUTHORIZED) throw failure;
                commit(() -> repository.finishSend(work.sequence(), Delivery.UNKNOWN));
                return true;
            }
            Delivery outcome;
            try { sender.send(session, work); outcome = Delivery.CONFIRMED; }
            catch (RemoteFailure failure) {
                outcome = failure.kind() == RemoteFailure.Kind.TIMEOUT || failure.kind() == RemoteFailure.Kind.CONNECTION
                        || (failure.kind() == RemoteFailure.Kind.HTTP && failure.code() != null && failure.code() >= 500)
                        || failure.kind() == RemoteFailure.Kind.JSON || failure.kind() == RemoteFailure.Kind.INVALID_RESPONSE
                        ? Delivery.UNKNOWN : Delivery.FAILED;
            } catch (RuntimeException failure) { outcome = Delivery.UNKNOWN; }
            // If persistence fails here, SENDING remains on disk and restart will classify it UNKNOWN.
            Delivery result = outcome;
            commit(() -> repository.finishSend(work.sequence(), result));
            return true;
        }
        if (work.kind() != Kind.CHAT) {
            String localReply = work.kind() == Kind.NOTICE ? "目前仅支持文字消息，请将问题以文字发送。" : HELP;
            if ("/new".equals(work.text())) commit(() -> repository.completeNewConversation(work.sequence(), formatter.format("已开始新会话，旧记录保留。", false)));
            else commit(() -> repository.saveReply(work.sequence(), ModelStatus.NONE, null, formatter.format(localReply, false)));
            return true;
        }
        java.util.Optional<java.util.List<DialogueMessage>> prompt;
        try { prompt = context.prepare(repository.history(work), work.text()); }
        catch (StorageProblem failure) {
            if (failure.reason() != StorageProblem.Reason.UNAUTHORIZED) throw failure;
            commit(() -> repository.failProcessing(work.sequence()));
            return true;
        }
        if (prompt.isEmpty()) {
            commit(() -> repository.saveReply(work.sequence(), ModelStatus.FAILED, null, formatter.format("输入超出上下文预算，请缩短问题或调整模型容量配置。", false)));
            return true;
        }
        AssistantService.Reply reply;
        Runnable finishActivity;
        try { finishActivity = activity.begin(work); }
        catch (RuntimeException ignored) { finishActivity = () -> { }; }
        try {
            if (cancelled) return true;
            repository.sessionForActivity(work); // Auxiliary calls may have discovered a revoked generation.
            reply = assistant.answer(prompt.get());
            if (reply == null || reply.text() == null || reply.text().isBlank()) throw new IllegalStateException("No usable model text");
        } catch (RuntimeException failure) {
            if ((cancelled || Thread.currentThread().isInterrupted())) { commit(() -> repository.interruptProcessing(work.sequence())); return true; }
            commit(() -> repository.saveReply(work.sequence(), ModelStatus.FAILED, null, formatter.format("本次回答未能完成，请稍后重新发送问题。", false)));
            return true;
        } finally {
            try { finishActivity.run(); } catch (RuntimeException ignored) { }
        }
        if ((cancelled || Thread.currentThread().isInterrupted())) { commit(() -> repository.interruptProcessing(work.sequence())); return true; }
        // Persisting the generated result is outside the model-error catch: a failed write must not trigger another request.
        commit(() -> repository.saveReply(work.sequence(), ModelStatus.SUCCEEDED, reply.text(), formatter.format(reply.text(), reply.truncated())));
        return true;
    }
}

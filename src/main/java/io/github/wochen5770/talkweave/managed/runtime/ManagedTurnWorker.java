package io.github.wochen5770.talkweave.managed.runtime;

import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.conversation.*;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.model.*;
import io.github.wochen5770.talkweave.managed.cache.HistoryService;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.util.List;

/** Executes one durable stage. The scheduler serializes each user, and may interleave other users. */
public final class ManagedTurnWorker {
    @FunctionalInterface public interface Sender {
        void send(WechatApiClient.Credentials credentials, String recipient, String contextToken, String clientId, String text);
    }
    @FunctionalInterface public interface ModelCall {
        AssistantService.Reply answer(ManagedSettings.ModelSnapshot snapshot, ModelRequestContext context,
                                      List<DialogueMessage> messages, ModelAttemptObserver observer);
    }
    private final ManagedUsers users;
    private final ManagedSettings settings;
    private final ManagedConversations conversations;
    private final ManagedUsage usage;
    private final ModelCall model;
    private final Sender sender;
    private final ReplyFormatter formatter;
    private final ConversationTimeContext timeContext;
    private final HistoryService historyService;
    private final java.util.function.Function<ManagedConversations.Event, Runnable> modelActivity;
    public ManagedTurnWorker(ManagedUsers users, ManagedSettings settings, ManagedConversations conversations,
                             ManagedUsage usage, ModelCall model, Sender sender, int replyBytes) {
        this(users, settings, conversations, usage, model, sender, replyBytes, event -> () -> { });
    }
    public ManagedTurnWorker(ManagedUsers users, ManagedSettings settings, ManagedConversations conversations, ManagedUsage usage,
                             ModelCall model, Sender sender, int replyBytes, java.util.function.Function<ManagedConversations.Event, Runnable> modelActivity) {
        this(users, settings, conversations, usage, model, sender, replyBytes, modelActivity, ConversationTimeContext.systemDefault());
    }
    public ManagedTurnWorker(ManagedUsers users, ManagedSettings settings, ManagedConversations conversations, ManagedUsage usage,
                             ModelCall model, Sender sender, int replyBytes, java.util.function.Function<ManagedConversations.Event, Runnable> modelActivity,
                             ConversationTimeContext timeContext) {
        this(users, settings, conversations, usage, model, sender, replyBytes, modelActivity, timeContext, null);
    }
    public ManagedTurnWorker(ManagedUsers users, ManagedSettings settings, ManagedConversations conversations, ManagedUsage usage,
                             ModelCall model, Sender sender, int replyBytes, java.util.function.Function<ManagedConversations.Event, Runnable> modelActivity,
                             ConversationTimeContext timeContext, HistoryService historyService) {
        this.historyService = historyService;
        this.timeContext = java.util.Objects.requireNonNull(timeContext);
        this.modelActivity = modelActivity;
        this.users = users; this.settings = settings; this.conversations = conversations;
        this.usage = usage; this.model = model; this.sender = sender; this.formatter = new ReplyFormatter(replyBytes);
    }
    public boolean runOnce(ManagedScope scope) {
        var claimed = conversations.claim(scope);
        if (claimed.isEmpty()) return false;
        var work = claimed.get(); var event = work.event();
        if (work.sending()) {
            // Fetch current credentials only after the durable SENDING transition and immediately before dispatch.
            var connection = users.connection(scope);
            ManagedConversations.Delivery delivery;
            try {
                sender.send(connection.credentials(), scope.senderId(), event.contextToken(), work.clientId(), work.reply());
                delivery = ManagedConversations.Delivery.CONFIRMED;
            } catch (RemoteFailure failure) {
                delivery = switch (failure.kind()) {
                    case CONNECTION, TIMEOUT, JSON, INVALID_RESPONSE -> ManagedConversations.Delivery.UNKNOWN;
                    default -> ManagedConversations.Delivery.FAILED;
                };
            } catch (RuntimeException failure) { delivery = ManagedConversations.Delivery.UNKNOWN; }
            // A DB failure escapes; SENDING must become UNKNOWN on recovery, never be auto-replayed.
            var delivered = conversations.finishSend(scope, event.sequence(), delivery);
            if (historyService != null) historyService.afterSend(event.sequence(), delivered);
            return true;
        }
        if (event.kind() != ManagedConversations.Kind.CHAT) {
            String text = switch (event.kind()) {
                case NEW -> "已开始新会话，旧记录保留。";
                case HELP -> "发送文字与助手聊天。/new 开始新会话；/help 查看帮助。";
                default -> "目前仅支持文字消息，请将问题以文字发送。";
            };
            save(scope, event.sequence(), text, false, false); return true;
        }
        var configured = settings.currentModel();
        if (configured.isEmpty()) {
            save(scope, event.sequence(), "管理员尚未配置模型服务，请稍后再试。", false, false); return true;
        }
        var snapshot = configured.get();
        var history = historyService == null ? conversations.history(scope, event.sequence(), snapshot.configuration().historyRounds())
                : historyService.load(scope, event.sequence(), snapshot);
        var time = timeContext.snapshot(event.receivedAt());
        var prompt = new ContextBudget(snapshot.configuration()).prepare(history, event.text(), time);
        if (prompt.isEmpty()) {
            save(scope, event.sequence(), "输入超出上下文预算，请缩短问题或联系管理员。", false, false); return true;
        }
        var context = new ModelRequestContext(scope.userId(), scope.bindingId(), event.conversationId(), event.sequence(),
                scope.generation(), scope.authEpoch(), snapshot.version());
        AssistantService.Reply response;
        var endActivity = modelActivity.apply(event);
        try {
            response = model.answer(snapshot, context, prompt.get(), new ManagedModelObserver(usage, scope, context));
        } catch (RemoteFailure failure) {
            save(scope, event.sequence(), "模型服务暂不可用，请稍后再试。", false, false); return true;
        } finally { endActivity.run(); }
        // No broad catch around persistence: failure here must not repeat a paid model call.
        save(scope, event.sequence(), response.text(), response.truncated(), true);
        return true;
    }
    private void save(ManagedScope scope, long sequence, String text, boolean truncated, boolean succeeded) {
        conversations.saveReply(scope, sequence, formatter.format(text, truncated), succeeded);
    }
}

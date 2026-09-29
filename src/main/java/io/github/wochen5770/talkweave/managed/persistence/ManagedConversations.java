package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import static io.github.wochen5770.talkweave.managed.persistence.Sql.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Messages get their immutable conversation assignment in the receive transaction. */
public final class ManagedConversations {
    public enum Kind { CHAT, NEW, HELP, NOTICE }
    public enum Delivery { CONFIRMED, FAILED, UNKNOWN }
    public record Event(long sequence, String conversationId, Kind kind, String text, String contextToken, long receivedAt) {
        @Override public String toString() { return "Event[sequence=" + sequence + ", content=REDACTED]"; }
    }
    public record Work(ManagedScope scope, Event event, boolean sending, String reply, String clientId) {
        @Override public String toString() { return "Work[REDACTED]"; }
    }
    private final ManagedStore store;
    private final Clock clock;
    private final int perUserLimit;
    private final int totalLimit;
    private static final String PENDING = "('RECEIVED','PROCESSING','RESPONSE_READY','SENDING')";
    public ManagedConversations(ManagedStore store, Clock clock) { this(store, clock, 1000, 10000); }
    public ManagedConversations(ManagedStore store, Clock clock, int perUserLimit, int totalLimit) {
        if (perUserLimit < 1 || totalLimit < perUserLimit) throw new IllegalArgumentException("Invalid queue limits");
        this.store = store; this.clock = clock; this.perUserLimit = perUserLimit; this.totalLimit = totalLimit;
    }

    public boolean globalBacklog() { return store.transaction(c -> scalar(c, "SELECT count(*) FROM turn WHERE stage IN " + PENDING) >= totalLimit); }
    public enum Capacity { AVAILABLE, USER_BACKLOG, GLOBAL_BACKLOG }
    public Capacity capacity(ManagedScope scope) {
        return store.transaction(c -> {
            ManagedUsers.authorize(c, scope);
            if (scalar(c, "SELECT count(*) FROM turn WHERE stage IN " + PENDING) >= totalLimit) return Capacity.GLOBAL_BACKLOG;
            if (scalar(c, "SELECT count(*) FROM turn WHERE user_id=? AND stage IN " + PENDING, scope.userId()) >= perUserLimit) return Capacity.USER_BACKLOG;
            return Capacity.AVAILABLE;
        });
    }
    /** No replay of an uncertain stage after a worker fails or is interrupted in-process. */
    public void interrupt(ManagedScope scope) {
        store.transaction(c -> {
            update(c, "UPDATE turn SET stage=CASE WHEN stage='SENDING' THEN 'DELIVERY_UNKNOWN' ELSE 'INTERRUPTED' END,updated_at=? WHERE user_id=? AND binding_id=? AND stage IN ('PROCESSING','SENDING') AND event_sequence IN (SELECT sequence FROM inbound_event WHERE user_id=? AND binding_id=? AND generation=? AND auth_epoch=?)",
                    clock.millis(), scope.userId(), scope.bindingId(), scope.userId(), scope.bindingId(), scope.generation(), scope.authEpoch());
            return null;
        });
    }
    /** Cursor commit, accepted events and conversation assignment are atomic for the whole batch. */
    public List<Event> accept(ManagedScope scope, WechatApiClient.Updates updates) {
        return store.transaction(c -> {
            ManagedUsers.authorize(c, scope);
            var result = new ArrayList<Event>();
            long receivedAt = clock.millis();
            for (var message : updates.messages()) {
                if (message.group() || message.messageType() != 1 || message.messageState() != 2
                        || !scope.senderId().equals(message.sender()) || blank(message.messageId()) || blank(message.contextToken())) continue;
                if (scalar(c, "SELECT count(*) FROM inbound_event WHERE bot_id=? AND message_id=?", scope.botId(), message.messageId()) != 0) continue;
                if (scalar(c, "SELECT count(*) FROM turn WHERE user_id=? AND stage IN " + PENDING, scope.userId()) >= perUserLimit
                        || scalar(c, "SELECT count(*) FROM turn WHERE stage IN " + PENDING) >= totalLimit) throw new ManagedProblem(BACKLOG);
                String text = message.text();
                Kind kind = blank(text) ? Kind.NOTICE : switch (text.strip()) { case "/new" -> Kind.NEW; case "/help" -> Kind.HELP; default -> Kind.CHAT; };
                String conversation = assign(c, scope, kind, receivedAt);
                update(c, "INSERT INTO inbound_event(user_id,binding_id,conversation_id,bot_id,sender_id,generation,auth_epoch,message_id,received_at,text,context_token,kind) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        scope.userId(), scope.bindingId(), conversation, scope.botId(), scope.senderId(), scope.generation(), scope.authEpoch(), message.messageId(), receivedAt,
                        kind == Kind.NOTICE ? null : text, message.contextToken(), kind.name());
                long sequence = scalar(c, "SELECT last_insert_rowid()");
                update(c, "INSERT INTO turn(event_sequence,user_id,binding_id,conversation_id,stage,updated_at) VALUES (?,?,?,?,'RECEIVED',?)",
                        sequence, scope.userId(), scope.bindingId(), conversation, receivedAt);
                result.add(new Event(sequence, conversation, kind, kind == Kind.NOTICE ? null : text, message.contextToken(), receivedAt));
            }
            update(c, "UPDATE channel_session SET cursor=?,updated_at=? WHERE binding_id=? AND user_id=?", updates.cursor(), receivedAt, scope.bindingId(), scope.userId());
            return List.copyOf(result);
        });
    }

    private static String assign(Connection c, ManagedScope scope, Kind kind, long now) throws SQLException {
        String current = null; Long last = null;
        try (var s = prepare(c, "SELECT id,last_user_message_at FROM conversation WHERE user_id=? AND binding_id=? AND current=1", scope.userId(), scope.bindingId()); var r = s.executeQuery()) {
            if (r.next()) { current = r.getString("id"); last = nullableLong(r, "last_user_message_at"); }
        }
        boolean activity = kind == Kind.CHAT || kind == Kind.NEW;
        long timeout = scalar(c, "SELECT idle_minutes FROM admin_setting WHERE slot=1") * 60_000;
        boolean expired = activity && last != null && now >= last && now - last >= timeout;
        long effectiveTime = last == null ? now : Math.max(last, now);
        if (current == null || kind == Kind.NEW || expired) {
            update(c, "UPDATE conversation SET current=0,ended_at=? WHERE user_id=? AND binding_id=? AND current=1", effectiveTime, scope.userId(), scope.bindingId());
            current = UUID.randomUUID().toString();
            update(c, "INSERT INTO conversation(id,user_id,binding_id,current,created_at,last_user_message_at) VALUES (?,?,?,1,?,?)",
                    current, scope.userId(), scope.bindingId(), effectiveTime, activity ? effectiveTime : null);
        } else if (activity) {
            update(c, "UPDATE conversation SET last_user_message_at=? WHERE id=? AND user_id=? AND binding_id=?", effectiveTime, current, scope.userId(), scope.bindingId());
        }
        return current;
    }

    public List<DialogueMessage> history(ManagedScope scope, long sequence) {
        return store.transaction(c -> {
            ManagedUsers.authorize(c, scope);
            Event event = event(c, scope, sequence);
            var history = new ArrayList<DialogueMessage>();
            try (var s = prepare(c, "SELECT e.text,t.reply_text FROM inbound_event e JOIN turn t ON t.event_sequence=e.sequence "
                    + "WHERE e.user_id=? AND e.binding_id=? AND e.conversation_id=? AND e.sequence<? AND e.kind='CHAT' AND t.stage='SENT' AND t.model_succeeded=1 ORDER BY e.sequence",
                    scope.userId(), scope.bindingId(), event.conversationId(), sequence); var r = s.executeQuery()) {
                while (r.next()) {
                    history.add(new DialogueMessage(DialogueMessage.Role.USER, r.getString(1)));
                    history.add(new DialogueMessage(DialogueMessage.Role.ASSISTANT, r.getString(2)));
                }
            }
            return List.copyOf(history);
        });
    }
    public Optional<Work> claim(ManagedScope scope) {
        return store.transaction(c -> {
            ManagedUsers.authorize(c, scope);
            if (scalar(c, "SELECT count(*) FROM turn WHERE user_id=? AND stage IN ('PROCESSING','SENDING')", scope.userId()) != 0) return Optional.empty();
            try (var s = prepare(c, "SELECT t.event_sequence,t.stage,t.reply_text,t.client_id FROM turn t JOIN inbound_event e ON e.sequence=t.event_sequence "
                    + "WHERE t.user_id=? AND t.binding_id=? AND e.generation=? AND e.auth_epoch=? AND t.stage IN ('RECEIVED','RESPONSE_READY') ORDER BY t.event_sequence LIMIT 1",
                    scope.userId(), scope.bindingId(), scope.generation(), scope.authEpoch()); var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                long sequence = r.getLong(1); boolean sending = r.getString(2).equals("RESPONSE_READY");
                String reply = r.getString(3); String clientId = r.getString(4);
                update(c, "UPDATE turn SET stage=?,updated_at=? WHERE event_sequence=?", sending ? "SENDING" : "PROCESSING", clock.millis(), sequence);
                return Optional.of(new Work(scope, event(c, scope, sequence), sending, reply, clientId));
            }
        });
    }
    public void saveReply(ManagedScope scope, long sequence, String reply, boolean modelSucceeded) {
        if (blank(reply)) throw new ManagedProblem(INVALID_INPUT);
        store.transaction(c -> {
            ManagedUsers.authorize(c, scope); event(c, scope, sequence);
            if (update(c, "UPDATE turn SET stage='RESPONSE_READY',reply_text=?,model_succeeded=?,client_id=?,updated_at=? WHERE event_sequence=? AND stage='PROCESSING'",
                    reply, modelSucceeded ? 1 : 0, UUID.randomUUID().toString(), clock.millis(), sequence) != 1) throw new ManagedProblem(CONFLICT);
            return null;
        });
    }
    /** The external send may already have occurred. Persist its outcome even if authorization changed. */
    public void finishSend(ManagedScope originalScope, long sequence, Delivery delivery) {
        Objects.requireNonNull(delivery);
        store.transaction(c -> {
            event(c, originalScope, sequence);
            String stage = switch (delivery) { case CONFIRMED -> "SENT"; case FAILED -> "SEND_FAILED"; case UNKNOWN -> "DELIVERY_UNKNOWN"; };
            update(c, "UPDATE turn SET stage=?,updated_at=? WHERE event_sequence=? AND stage='SENDING'", stage, clock.millis(), sequence);
            return null;
        });
    }
    static Event event(Connection c, ManagedScope scope, long sequence) throws SQLException {
        try (var s = prepare(c, "SELECT * FROM inbound_event WHERE sequence=? AND user_id=? AND binding_id=? AND bot_id=? AND sender_id=? AND generation=? AND auth_epoch=?",
                sequence, scope.userId(), scope.bindingId(), scope.botId(), scope.senderId(), scope.generation(), scope.authEpoch()); var r = s.executeQuery()) {
            if (!r.next()) throw new ManagedProblem(NOT_FOUND);
            return new Event(sequence, r.getString("conversation_id"), Kind.valueOf(r.getString("kind")), r.getString("text"), r.getString("context_token"), r.getLong("received_at"));
        }
    }
    static void invalidateWork(Connection c, String userId, long now) throws SQLException {
        update(c, "UPDATE turn SET stage=CASE WHEN stage='SENDING' THEN 'DELIVERY_UNKNOWN' ELSE 'INTERRUPTED' END,updated_at=? WHERE user_id=? AND stage IN " + PENDING, now, userId);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
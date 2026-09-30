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
    // Drive the ordered scope index first; starting from turn's status index sorts the entire match set.
    static final String HISTORY_SQL = "SELECT e.sequence,e.text,t.reply_text FROM inbound_event e FORCE INDEX (history_window) STRAIGHT_JOIN turn t ON t.event_sequence=e.sequence "
            + "WHERE e.user_id=? AND e.binding_id=? AND e.conversation_id=? AND e.sequence<? AND e.kind='CHAT' AND t.stage='SENT' AND t.model_succeeded=1 ORDER BY e.sequence DESC LIMIT ?";
    public ManagedConversations(ManagedStore store, Clock clock) { this(store, clock, 1000, 10000); }
    public ManagedConversations(ManagedStore store, Clock clock, int perUserLimit, int totalLimit) {
        if (perUserLimit < 1 || totalLimit < perUserLimit) throw new IllegalArgumentException("Invalid queue limits");
        this.store = store; this.clock = clock; this.perUserLimit = perUserLimit; this.totalLimit = totalLimit;
    }

    public boolean globalBacklog() { return store.transaction(c -> scalar(c, "SELECT count(*) FROM turn WHERE stage IN " + PENDING) >= totalLimit); }
    public enum Capacity { AVAILABLE, USER_BACKLOG, GLOBAL_BACKLOG }
    public Capacity capacity(ManagedScope scope) {
        return store.transaction(c -> {
            ManagedUsers.lockUser(c, scope.userId());
            ManagedUsers.authorize(c, scope);
            if (scalar(c, "SELECT count(*) FROM turn WHERE stage IN " + PENDING) >= totalLimit) return Capacity.GLOBAL_BACKLOG;
            if (scalar(c, "SELECT count(*) FROM turn WHERE user_id=? AND stage IN " + PENDING, scope.userId()) >= perUserLimit) return Capacity.USER_BACKLOG;
            return Capacity.AVAILABLE;
        });
    }
    /** No replay of an uncertain stage after a worker fails or is interrupted in-process. */
    public void interrupt(ManagedScope scope) {
        store.transaction(c -> {
            ManagedUsers.lockUser(c, scope.userId());
            update(c, "UPDATE turn SET stage=CASE WHEN stage='SENDING' THEN 'DELIVERY_UNKNOWN' ELSE 'INTERRUPTED' END,updated_at=? WHERE user_id=? AND binding_id=? AND stage IN ('PROCESSING','SENDING') AND event_sequence IN (SELECT sequence FROM inbound_event WHERE user_id=? AND binding_id=? AND generation=? AND auth_epoch=?)",
                    clock.millis(), scope.userId(), scope.bindingId(), scope.userId(), scope.bindingId(), scope.generation(), scope.authEpoch());
            return null;
        });
    }
    /** Cursor commit, accepted events and conversation assignment are atomic for the whole batch. */
    public List<Event> accept(ManagedScope scope, WechatApiClient.Updates updates) {
        return store.transaction(c -> {
            scalar(c, "SELECT slot FROM capacity_guard WHERE slot=1 FOR UPDATE");
            ManagedUsers.lockUser(c, scope.userId());
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
                long sequence = insertId(c, "INSERT INTO inbound_event(user_id,binding_id,conversation_id,bot_id,sender_id,generation,auth_epoch,message_id,received_at,text,context_token,kind) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        scope.userId(), scope.bindingId(), conversation, scope.botId(), scope.senderId(), scope.generation(), scope.authEpoch(), message.messageId(), receivedAt,
                        kind == Kind.NOTICE ? null : text, message.contextToken(), kind.name());
                update(c, "INSERT INTO turn(event_sequence,user_id,binding_id,conversation_id,runtime_epoch,stage,updated_at) VALUES (?,?,?,?,?,'RECEIVED',?)",
                        sequence, scope.userId(), scope.bindingId(), conversation, store.epoch(), receivedAt);
                result.add(new Event(sequence, conversation, kind, kind == Kind.NOTICE ? null : text, message.contextToken(), receivedAt));
            }
            update(c, "UPDATE channel_session SET `cursor`=?,updated_at=? WHERE binding_id=? AND user_id=?", updates.cursor(), receivedAt, scope.bindingId(), scope.userId());
            return List.copyOf(result);
        });
    }

    private static String assign(Connection c, ManagedScope scope, Kind kind, long now) throws SQLException {
        String current = null; Long last = null;
        try (var s = prepare(c, "SELECT v.id,v.last_user_message_at FROM conversation v JOIN active_conversation a ON a.conversation_id=v.id WHERE v.user_id=? AND v.binding_id=? FOR UPDATE", scope.userId(), scope.bindingId()); var r = s.executeQuery()) {
            if (r.next()) { current = r.getString("id"); last = nullableLong(r, "last_user_message_at"); }
        }
        boolean activity = kind == Kind.CHAT || kind == Kind.NEW;
        long timeout = scalar(c, "SELECT idle_minutes FROM admin_setting WHERE slot=1") * 60_000;
        boolean expired = activity && last != null && now >= last && now - last >= timeout;
        long effectiveTime = last == null ? now : Math.max(last, now);
        if (current == null || kind == Kind.NEW || expired) {
            if (current != null) {
                update(c, "UPDATE conversation SET ended_at=? WHERE id=? AND user_id=? AND binding_id=?", effectiveTime, current, scope.userId(), scope.bindingId());
                update(c, "DELETE FROM active_conversation WHERE user_id=? AND binding_id=?", scope.userId(), scope.bindingId());
            }
            current = UUID.randomUUID().toString();
            update(c, "INSERT INTO conversation(id,user_id,binding_id,created_at,last_user_message_at) VALUES (?,?,?,?,?)",
                    current, scope.userId(), scope.bindingId(), effectiveTime, activity ? effectiveTime : null);
            update(c, "INSERT INTO active_conversation(user_id,binding_id,conversation_id) VALUES (?,?,?)", scope.userId(), scope.bindingId(), current);
        } else if (activity) {
            update(c, "UPDATE conversation SET last_user_message_at=? WHERE id=? AND user_id=? AND binding_id=?", effectiveTime, current, scope.userId(), scope.bindingId());
        }
        return current;
    }

    public record Pair(long sequence, String userText, String replyText) {
        @Override public String toString() { return "Pair[sequence=" + sequence + ", content=REDACTED]"; }
    }
    public record HistorySnapshot(ManagedScope scope, String conversationId, long beforeSequence,
                                  long revision, long count, long watermark) {
        public boolean latestTail() { return watermark < beforeSequence; }
    }
    public record HistoryRead(HistorySnapshot snapshot, List<Pair> pairs) {
        public HistoryRead { pairs = List.copyOf(pairs); }
        public List<DialogueMessage> messages() {
            var result = new ArrayList<DialogueMessage>(pairs.size() * 2);
            for (var pair : pairs) {
                result.add(new DialogueMessage(DialogueMessage.Role.USER, pair.userText()));
                result.add(new DialogueMessage(DialogueMessage.Role.ASSISTANT, pair.replyText()));
            }
            return List.copyOf(result);
        }
    }
    public record Delivered(ManagedScope scope, String conversationId, long oldRevision,
                            long revision, long count, long watermark, Pair pair) { }

    public HistorySnapshot historySnapshot(ManagedScope scope, long sequence) {
        return store.transaction(c -> snapshot(c, scope, sequence));
    }
    private static HistorySnapshot snapshot(Connection c, ManagedScope scope, long sequence) throws SQLException {
        ManagedUsers.authorize(c, scope);
        // Metadata reads must not fetch message bodies or context tokens, even on a hot hit.
        try (var s = prepare(c, "SELECT v.history_revision,v.confirmed_turn_count,v.last_confirmed_sequence,v.id FROM inbound_event e JOIN conversation v ON v.id=e.conversation_id AND v.user_id=e.user_id AND v.binding_id=e.binding_id WHERE e.sequence=? AND e.user_id=? AND e.binding_id=? AND e.bot_id=? AND e.sender_id=? AND e.generation=? AND e.auth_epoch=?",
                sequence, scope.userId(), scope.bindingId(), scope.botId(), scope.senderId(), scope.generation(), scope.authEpoch()); var r = s.executeQuery()) {
            if (!r.next()) throw new ManagedProblem(NOT_FOUND);
            return new HistorySnapshot(scope, r.getString(4), sequence, r.getLong(1), r.getLong(2), r.getLong(3));
        }
    }
    public HistoryRead readHistory(ManagedScope scope, long sequence, int rounds) {
        if (rounds < 0 || rounds > 1000) throw new ManagedProblem(INVALID_INPUT);
        return store.transaction(c -> {
            var snapshot = snapshot(c, scope, sequence);
            if (rounds == 0) return new HistoryRead(snapshot, List.of());
            var pairs = new ArrayList<Pair>();
            try (var s = prepare(c, HISTORY_SQL,
                    scope.userId(), scope.bindingId(), snapshot.conversationId(), sequence, rounds); var r = s.executeQuery()) {
                while (r.next()) pairs.add(new Pair(r.getLong(1), r.getString(2), r.getString(3)));
            }
            Collections.reverse(pairs);
            return new HistoryRead(snapshot, pairs);
        });
    }
    public List<DialogueMessage> history(ManagedScope scope, long sequence, int rounds) {
        return readHistory(scope, sequence, rounds).messages();
    }
    /** Bounded compatibility entry for diagnostic callers; runtime always supplies its configuration snapshot. */
    public List<DialogueMessage> history(ManagedScope scope, long sequence) { return history(scope, sequence, 1000); }
    public Optional<Work> claim(ManagedScope scope) {
        return store.transaction(c -> {
            ManagedUsers.lockUser(c, scope.userId());
            ManagedUsers.authorize(c, scope);
            if (scalar(c, "SELECT count(*) FROM turn WHERE user_id=? AND stage IN ('PROCESSING','SENDING')", scope.userId()) != 0) return Optional.empty();
            try (var s = prepare(c, "SELECT t.event_sequence,t.stage,t.reply_text,t.client_id FROM turn t JOIN inbound_event e ON e.sequence=t.event_sequence "
                    + "WHERE t.user_id=? AND t.binding_id=? AND e.generation=? AND e.auth_epoch=? AND t.stage IN ('RECEIVED','RESPONSE_READY') ORDER BY t.event_sequence LIMIT 1",
                    scope.userId(), scope.bindingId(), scope.generation(), scope.authEpoch()); var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                long sequence = r.getLong(1); boolean sending = r.getString(2).equals("RESPONSE_READY");
                String reply = r.getString(3); String clientId = r.getString(4);
                if (update(c, "UPDATE turn SET stage=?,runtime_epoch=?,updated_at=? WHERE event_sequence=? AND stage=?",
                        sending ? "SENDING" : "PROCESSING", store.epoch(), clock.millis(), sequence, sending ? "RESPONSE_READY" : "RECEIVED") != 1)
                    throw new ManagedProblem(CONFLICT);
                return Optional.of(new Work(scope, event(c, scope, sequence), sending, reply, clientId));
            }
        });
    }
    public void saveReply(ManagedScope scope, long sequence, String reply, boolean modelSucceeded) {
        if (blank(reply)) throw new ManagedProblem(INVALID_INPUT);
        store.transaction(c -> {
            ManagedUsers.lockUser(c, scope.userId());
            ManagedUsers.authorize(c, scope); event(c, scope, sequence);
            if (update(c, "UPDATE turn SET stage='RESPONSE_READY',reply_text=?,model_succeeded=?,client_id=?,updated_at=? WHERE event_sequence=? AND stage='PROCESSING' AND runtime_epoch=?",
                    reply, modelSucceeded ? 1 : 0, UUID.randomUUID().toString(), clock.millis(), sequence, store.epoch()) != 1) throw new ManagedProblem(CONFLICT);
            return null;
        });
    }
    /** The external send may already have occurred. Persist its outcome even if authorization changed. */
    public Optional<Delivered> finishSend(ManagedScope originalScope, long sequence, Delivery delivery) {
        Objects.requireNonNull(delivery);
        return store.transaction(c -> {
            ManagedUsers.lockUser(c, originalScope.userId());
            var event = event(c, originalScope, sequence);
            long oldRevision = scalar(c, "SELECT history_revision FROM conversation WHERE id=? FOR UPDATE", event.conversationId());
            String stage = switch (delivery) { case CONFIRMED -> "SENT"; case FAILED -> "SEND_FAILED"; case UNKNOWN -> "DELIVERY_UNKNOWN"; };
            if (update(c, "UPDATE turn SET stage=?,updated_at=? WHERE event_sequence=? AND stage='SENDING' AND runtime_epoch=?",
                    stage, clock.millis(), sequence, store.epoch()) != 1) return Optional.empty();
            if (delivery != Delivery.CONFIRMED || event.kind() != Kind.CHAT) return Optional.empty();
            String reply;
            try (var s = prepare(c, "SELECT reply_text FROM turn WHERE event_sequence=? AND model_succeeded=1", sequence); var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                reply = r.getString(1);
            }
            update(c, "UPDATE conversation SET history_revision=history_revision+1,confirmed_turn_count=confirmed_turn_count+1,last_confirmed_sequence=GREATEST(last_confirmed_sequence,?) WHERE id=?",
                    sequence, event.conversationId());
            try (var s = prepare(c, "SELECT history_revision,confirmed_turn_count,last_confirmed_sequence FROM conversation WHERE id=?", event.conversationId()); var r = s.executeQuery()) {
                if (!r.next()) throw new ManagedProblem(DATABASE_UNAVAILABLE);
                return Optional.of(new Delivered(originalScope, event.conversationId(), oldRevision, r.getLong(1), r.getLong(2), r.getLong(3),
                        new Pair(sequence, event.text(), reply)));
            }
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

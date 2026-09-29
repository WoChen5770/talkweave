package io.github.wochen5770.talkweave.persistence;

import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import io.github.wochen5770.talkweave.runtime.AssistantProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import static io.github.wochen5770.talkweave.persistence.StorageProblem.Reason.*;

/** Durable local queue. All transactions are SQL-only; returned work runs outside the connection lock. */
public final class ConversationRepository {
    public enum Kind { CHAT, COMMAND, NOTICE }
    public enum Stage { RECEIVED, PROCESSING, RESPONSE_READY, SENDING, SENT, FAILED, INTERRUPTED, SEND_FAILED, DELIVERY_UNKNOWN }
    public enum ModelStatus { NONE, RUNNING, SUCCEEDED, FAILED, INTERRUPTED }
    public enum Delivery { CONFIRMED, FAILED, UNKNOWN }
    public enum Intake { READY, BACKLOG, LOW_DISK, UNAVAILABLE, NO_SESSION }
    public record Binding(String botId, String ownerId) {
        public Binding {
            if (blank(botId) != blank(ownerId)) throw new IllegalArgumentException("Both binding identifiers are required");
        }
        boolean permits(String bot, String sender) { return !blank(botId) && botId.equals(bot) && ownerId.equals(sender); }
        @Override public String toString() { return "Binding[REDACTED]"; }
    }
    public record Session(String botId, String origin, String token, String scanUserId, long generation, String cursor, boolean active) {
        @Override public String toString() { return "Session[generation=" + generation + ", active=" + active + "]"; }
    }
    /** Normalized transport input; text and context are discarded unless the binding authorizes the message. */
    public record Inbound(String messageId, String senderId, String text, String contextToken, boolean group,
                          boolean userMessage, boolean complete, boolean supportedText) {
        @Override public String toString() { return "Inbound[REDACTED]"; }
    }
    public record Work(long sequence, String botId, String senderId, long generation, String conversationId,
                       String text, String contextToken, Kind kind, Stage stage, ModelStatus modelStatus,
                       String replyText, String clientId) {
        @Override public String toString() { return "Work[sequence=" + sequence + ", kind=" + kind + ", stage=" + stage + "]"; }
    }

    private static final String PENDING = "('RECEIVED','PROCESSING','RESPONSE_READY','SENDING')";
    private static final String WORK_SELECT = "SELECT t.*,e.generation,e.text,e.context_token FROM turn t JOIN inbound_event e ON e.sequence=t.event_sequence ";
    private final SqliteStore store;
    private final Binding binding;
    private final int maxPending;
    private final long minimumFreeBytes;
    private final LongSupplier freeBytes;
    private final Runnable beforeBatchCommit;

    public ConversationRepository(SqliteStore store, Binding binding, AssistantProperties.Storage config) {
        this(store, binding, config, store::usableSpace, () -> { });
    }
    ConversationRepository(SqliteStore store, Binding binding, AssistantProperties.Storage config,
                           LongSupplier freeBytes, Runnable beforeBatchCommit) {
        this.store = Objects.requireNonNull(store);
        this.binding = Objects.requireNonNull(binding);
        this.maxPending = config.maxPendingEvents();
        this.minimumFreeBytes = config.minFreeBytes();
        this.freeBytes = freeBytes;
        this.beforeBatchCommit = beforeBatchCommit;
    }

    /** Caller supplies credentials validated by the channel; old generation responses cannot replace these. */
    public Session installSession(String botId, String origin, String token, String scanUserId) {
        requireText(botId); requireText(origin); requireText(token);
        return store.transaction(c -> installSession(c, botId, origin, token, scanUserId));
    }
    public Optional<Session> installSessionIfGeneration(long expected, String botId, String origin, String token, String scanUserId) {
        requireText(botId); requireText(origin); requireText(token);
        return store.transaction(c -> {
            Session old = session(c);
            if ((old == null ? 0 : old.generation()) != expected) return Optional.empty();
            return Optional.of(installSession(c, botId, origin, token, scanUserId));
        });
    }
    private static Session installSession(Connection c, String botId, String origin, String token, String scanner) throws SQLException {
        Session old = session(c);
        long generation = old == null ? 1 : Math.addExact(old.generation(), 1);
        String cursor = old != null && old.botId().equals(botId) ? old.cursor() : "";
        update(c, "INSERT INTO channel_state(slot,bot_id,origin,token,scan_user_id,generation,cursor,active,updated_at) VALUES(1,?,?,?,?,?,?,1,?) "
                + "ON CONFLICT(slot) DO UPDATE SET bot_id=excluded.bot_id,origin=excluded.origin,token=excluded.token,scan_user_id=excluded.scan_user_id,generation=excluded.generation,cursor=excluded.cursor,active=1,updated_at=excluded.updated_at",
                botId, origin, token, scanner == null ? "" : scanner, generation, cursor, now());
        return session(c);
    }
    public boolean isBound(Session session) { return session != null && session.active() && binding.permits(session.botId(), binding.ownerId()); }
    public Optional<Session> session() { return store.transaction(c -> Optional.ofNullable(session(c))); }
    public boolean invalidateSession(long generation) {
        return store.transaction(c -> update(c, "UPDATE channel_state SET active=0,updated_at=? WHERE slot=1 AND generation=?", now(), generation) == 1);
    }

    public Intake intakeState() {
        try {
            checkDisk();
            return store.transaction(c -> {
                Session session = session(c);
                if (session == null || !session.active()) return Intake.NO_SESSION;
                update(c, "UPDATE schema_version SET applied_at=applied_at WHERE version=2"); // Verify writes before polling.
                return pending(c) >= maxPending ? Intake.BACKLOG : Intake.READY;
            });
        } catch (StorageProblem failure) { return failure.reason() == LOW_DISK ? Intake.LOW_DISK : Intake.UNAVAILABLE; }
    }

    /** Returns false for a stale generation/cursor; any storage or capacity failure rolls back the entire batch. */
    public boolean acceptBatch(long generation, String previousCursor, String nextCursor, List<Inbound> messages) {
        Objects.requireNonNull(previousCursor); Objects.requireNonNull(nextCursor);
        List<Inbound> batch = List.copyOf(messages);
        checkDisk();
        return store.transaction(c -> {
            Session current = session(c);
            if (current == null || !current.active() || current.generation() != generation || !current.cursor().equals(previousCursor)) return false;
            if (pending(c) >= maxPending) throw new StorageProblem(BACKLOG);
            for (Inbound message : batch) insertInbound(c, current, message);
            if (pending(c) > maxPending) throw new StorageProblem(BACKLOG);
            beforeBatchCommit.run(); // Test-only fault point, no callback supplied by production code.
            update(c, "UPDATE channel_state SET cursor=?,updated_at=? WHERE slot=1", nextCursor, now());
            return true;
        });
    }

    private void insertInbound(Connection c, Session current, Inbound message) throws SQLException {
        String reason = null;
        String disposition = "ACCEPTED";
        if (!message.userMessage() || !message.complete()) reason = "NOT_FINAL_USER_MESSAGE";
        else if (message.group()) reason = "GROUP_UNSUPPORTED";
        else if (!binding.permits(current.botId(), message.senderId())) reason = "UNAUTHORIZED";
        if (reason != null) disposition = "IGNORED";
        else if (blank(message.messageId()) || blank(message.contextToken())) {
            reason = blank(message.messageId()) ? "MISSING_MESSAGE_ID" : "MISSING_CONTEXT";
            disposition = "QUARANTINED";
        }
        boolean accepted = disposition.equals("ACCEPTED");
        boolean text = message.supportedText() && !blank(message.text());
        // Intermediate updates must not consume the unique key of a later complete message.
        String id = message.complete() && message.userMessage() && !blank(message.messageId()) ? message.messageId() : null;
        int inserted = update(c, "INSERT INTO inbound_event(bot_id,message_id,sender_id,generation,text,context_token,content_kind,disposition,diagnostic,received_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(bot_id,message_id) DO NOTHING", current.botId(), id,
                message.senderId(), current.generation(), accepted && text ? message.text() : null,
                accepted ? message.contextToken() : null, text ? "TEXT" : "UNSUPPORTED", disposition, reason, now());
        if (inserted == 1 && accepted) {
            long sequence = scalar(c, "SELECT last_insert_rowid()");
            Kind kind = !text ? Kind.NOTICE : message.text().startsWith("/") ? Kind.COMMAND : Kind.CHAT;
            update(c, "INSERT INTO turn(event_sequence,bot_id,owner_id,kind,stage,model_status,delivery_status,updated_at) VALUES(?,?,?,?,'RECEIVED','NONE','NONE',?)",
                    sequence, current.botId(), message.senderId(), kind.name(), now());
        }
    }

    /** A RESPONSE_READY claim durably records SENDING before returning; never replay it after a crash. */
    public Optional<Work> claimNext() {
        checkDisk();
        return store.transaction(c -> {
            Session current = session(c);
            if (current == null || !current.active()) return Optional.empty();
            if (scalar(c, "SELECT count(*) FROM turn WHERE stage IN ('PROCESSING','SENDING')") != 0) return Optional.empty();
            while (true) {
                Work candidate = firstPending(c);
                if (candidate == null) return Optional.empty();
                if (!authorized(current, candidate)) {
                    if (candidate.stage() == Stage.RESPONSE_READY) {
                        update(c, "UPDATE turn SET stage='SEND_FAILED',delivery_status='FAILED',diagnostic='STALE_AUTHORIZATION',updated_at=? WHERE event_sequence=?", now(), candidate.sequence());
                    } else {
                        update(c, "UPDATE turn SET stage='INTERRUPTED',diagnostic='STALE_AUTHORIZATION',updated_at=? WHERE event_sequence=?", now(), candidate.sequence());
                    }
                    continue;
                }
                if (candidate.stage() == Stage.RESPONSE_READY) {
                    update(c, "UPDATE turn SET stage='SENDING',delivery_status='SENDING',updated_at=? WHERE event_sequence=?", now(), candidate.sequence());
                } else {
                    String conversation = currentConversation(c, candidate.botId(), candidate.senderId());
                    update(c, "UPDATE turn SET conversation_id=?,stage='PROCESSING',model_status=?,updated_at=? WHERE event_sequence=?",
                            conversation, candidate.kind() == Kind.CHAT ? "RUNNING" : "NONE", now(), candidate.sequence());
                }
                return Optional.of(work(c, candidate.sequence()));
            }
        });
    }

    public void saveReply(long sequence, ModelStatus modelStatus, String fullResult, String replyText) {
        requireText(replyText);
        if (modelStatus == ModelStatus.SUCCEEDED) requireText(fullResult);
        if (modelStatus != ModelStatus.NONE && modelStatus != ModelStatus.SUCCEEDED && modelStatus != ModelStatus.FAILED) throw new StorageProblem(INVALID_TRANSITION);
        store.transaction(c -> {
            Work work = requireStage(c, sequence, Stage.PROCESSING);
            if (work.kind() == Kind.CHAT ? modelStatus == ModelStatus.NONE : modelStatus != ModelStatus.NONE) throw new StorageProblem(INVALID_TRANSITION);
            saveReply(c, sequence, modelStatus, fullResult, replyText);
            return null;
        });
    }
    private static void saveReply(Connection c, long sequence, ModelStatus status, String result, String reply) throws SQLException {
        update(c, "UPDATE turn SET stage='RESPONSE_READY',model_status=?,delivery_status='READY',full_result=?,reply_text=?,client_id=?,updated_at=? WHERE event_sequence=?",
                status.name(), result, reply, "reply-" + UUID.randomUUID(), now(), sequence);
    }

    /** The conversation switch and the confirmation's durable response are a single transaction. */
    public void completeNewConversation(long sequence, String confirmation) {
        requireText(confirmation);
        store.transaction(c -> {
            Work work = requireStage(c, sequence, Stage.PROCESSING);
            if (work.kind() != Kind.COMMAND || !"/new".equals(work.text())) throw new StorageProblem(INVALID_TRANSITION);
            requireAuthorization(c, work);
            update(c, "UPDATE conversation SET is_current=0 WHERE bot_id=? AND owner_id=? AND is_current=1", work.botId(), work.senderId());
            String next = currentConversation(c, work.botId(), work.senderId());
            update(c, "UPDATE turn SET conversation_id=? WHERE event_sequence=?", next, sequence);
            saveReply(c, sequence, ModelStatus.NONE, null, confirmation);
            return null;
        });
    }

    public void failProcessing(long sequence) {
        store.transaction(c -> {
            Work work = requireStage(c, sequence, Stage.PROCESSING);
            update(c, "UPDATE turn SET stage='FAILED',model_status=?,diagnostic='PROCESSING_FAILED',updated_at=? WHERE event_sequence=?",
                    work.kind() == Kind.CHAT ? "FAILED" : "NONE", now(), sequence);
            return null;
        });
    }
    public void interruptProcessing(long sequence) {
        store.transaction(c -> {
            Work work = requireStage(c, sequence, Stage.PROCESSING);
            update(c, "UPDATE turn SET stage='INTERRUPTED',model_status=?,diagnostic='SHUTDOWN_DURING_PROCESSING',updated_at=? WHERE event_sequence=?",
                    work.kind() == Kind.CHAT ? "INTERRUPTED" : "NONE", now(), sequence);
            return null;
        });
    }
    public void finishSend(long sequence, Delivery result) {
        Objects.requireNonNull(result);
        store.transaction(c -> {
            Work work = requireStage(c, sequence, Stage.SENDING);
            Delivery safeResult = authorized(session(c), work) ? result : Delivery.UNKNOWN;
            String stage = switch (safeResult) { case CONFIRMED -> "SENT"; case FAILED -> "SEND_FAILED"; case UNKNOWN -> "DELIVERY_UNKNOWN"; };
            update(c, "UPDATE turn SET stage=?,delivery_status=?,updated_at=? WHERE event_sequence=?", stage, safeResult.name(), now(), sequence);
            return null;
        });
    }

    /** Only confirmed ordinary turns; truncation is represented by the actual sent reply, not full_result. */
    public List<DialogueMessage> history(Work current) {
        return store.transaction(c -> {
            requireAuthorization(c, current);
            Work persisted = work(c, current.sequence());
            if (persisted == null || !Objects.equals(persisted.conversationId(), current.conversationId())) throw new StorageProblem(UNAUTHORIZED);
            var result = new ArrayList<DialogueMessage>();
            try (var sql = prepare(c, "SELECT e.text,t.reply_text FROM turn t JOIN inbound_event e ON e.sequence=t.event_sequence "
                    + "WHERE t.conversation_id=? AND t.bot_id=? AND t.owner_id=? AND t.kind='CHAT' AND t.model_status='SUCCEEDED' "
                    + "AND t.stage='SENT' AND t.delivery_status='CONFIRMED' AND t.event_sequence<? ORDER BY t.event_sequence",
                    current.conversationId(), current.botId(), current.senderId(), current.sequence()); var rows = sql.executeQuery()) {
                while (rows.next()) {
                    result.add(new DialogueMessage(DialogueMessage.Role.USER, rows.getString(1)));
                    result.add(new DialogueMessage(DialogueMessage.Role.ASSISTANT, rows.getString(2)));
                }
            }
            return List.copyOf(result);
        });
    }
    public Session sessionForActivity(Work claimed) {
        return store.transaction(c -> {
            Work persisted = requireStage(c, claimed.sequence(), Stage.PROCESSING);
            requireAuthorization(c, persisted);
            if (!persisted.equals(claimed)) throw new StorageProblem(INVALID_TRANSITION);
            return session(c);
        });
    }
    public Session sessionForSend(Work claimed) {
        return store.transaction(c -> {
            Work persisted = requireStage(c, claimed.sequence(), Stage.SENDING);
            requireAuthorization(c, persisted);
            if (!persisted.equals(claimed)) throw new StorageProblem(INVALID_TRANSITION);
            return session(c);
        });
    }
    public long pendingCount() { return store.transaction(ConversationRepository::pending); }
    public long uncertainCount() { return store.transaction(c -> scalar(c, "SELECT count(*) FROM turn WHERE stage IN ('INTERRUPTED','DELIVERY_UNKNOWN')")); }

    private boolean authorized(Session current, Work work) {
        return current != null && current.active() && binding.permits(work.botId(), work.senderId())
                && current.botId().equals(work.botId()) && current.generation() == work.generation() && !blank(work.contextToken());
    }
    private void requireAuthorization(Connection c, Work work) throws SQLException {
        if (!authorized(session(c), work)) throw new StorageProblem(UNAUTHORIZED);
    }
    private void checkDisk() {
        if (freeBytes.getAsLong() < minimumFreeBytes) throw new StorageProblem(LOW_DISK);
    }
    private static Session session(Connection c) throws SQLException {
        try (var sql = c.createStatement(); var row = sql.executeQuery("SELECT * FROM channel_state WHERE slot=1")) {
            return row.next() ? new Session(row.getString("bot_id"), row.getString("origin"), row.getString("token"),
                    row.getString("scan_user_id"), row.getLong("generation"), row.getString("cursor"), row.getInt("active") == 1) : null;
        }
    }
    private static Work firstPending(Connection c) throws SQLException {
        try (var sql = c.createStatement(); var row = sql.executeQuery(WORK_SELECT + "WHERE t.stage IN ('RECEIVED','RESPONSE_READY') ORDER BY event_sequence LIMIT 1")) {
            return row.next() ? mapWork(row) : null;
        }
    }
    private static Work work(Connection c, long sequence) throws SQLException {
        try (var sql = prepare(c, WORK_SELECT + "WHERE event_sequence=?", sequence); var row = sql.executeQuery()) {
            return row.next() ? mapWork(row) : null;
        }
    }
    private static Work requireStage(Connection c, long sequence, Stage stage) throws SQLException {
        Work work = work(c, sequence);
        if (work == null || work.stage() != stage) throw new StorageProblem(INVALID_TRANSITION);
        return work;
    }
    private static Work mapWork(ResultSet row) throws SQLException {
        return new Work(row.getLong("event_sequence"), row.getString("bot_id"), row.getString("owner_id"), row.getLong("generation"),
                row.getString("conversation_id"), row.getString("text"), row.getString("context_token"), Kind.valueOf(row.getString("kind")),
                Stage.valueOf(row.getString("stage")), ModelStatus.valueOf(row.getString("model_status")), row.getString("reply_text"), row.getString("client_id"));
    }
    private static String currentConversation(Connection c, String bot, String owner) throws SQLException {
        try (var sql = prepare(c, "SELECT id FROM conversation WHERE bot_id=? AND owner_id=? AND is_current=1", bot, owner); var row = sql.executeQuery()) {
            if (row.next()) return row.getString(1);
        }
        String id = UUID.randomUUID().toString();
        update(c, "INSERT INTO conversation(id,bot_id,owner_id,is_current,created_at) VALUES(?,?,?,1,?)", id, bot, owner, now());
        return id;
    }
    private static long pending(Connection c) throws SQLException { return scalar(c, "SELECT count(*) FROM turn WHERE stage IN " + PENDING); }
    private static long scalar(Connection c, String query) throws SQLException {
        try (var sql = c.createStatement(); var row = sql.executeQuery(query)) { row.next(); return row.getLong(1); }
    }
    private static int update(Connection c, String query, Object... values) throws SQLException {
        try (var sql = prepare(c, query, values)) { return sql.executeUpdate(); }
    }
    private static PreparedStatement prepare(Connection c, String query, Object... values) throws SQLException {
        var sql = c.prepareStatement(query);
        try { for (int i = 0; i < values.length; i++) sql.setObject(i + 1, values[i]); return sql; }
        catch (SQLException failure) { sql.close(); throw failure; }
    }
    private static String now() { return Instant.now().toString(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireText(String value) { if (blank(value)) throw new IllegalArgumentException("Nonempty value required"); }
}
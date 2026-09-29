package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.model.TokenUsage;
import java.time.Clock;
import java.util.*;
import static io.github.wochen5770.talkweave.managed.persistence.Sql.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Accounting is tied to a request attempt, independently of eventual reply delivery. */
public final class ManagedUsage {
    public enum Outcome { SUCCEEDED, FAILED, UNKNOWN, CANCELLED }
    public record Summary(long attempts, long unreportedAttempts, long incompleteAttempts, long invalidAttempts, Long knownInputTokens,
                          Long knownOutputTokens, Long knownCachedInputTokens, long ratioCoveredAttempts,
                          Double coveredCacheHitRatio) { }
    private final ManagedStore store;
    private final Clock clock;
    public ManagedUsage(ManagedStore store, Clock clock) { this.store = store; this.clock = clock; }

    public String begin(ManagedScope scope, io.github.wochen5770.talkweave.model.ModelRequestContext context) {
        if (!scope.userId().equals(context.userId()) || !scope.bindingId().equals(context.bindingId())
                || scope.generation() != context.generation() || scope.authEpoch() != context.authEpoch())
            throw new ManagedProblem(UNAUTHORIZED);
        return begin(scope, context.eventSequence(), context.modelVersion(), context.conversationId());
    }
    public String begin(ManagedScope scope, long eventSequence, long modelVersion) {
        return begin(scope, eventSequence, modelVersion, null);
    }
    private String begin(ManagedScope scope, long eventSequence, long modelVersion, String expectedConversation) {
        return store.transaction(c -> {
            ManagedUsers.authorize(c, scope);
            var event = ManagedConversations.event(c, scope, eventSequence);
            if (expectedConversation != null && !expectedConversation.equals(event.conversationId())) throw new ManagedProblem(UNAUTHORIZED);
            if (event.kind() != ManagedConversations.Kind.CHAT || scalar(c, "SELECT count(*) FROM turn WHERE event_sequence=? AND stage='PROCESSING'", eventSequence) != 1) throw new ManagedProblem(CONFLICT);
            String id = UUID.randomUUID().toString();
            long number = scalar(c, "SELECT count(*)+1 FROM model_attempt WHERE event_sequence=?", eventSequence);
            update(c, "INSERT INTO model_attempt(id,event_sequence,user_id,binding_id,conversation_id,model_version,attempt_number,started_at,outcome) VALUES (?,?,?,?,?,?,?,?,'STARTED')",
                    id, eventSequence, scope.userId(), scope.bindingId(), event.conversationId(), modelVersion, number, clock.millis());
            return id;
        });
    }
    public void record(String attemptId, Outcome outcome, TokenUsage usage) {
        Objects.requireNonNull(outcome); Objects.requireNonNull(usage);
        store.transaction(c -> {
            if (scalar(c, "SELECT count(*) FROM model_attempt WHERE id=?", attemptId) != 1) throw new ManagedProblem(NOT_FOUND);
            int written = update(c, "INSERT INTO model_usage(attempt_id,input_tokens,output_tokens,cached_input_tokens,status) VALUES (?,?,?,?,?) ON CONFLICT(attempt_id) DO NOTHING",
                    attemptId, usage.inputTokens(), usage.outputTokens(), usage.cachedInputTokens(), usage.status().name());
            if (written != 0) update(c, "UPDATE model_attempt SET outcome=? WHERE id=?", outcome.name(), attemptId);
            return null;
        });
    }
    /** Administrator-only metadata, including archived bindings for historical accounting; never message bodies or credentials. */
    public record Conversation(long sequence, String id, long bindingVersion, boolean bindingCurrent,
                               boolean current, long createdAt, Long endedAt, Long lastActivity) { }
    public List<Conversation> conversations(String userId, long beforeExclusive, int limit) {
        if (beforeExclusive < 1 || limit < 1 || limit > 100) throw new ManagedProblem(INVALID_INPUT);
        return store.transaction(c -> {
            ManagedUsers.user(c, userId);
            var result = new ArrayList<Conversation>();
            try (var statement = prepare(c, "SELECT v.rowid sequence,v.id,v.current,v.created_at,v.ended_at,v.last_user_message_at,"
                    + "b.version,b.active FROM conversation v JOIN binding b ON b.id=v.binding_id AND b.user_id=v.user_id "
                    + "WHERE v.user_id=? AND v.rowid<? ORDER BY v.rowid DESC LIMIT ?", userId, beforeExclusive, limit);
                 var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Conversation(rows.getLong("sequence"), rows.getString("id"), rows.getLong("version"),
                        rows.getInt("active") == 1, rows.getInt("current") == 1, rows.getLong("created_at"),
                        nullableLong(rows, "ended_at"), nullableLong(rows, "last_user_message_at")));
            }
            return List.copyOf(result);
        });
    }
    public Summary summary(String userId, String conversationId, long fromInclusive, long toExclusive) {
        if (fromInclusive < 0 || fromInclusive >= toExclusive) throw new ManagedProblem(INVALID_INPUT);
        return store.transaction(c -> {
            ManagedUsers.user(c, userId);
            if (conversationId != null && scalar(c, "SELECT count(*) FROM conversation WHERE id=? AND user_id=?", conversationId, userId) != 1) throw new ManagedProblem(NOT_FOUND);
            String sql = "SELECT COUNT(*) attempts, SUM(CASE WHEN u.status IS NULL OR u.status='UNKNOWN' THEN 1 ELSE 0 END) unreported, "
                    + "SUM(CASE WHEN u.status IS NULL OR u.status!='REPORTED' THEN 1 ELSE 0 END) incomplete, "
                    + "SUM(CASE WHEN u.status='INVALID' THEN 1 ELSE 0 END) invalid, "
                    + "SUM(u.input_tokens) inputs, SUM(u.output_tokens) outputs, SUM(u.cached_input_tokens) cached, "
                    + "SUM(CASE WHEN u.input_tokens IS NOT NULL AND u.cached_input_tokens IS NOT NULL AND u.status!='INVALID' THEN 1 ELSE 0 END) covered, "
                    + "SUM(CASE WHEN u.input_tokens IS NOT NULL AND u.cached_input_tokens IS NOT NULL AND u.status!='INVALID' THEN u.input_tokens ELSE 0 END) ratio_inputs, "
                    + "SUM(CASE WHEN u.input_tokens IS NOT NULL AND u.cached_input_tokens IS NOT NULL AND u.status!='INVALID' THEN u.cached_input_tokens ELSE 0 END) ratio_cached "
                    + "FROM model_attempt a LEFT JOIN model_usage u ON u.attempt_id=a.id WHERE a.user_id=? AND a.started_at>=? AND a.started_at<?"
                    + (conversationId == null ? "" : " AND a.conversation_id=?");
            Object[] parameters = conversationId == null ? new Object[]{userId, fromInclusive, toExclusive} : new Object[]{userId, fromInclusive, toExclusive, conversationId};
            try (var s = prepare(c, sql, parameters); var r = s.executeQuery()) {
                r.next(); long denominator = r.getLong("ratio_inputs");
                return new Summary(r.getLong("attempts"), r.getLong("unreported"), r.getLong("incomplete"), r.getLong("invalid"),
                        nullableLong(r, "inputs"), nullableLong(r, "outputs"), nullableLong(r, "cached"), r.getLong("covered"),
                        denominator > 0 ? (double) r.getLong("ratio_cached") / denominator : null);
            }
        });
    }
}
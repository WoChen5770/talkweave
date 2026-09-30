package io.github.wochen5770.talkweave.managed.cache;

import com.fasterxml.jackson.databind.*;
import io.github.wochen5770.talkweave.managed.persistence.ManagedConversations.*;
import io.github.wochen5770.talkweave.managed.persistence.ManagedScope;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A complete bounded suffix of confirmed raw pairs, never a rendered prompt. */
public record HistoryWindow(int format, ManagedScope scope, String conversationId, long modelVersion,
                            long revision, long count, long watermark, int capacity, List<Pair> pairs) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    public HistoryWindow {
        pairs = List.copyOf(pairs);
        if (format != 1 || scope == null || conversationId == null || modelVersion < 1 || revision < 0
                || count < 0 || watermark < 0 || capacity < 1 || capacity > 1000 || revision != count
                || pairs.size() != Math.min((long) capacity, count)) throw new IllegalArgumentException("Invalid history window");
        long previous = 0;
        for (var pair : pairs) {
            if (pair.sequence() <= previous || pair.sequence() > watermark || pair.userText() == null || pair.replyText() == null)
                throw new IllegalArgumentException("Invalid history pair");
            previous = pair.sequence();
        }
        if ((count == 0 && watermark != 0) || (count > 0 && (pairs.isEmpty() || previous != watermark)))
            throw new IllegalArgumentException("Incomplete history window");
    }
    public static HistoryWindow from(HistoryRead read, long version, int rounds) {
        var s = read.snapshot();
        if (!s.latestTail()) throw new IllegalArgumentException("Historical slices cannot refill a tail window");
        return new HistoryWindow(1, s.scope(), s.conversationId(), version, s.revision(), s.count(), s.watermark(), rounds, read.pairs());
    }
    public boolean matches(HistorySnapshot s, long version, int rounds) {
        return scope.equals(s.scope()) && conversationId.equals(s.conversationId()) && modelVersion == version
                && revision == s.revision() && count == s.count() && watermark == s.watermark()
                && s.latestTail() && pairs.size() >= Math.min((long) rounds, count);
    }
    public HistoryRead read(HistorySnapshot snapshot, int rounds) {
        return new HistoryRead(snapshot, pairs.subList(Math.max(0, pairs.size() - rounds), pairs.size()));
    }
    public Optional<HistoryWindow> append(Delivered delivered) {
        if (!scope.equals(delivered.scope()) || !conversationId.equals(delivered.conversationId())
                || revision != delivered.oldRevision() || delivered.revision() != revision + 1
                || delivered.count() != count + 1 || delivered.pair().sequence() <= watermark
                || delivered.watermark() != delivered.pair().sequence()) return Optional.empty();
        var next = new ArrayList<>(pairs); next.add(delivered.pair());
        if (next.size() > capacity) next.removeFirst();
        return Optional.of(new HistoryWindow(1, scope, conversationId, modelVersion, delivered.revision(), delivered.count(),
                delivered.watermark(), capacity, next));
    }
    public String encode(int maxBytes) {
        try { return HistoryCacheFrame.encode(revision, pairs.size(), JSON.writeValueAsString(this), maxBytes); }
        catch (Exception invalid) { throw new IllegalArgumentException("Uncacheable history window"); }
    }
    public static HistoryWindow decode(String value, int maxBytes) {
        if (value == null || value.length() < 30 || value.length() > maxBytes || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || value.charAt(29) != '\n' || !value.substring(0, 29).matches("[0-9]{29}"))
            throw new IllegalArgumentException("Invalid history frame");
        try {
            var window = JSON.readValue(value.substring(30), HistoryWindow.class);
            if (Long.parseLong(value.substring(0, 19)) != window.revision() || Integer.parseInt(value.substring(19, 29)) != window.pairs().size())
                throw new IllegalArgumentException();
            return window;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid history frame"); }
    }
    @Override public String toString() { return "HistoryWindow[revision=" + revision + ", content=REDACTED]"; }
}

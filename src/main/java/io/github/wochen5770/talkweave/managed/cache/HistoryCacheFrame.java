package io.github.wochen5770.talkweave.managed.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Transport framing only; database authorization and full window validation remain mandatory. */
public final class HistoryCacheFrame {
    public static final String COMPARE_SET = resource("/cache/history-compare-set.lua");
    private HistoryCacheFrame() { }

    public static String encode(long revision, int coverage, String payload, int maxBytes) {
        if (revision < 0 || coverage < 0 || payload == null || maxBytes < 30)
            throw new IllegalArgumentException("Invalid history frame");
        if (payload.length() > maxBytes - 30) throw new IllegalArgumentException("Oversized history frame");
        String value = String.format(Locale.ROOT, "%019d%010d\n", revision, coverage) + payload;
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("Oversized history frame");
        return value;
    }

    private static String resource(String name) {
        try (var input = HistoryCacheFrame.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("History cache script unavailable"); }
    }
}

package io.github.personalassistant.conversation;

import java.nio.charset.StandardCharsets;

public final class ReplyFormatter {
    private static final String MODEL_LIMIT = "\n[模型达到输出上限，回答可能不完整]";
    private static final String CHANNEL_LIMIT = "\n[回复过长，已截断]";
    private final int maxBytes;
    public ReplyFormatter(int maxBytes) {
        if (maxBytes < 128) throw new IllegalArgumentException("Reply budget must be at least 128 bytes");
        this.maxBytes = maxBytes;
    }
    public String format(String fullText, boolean modelTruncated) {
        if (fullText == null || fullText.isBlank()) throw new IllegalArgumentException("Reply must be nonempty");
        String suffix = modelTruncated ? MODEL_LIMIT : "";
        if (bytes(fullText) + bytes(suffix) <= maxBytes) return fullText + suffix;
        suffix += CHANNEL_LIMIT;
        int remaining = maxBytes - bytes(suffix);
        int end = 0;
        while (end < fullText.length()) {
            int codePoint = fullText.codePointAt(end);
            int width = Character.charCount(codePoint);
            int cost = bytes(fullText.substring(end, end + width));
            if (cost > remaining) break;
            remaining -= cost;
            end += width;
        }
        return fullText.substring(0, end) + suffix;
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
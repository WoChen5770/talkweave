package io.github.wochen5770.talkweave.runtime;

/** A safe external-service failure. No response body, URL, request or nested exception is exposed. */
public final class RemoteFailure extends RuntimeException {
    public enum Source { MODEL, WECHAT }
    public enum Kind { HTTP, JSON, BUSINESS, STALE_TOKEN, CONNECTION, TIMEOUT, INVALID_RESPONSE, UNSAFE_ENDPOINT }
    private final Source source;
    private final Kind kind;
    private final Integer code;
    public RemoteFailure(Source source, Kind kind) { this(source, kind, null); }
    public RemoteFailure(Source source, Kind kind, Integer code) {
        super("External operation failed: " + source + "/" + kind + (code == null ? "" : " (" + code + ")"));
        this.source = source;
        this.kind = kind;
        this.code = code;
    }
    public Source source() { return source; }
    public Kind kind() { return kind; }
    public Integer code() { return code; }
}

package io.github.personalassistant.runtime;

import java.util.UUID;
import org.slf4j.Logger;

/** Fixed labels only: callers cannot accidentally log a provider body, message or token. */
public final class SafeDiagnostics {
    public enum Operation { STARTUP, WECHAT_LOGIN, WECHAT_RECEIVE, WECHAT_SEND, MODEL_CALL }
    public enum Failure { CONFIGURATION, STORAGE, CONNECTION, TIMEOUT, HTTP, JSON, BUSINESS, STALE_TOKEN, INVALID_RESPONSE }
    private SafeDiagnostics() { }
    public static void failed(Logger logger, Operation operation, Failure category, UUID correlationId) {
        logger.warn("operation={} category={} correlation={}", operation, category, correlationId);
    }
}

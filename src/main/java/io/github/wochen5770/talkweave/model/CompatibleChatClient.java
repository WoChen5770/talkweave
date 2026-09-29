package io.github.wochen5770.talkweave.model;

import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.conversation.DialogueMessage;
import io.github.wochen5770.talkweave.runtime.AssistantProperties;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import static io.github.wochen5770.talkweave.runtime.RemoteFailure.Source.MODEL;
import static io.github.wochen5770.talkweave.runtime.RemoteFailure.Kind.*;

/** Minimal non-streaming adapter. Explicit retry boundaries; each dispatched attempt has an end-to-end deadline. */
public final class CompatibleChatClient implements AssistantService, AutoCloseable {
    private final OpenAiChatModel model;
    private final HttpClient http;
    private final AssistantProperties.Model config;
    private final Long modelVersion;
    private final ThreadLocal<CompatibleUsageCapture> activeCapture = new ThreadLocal<>();
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;
    private static final class RateLimited extends RuntimeException {
        final java.time.Duration wait;
        RateLimited(java.time.Duration wait) { super("Model request explicitly rate limited"); this.wait = wait; }
    }

    public CompatibleChatClient(AssistantProperties.Model config) { this(config, null); }

    public CompatibleChatClient(AssistantProperties.Model config, Long modelVersion) {
        if (modelVersion != null && modelVersion < 1) throw new IllegalArgumentException("Invalid model version");
        this.modelVersion = modelVersion;
        config.validate();
        this.config = config;
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.requestTimeout()).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(config.requestTimeout());
        var errorHandler = new ResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException {
                return !response.getStatusCode().is2xxSuccessful();
            }
            @Override public void handleError(java.net.URI uri, org.springframework.http.HttpMethod method,
                                              ClientHttpResponse response) throws IOException {
                if (response.getStatusCode().value() == 429) throw new RateLimited(retryAfter(response.getHeaders().getFirst("Retry-After"), java.time.Instant.now()));
                throw new RemoteFailure(MODEL, HTTP, response.getStatusCode().value());
            }
        };
        var api = OpenAiApi.builder().baseUrl(config.baseUri().toString()).apiKey(config.apiKey())
                .completionsPath("/v1/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(requests).requestInterceptor((request, body, execution) -> {
                    var response = execution.execute(request, body);
                    var capture = activeCapture.get();
                    if (capture == null) { response.close(); throw new RemoteFailure(MODEL, INVALID_RESPONSE); }
                    return capture.intercept(response);
                }))
                .responseErrorHandler(errorHandler).build();
        var options = OpenAiChatOptions.builder().model(config.name()).temperature(null)
                .maxTokens(config.outputBudget()).internalToolExecutionEnabled(false).build();
        model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .toolExecutionEligibilityPredicate((promptOptions, response) -> false).build();
    }

    @Override public Reply answer(List<DialogueMessage> messages) {
        return answerInternal(messages, ModelAttemptObserver.noop());
    }

    public Reply answer(ModelRequestContext context, List<DialogueMessage> messages, ModelAttemptObserver observer) {
        java.util.Objects.requireNonNull(context);
        java.util.Objects.requireNonNull(observer);
        if (modelVersion == null || modelVersion != context.modelVersion())
            throw new IllegalArgumentException("Model configuration snapshot mismatch");
        return answerInternal(messages, observer);
    }

    private Reply answerInternal(List<DialogueMessage> messages, ModelAttemptObserver observer) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("At least one message is required");
        List<Message> input = new ArrayList<>();
        for (var message : List.copyOf(messages)) {
            input.add(switch (message.role()) {
                case SYSTEM -> new SystemMessage(message.text());
                case USER -> new UserMessage(message.text());
                case ASSISTANT -> new AssistantMessage(message.text());
            });
        }
        long deadline = System.nanoTime() + config.totalTimeBudget().toNanos();
        for (int attempt = 0; ; attempt++) {
            if (closed || Thread.currentThread().isInterrupted()) throw new RemoteFailure(MODEL, CONNECTION);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new RemoteFailure(MODEL, TIMEOUT);
            // Persist and recheck authorization BEFORE dispatch. Observer exceptions are never retryable.
            observer.beforeAttempt(attempt + 1);
            var capture = new CompatibleUsageCapture();
            AttemptResult result = callOnce(input, capture, deadline);
            observer.afterAttempt(attempt + 1, result.outcome(), capture.usage);
            if (result.reply() != null) return result.reply();
            if (!result.retryable() || attempt >= config.maxRetries()) throw result.failure();
            java.time.Duration delay = result.delay() == null ? java.time.Duration.ofSeconds(Math.min(1L << attempt, 5)) : result.delay();
            remaining = deadline - System.nanoTime();
            if (remaining <= 0 || delay.compareTo(java.time.Duration.ofNanos(remaining)) >= 0) throw result.failure();
            try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay.toNanos()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RemoteFailure(MODEL, CONNECTION); }
        }
    }

    private record AttemptResult(Reply reply, RemoteFailure failure, boolean retryable,
                                 java.time.Duration delay, ModelAttemptObserver.Outcome outcome) {
        static AttemptResult failed(RemoteFailure failure, boolean retryable, java.time.Duration delay, ModelAttemptObserver.Outcome outcome) {
            return new AttemptResult(null, failure, retryable, delay, outcome);
        }
    }

    private AttemptResult callOnce(List<Message> input, CompatibleUsageCapture capture, long deadline) {
        var failed = ModelAttemptObserver.Outcome.FAILED;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return AttemptResult.failed(new RemoteFailure(MODEL, TIMEOUT), false, null, failed);
        java.util.concurrent.Future<org.springframework.ai.chat.model.ChatResponse> future;
        try {
            future = executor.submit(() -> {
                activeCapture.set(capture);
                try { return model.call(new Prompt(input)); }
                finally { activeCapture.remove(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            return AttemptResult.failed(new RemoteFailure(MODEL, CONNECTION), false, null, failed);
        }
        try {
            var response = future.get(Math.min(config.requestTimeout().toNanos(), remaining), java.util.concurrent.TimeUnit.NANOSECONDS);
            if (response != null && response.getResults() != null) {
                for (var generation : response.getResults()) {
                    String text = generation.getOutput() == null ? null : generation.getOutput().getText();
                    if (text != null && !text.isBlank()) {
                        boolean truncated = "length".equalsIgnoreCase(generation.getMetadata().getFinishReason());
                        return new AttemptResult(new Reply(text, truncated, capture.usage), null, false, null, ModelAttemptObserver.Outcome.SUCCEEDED);
                    }
                }
            }
            return AttemptResult.failed(new RemoteFailure(MODEL, INVALID_RESPONSE), false, null, failed);
        } catch (java.util.concurrent.TimeoutException failure) {
            future.cancel(true);
            return AttemptResult.failed(new RemoteFailure(MODEL, TIMEOUT), false, null, ModelAttemptObserver.Outcome.UNKNOWN);
        } catch (InterruptedException failure) {
            future.cancel(true); Thread.currentThread().interrupt();
            return AttemptResult.failed(new RemoteFailure(MODEL, CONNECTION), false, null, ModelAttemptObserver.Outcome.CANCELLED);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable root = failure.getCause();
            RateLimited limited = find(root, RateLimited.class);
            if (limited != null) return AttemptResult.failed(new RemoteFailure(MODEL, HTTP, 429), true, limited.wait, failed);
            if (definitelyUnsent(root)) return AttemptResult.failed(new RemoteFailure(MODEL, CONNECTION), true, null, failed);
            RemoteFailure safe = find(root, RemoteFailure.class);
            if (safe != null) return AttemptResult.failed(safe, false, null, failed);
            if (find(root, HttpTimeoutException.class) != null || find(root, java.util.concurrent.TimeoutException.class) != null)
                return AttemptResult.failed(new RemoteFailure(MODEL, TIMEOUT), false, null, ModelAttemptObserver.Outcome.UNKNOWN);
            return AttemptResult.failed(new RemoteFailure(MODEL, INVALID_RESPONSE), false, null, ModelAttemptObserver.Outcome.UNKNOWN);
        }
    }
    static boolean definitelyUnsent(Throwable failure) {
        return find(failure, java.net.http.HttpConnectTimeoutException.class) != null
                || find(failure, java.net.ConnectException.class) != null
                || find(failure, java.net.UnknownHostException.class) != null
                || find(failure, java.nio.channels.UnresolvedAddressException.class) != null;
    }
    static java.time.Duration retryAfter(String value, java.time.Instant now) {
        if (value == null || value.length() > 128) return null;
        try {
            if (value.matches("[0-9]+")) return java.time.Duration.ofSeconds(Long.parseLong(value));
            var when = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            var delay = java.time.Duration.between(now, when);
            return delay.isNegative() ? java.time.Duration.ZERO : delay;
        } catch (RuntimeException ignored) { return null; }
    }
    private static <T extends Throwable> T find(Throwable root, Class<T> type) {
        for (int depth = 0; root != null && depth < 32; depth++, root = root.getCause()) if (type.isInstance(root)) return type.cast(root);
        return null;
    }
    @Override public void close() { closed = true; executor.shutdownNow(); http.shutdownNow(); }
}

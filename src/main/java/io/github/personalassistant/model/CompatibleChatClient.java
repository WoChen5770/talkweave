package io.github.personalassistant.model;

import io.github.personalassistant.assistant.AssistantService;
import io.github.personalassistant.conversation.DialogueMessage;
import io.github.personalassistant.runtime.AssistantProperties;
import io.github.personalassistant.runtime.RemoteFailure;
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
import static io.github.personalassistant.runtime.RemoteFailure.Source.MODEL;
import static io.github.personalassistant.runtime.RemoteFailure.Kind.*;

/** Minimal non-streaming adapter. Explicit retry boundaries; each dispatched attempt has an end-to-end deadline. */
public final class CompatibleChatClient implements AssistantService, AutoCloseable {
    private final OpenAiChatModel model;
    private final HttpClient http;
    private final AssistantProperties.Model config;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;
    private static final class RateLimited extends RuntimeException {
        final java.time.Duration wait;
        RateLimited(java.time.Duration wait) { super("Model request explicitly rate limited"); this.wait = wait; }
    }

    public CompatibleChatClient(AssistantProperties.Model config) {
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
                .restClientBuilder(RestClient.builder().requestFactory(requests))
                .responseErrorHandler(errorHandler).build();
        var options = OpenAiChatOptions.builder().model(config.name()).temperature(null)
                .maxTokens(config.outputBudget()).internalToolExecutionEnabled(false).build();
        model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .toolExecutionEligibilityPredicate((promptOptions, response) -> false).build();
    }

    @Override public Reply answer(List<DialogueMessage> messages) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("At least one message is required");
        List<Message> input = new ArrayList<>();
        for (var message : messages) {
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
            var future = executor.submit(() -> model.call(new Prompt(input)));
            try {
            var response = future.get(Math.min(config.requestTimeout().toNanos(), remaining), java.util.concurrent.TimeUnit.NANOSECONDS);
            if (response == null || response.getResults() == null) throw new RemoteFailure(MODEL, INVALID_RESPONSE);
            for (var generation : response.getResults()) {
                String text = generation.getOutput() == null ? null : generation.getOutput().getText();
                if (text != null && !text.isBlank()) {
                    boolean truncated = "length".equalsIgnoreCase(generation.getMetadata().getFinishReason());
                    return new Reply(text, truncated);
                }
            }
            throw new RemoteFailure(MODEL, INVALID_RESPONSE);
            } catch (java.util.concurrent.TimeoutException failure) {
                future.cancel(true); // Dispatch may have happened: never submit another attempt.
                throw new RemoteFailure(MODEL, TIMEOUT);
            } catch (InterruptedException failure) {
                future.cancel(true); Thread.currentThread().interrupt(); throw new RemoteFailure(MODEL, CONNECTION);
            } catch (java.util.concurrent.ExecutionException failure) {
                Throwable root = failure.getCause();
                RateLimited limited = find(root, RateLimited.class);
                boolean unsent = definitelyUnsent(root);
                RemoteFailure safe = find(root, RemoteFailure.class);
                if (limited == null && !unsent) {
                    if (safe != null) throw safe;
                    if (find(root, HttpTimeoutException.class) != null || find(root, java.util.concurrent.TimeoutException.class) != null) throw new RemoteFailure(MODEL, TIMEOUT);
                    throw new RemoteFailure(MODEL, INVALID_RESPONSE);
                }
                RemoteFailure exhausted = limited != null ? new RemoteFailure(MODEL, HTTP, 429) : new RemoteFailure(MODEL, CONNECTION);
                if (attempt >= config.maxRetries()) throw exhausted;
                java.time.Duration delay = limited != null && limited.wait != null ? limited.wait : java.time.Duration.ofSeconds(Math.min(1L << attempt, 5));
                remaining = deadline - System.nanoTime();
                if (remaining <= 0 || delay.compareTo(java.time.Duration.ofNanos(remaining)) >= 0) throw exhausted;
                try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay.toNanos()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RemoteFailure(MODEL, CONNECTION); }
            }
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

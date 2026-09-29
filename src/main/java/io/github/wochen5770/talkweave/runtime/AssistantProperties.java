package io.github.wochen5770.talkweave.runtime;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("assistant")
public record AssistantProperties(Model model, Wechat wechat, Storage storage, Duration shutdownGrace) {
    public void validate() {
        require(model != null, "assistant.model", "is required");
        require(wechat != null, "assistant.wechat", "is required");
        require(storage != null, "assistant.storage", "is required");
        model.validate();
        wechat.validate();
        storage.validate();
        duration(shutdownGrace, 1, 120, "assistant.shutdown-grace");
    }

    @Override public String toString() { return "AssistantProperties[REDACTED]"; }

    public record Model(String apiBaseUrl, String apiKey, String name, String systemPrompt,
                        boolean allowInsecureLocalHttp, int contextCapacity, int outputBudget,
                        int safetyMargin, int historyRounds, Duration requestTimeout,
                        Duration totalTimeBudget, int maxRetries) {
        public void validate() {
            baseUri();
            require(apiKey != null && !apiKey.isBlank() && !apiKey.contains("\r") && !apiKey.contains("\n"),
                    "assistant.model.api-key", "must be a nonempty single-line value");
            require(name != null && !name.isBlank() && name.length() <= 256,
                    "assistant.model.name", "must be a nonempty model identifier (at most 256 characters)");
            require(systemPrompt != null && !systemPrompt.isBlank(), "assistant.model.system-prompt", "is required");
            require(contextCapacity >= 256 && contextCapacity <= 10_000_000,
                    "assistant.model.context-capacity", "must be between 256 and 10000000");
            require(outputBudget > 0 && safetyMargin >= 0 && (long) outputBudget + safetyMargin < contextCapacity,
                    "assistant.model.output-budget", "plus safety-margin must leave input space");
            require(historyRounds >= 0 && historyRounds <= 1000, "assistant.model.history-rounds", "must be between 0 and 1000");
            duration(requestTimeout, 1, 300, "assistant.model.request-timeout");
            duration(totalTimeBudget, 1, 600, "assistant.model.total-time-budget");
            require(totalTimeBudget.compareTo(requestTimeout) >= 0,
                    "assistant.model.total-time-budget", "must be at least request-timeout");
            require(maxRetries >= 0 && maxRetries <= 5, "assistant.model.max-retries", "must be between 0 and 5");
        }

        public URI baseUri() {
            String property = "assistant.model.api-base-url";
            require(apiBaseUrl != null && !apiBaseUrl.isBlank(), property, "is required");
            URI uri;
            try { uri = URI.create(apiBaseUrl); }
            catch (IllegalArgumentException e) { throw new ConfigurationProblem(property, "must be a valid service URL"); }
            require(uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null, property, "must not contain credentials, query or fragment");
            require(uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535), property, "has an invalid port");
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            require(!path.contains("%") && !path.contains("//") && !path.contains("..") && !path.contains("/./"),
                    property, "must use an unambiguous gateway prefix");
            String base = apiBaseUrl.replaceAll("/+$", "");
            require(!base.endsWith("/v1") && !base.endsWith("/chat/completions"), property,
                    "must exclude /v1 and /chat/completions; the application appends the protocol path");
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && allowInsecureLocalHttp && isLocalHost(uri.getHost());
            require(https || localHttp, property, "requires HTTPS (local HTTP requires explicit opt-in and a private literal address)");
            return URI.create(base);
        }

        public URI completionUri() { return URI.create(baseUri() + "/v1/chat/completions"); }
        @Override public String toString() { return "Model[REDACTED]"; }
    }

    public record Wechat(String botId, String ownerId, Duration requestTimeout, Duration pollTimeout,
                         Duration loginTimeout, int maxReplyBytes) {
        void validate() {
            boolean bot = botId != null && !botId.isBlank();
            boolean owner = ownerId != null && !ownerId.isBlank();
            require(bot == owner, "assistant.wechat", "requires both bot-id and owner-id, or neither while unbound");
            require(!bot || (botId.length() <= 256 && ownerId.length() <= 256), "assistant.wechat", "identity is too long");
            duration(requestTimeout, 1, 120, "assistant.wechat.request-timeout");
            duration(pollTimeout, 1, 300, "assistant.wechat.poll-timeout");
            duration(loginTimeout, 30, 900, "assistant.wechat.login-timeout");
            require(pollTimeout.compareTo(requestTimeout) >= 0, "assistant.wechat.poll-timeout", "must be at least request-timeout");
            require(maxReplyBytes >= 128 && maxReplyBytes <= 100_000, "assistant.wechat.max-reply-bytes", "must be between 128 and 100000");
        }
        public boolean isBound() { return botId != null && !botId.isBlank() && ownerId != null && !ownerId.isBlank(); }
        @Override public String toString() { return "Wechat[REDACTED]"; }
    }

    public record Storage(String directory, int maxPendingEvents, long minFreeBytes, Duration busyTimeout) {
        void validate() {
            dataPath();
            require(maxPendingEvents > 0 && maxPendingEvents <= 100_000, "assistant.storage.max-pending-events", "must be between 1 and 100000");
            require(minFreeBytes >= 0, "assistant.storage.min-free-bytes", "must be nonnegative");
            duration(busyTimeout, 1, 30, "assistant.storage.busy-timeout");
        }
        public Path dataPath() {
            require(directory != null && !directory.isBlank(), "assistant.storage.directory", "is required");
            require(!directory.startsWith("\\\\") && !directory.startsWith("//"), "assistant.storage.directory", "must be on local disk, not a UNC share");
            try { return Path.of(directory).toAbsolutePath().normalize(); }
            catch (InvalidPathException e) { throw new ConfigurationProblem("assistant.storage.directory", "must be a valid local path"); }
        }
        @Override public String toString() { return "Storage[REDACTED]"; }
    }

    private static boolean isLocalHost(String host) {
        if (host.equalsIgnoreCase("localhost") || host.equals("[::1]") || host.equals("::1")) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return false;
        }
        return octets[0] == 127 || octets[0] == 10 || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
    }

    private static void duration(Duration value, int minSeconds, int maxSeconds, String property) {
        require(value != null && value.compareTo(Duration.ofSeconds(minSeconds)) >= 0
                && value.compareTo(Duration.ofSeconds(maxSeconds)) <= 0, property,
                "must be between " + minSeconds + "s and " + maxSeconds + "s");
    }
    private static void require(boolean valid, String property, String rule) {
        if (!valid) throw new ConfigurationProblem(property, rule);
    }
}

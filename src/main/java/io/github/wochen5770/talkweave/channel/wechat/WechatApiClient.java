package io.github.wochen5770.talkweave.channel.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import static io.github.wochen5770.talkweave.runtime.RemoteFailure.Source.WECHAT;
import static io.github.wochen5770.talkweave.runtime.RemoteFailure.Kind.*;

/** Protocol adapter only; polling persistence, ownership and login orchestration are separate tasks. */
public final class WechatApiClient implements AutoCloseable {
    public static final URI LOGIN_ORIGIN = URI.create("https://ilinkai.weixin.qq.com");
    public static final String CHANNEL_VERSION = "2.4.9";
    private static final String CLIENT_VERSION = "132105";
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final URI loginOrigin;
    private final Predicate<URI> allowedOrigin;
    private final Duration requestTimeout;
    private final Duration pollTimeout;
    private Credentials updatesIdentity;
    private Duration updatesTimeout;

    public WechatApiClient(Duration requestTimeout, Duration pollTimeout) {
        this(requestTimeout, pollTimeout, Set.of("ilinkai.weixin.qq.com"));
    }

    /** Additional hosts must be independently verified before being trusted by the caller. */
    public WechatApiClient(Duration requestTimeout, Duration pollTimeout, Set<String> trustedHosts) {
        this(LOGIN_ORIGIN, requestTimeout, pollTimeout, trustedWechatOrigins(trustedHosts));
    }

    private static Predicate<URI> trustedWechatOrigins(Set<String> trustedHosts) {
        Set<String> hosts = Set.copyOf(trustedHosts);
        if (!hosts.contains("ilinkai.weixin.qq.com") || hosts.stream().anyMatch(host ->
                !host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.weixin\\.qq\\.com"))) {
            throw new IllegalArgumentException("Trusted hosts must include the login host and remain in the WeChat service domain");
        }
        return uri -> "https".equals(uri.getScheme()) && hosts.contains(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    // Package-private injection prevents arbitrary HTTP endpoints becoming production configuration.
    WechatApiClient(URI loginOrigin, Duration requestTimeout, Duration pollTimeout, Predicate<URI> allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
        this.loginOrigin = checkOrigin(loginOrigin);
        this.requestTimeout = requestTimeout;
        this.pollTimeout = pollTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record QrCode(String value, String displayContent) {
        @Override public String toString() { return "QrCode[REDACTED]"; }
    }
    public record Credentials(String botId, String token, URI origin, String scanUserId) {
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
    public enum LoginPhase { WAIT, SCANNED, NEED_VERIFY_CODE, VERIFY_CODE_BLOCKED, EXPIRED, REDIRECT, BOUND_REDIRECT, CONFIRMED }
    public record LoginStatus(LoginPhase phase, URI redirect, Credentials credentials) {
        @Override public String toString() { return "LoginStatus[phase=" + phase + "]"; }
    }
    public record Incoming(String messageId, String sender, String contextToken, String text, boolean group,
                           int messageType, int messageState) {
        @Override public String toString() { return "Incoming[REDACTED]"; }
    }
    public record Updates(List<Incoming> messages, String cursor, Long suggestedPollMillis) {
        public Updates { messages = List.copyOf(messages); }
        @Override public String toString() { return "Updates[count=" + messages.size() + ", state=REDACTED]"; }
    }

    public void validateCredentials(Credentials credentials) {
        if (credentials == null || credentials.botId() == null || credentials.botId().isBlank()
                || credentials.token() == null || credentials.token().isBlank()
                || credentials.token().contains("\r") || credentials.token().contains("\n")) throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
        checkOrigin(credentials.origin());
    }
    public QrCode requestQr(List<String> localTokens) {
        if (localTokens == null || localTokens.size() > 10 || localTokens.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Supply at most ten nonempty saved tokens");
        }
        ObjectNode body = json.createObjectNode();
        body.set("local_token_list", json.valueToTree(localTokens));
        JsonNode response = post(loginOrigin, "/ilink/bot/get_bot_qrcode?bot_type=3", body, null, requestTimeout);
        return new QrCode(requiredText(response, "qrcode"), requiredText(response, "qrcode_img_content"));
    }

    public LoginStatus pollQr(URI origin, QrCode qr, String verificationCode) {
        if (qr == null || qr.value() == null || qr.value().isBlank()) throw new IllegalArgumentException("Current QR challenge is required");
        String path = "/ilink/bot/get_qrcode_status?qrcode=" + encode(qr.value());
        if (verificationCode != null) {
            if (!verificationCode.matches("[0-9]{1,16}")) throw new IllegalArgumentException("Verification code must contain digits");
            path += "&verify_code=" + encode(verificationCode);
        }
        JsonNode response = exchange(headers(checkOrigin(origin).resolve(path)).timeout(pollTimeout).GET().build());
        String status = requiredText(response, "status");
        return switch (status) {
            case "wait" -> new LoginStatus(LoginPhase.WAIT, null, null);
            case "scaned" -> new LoginStatus(LoginPhase.SCANNED, null, null);
            case "need_verifycode" -> new LoginStatus(LoginPhase.NEED_VERIFY_CODE, null, null);
            case "verify_code_blocked" -> new LoginStatus(LoginPhase.VERIFY_CODE_BLOCKED, null, null);
            case "expired" -> new LoginStatus(LoginPhase.EXPIRED, null, null);
            case "scaned_but_redirect" -> new LoginStatus(LoginPhase.REDIRECT,
                    parseOrigin("https://" + requiredText(response, "redirect_host")), null);
            case "binded_redirect" -> new LoginStatus(LoginPhase.BOUND_REDIRECT, null, null);
            case "confirmed" -> new LoginStatus(LoginPhase.CONFIRMED, null,
                    new Credentials(requiredText(response, "ilink_bot_id"), requiredText(response, "bot_token"),
                            parseOrigin(requiredText(response, "baseurl")), optionalText(response, "ilink_user_id")));
            default -> throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
        };
    }

    public Updates getUpdates(Credentials credentials, String cursor) {
        ObjectNode body = botBody();
        body.put("get_updates_buf", cursor == null ? "" : cursor);
        Duration requestDeadline;
        synchronized (this) {
            if (!credentials.equals(updatesIdentity)) { updatesIdentity = credentials; updatesTimeout = pollTimeout; }
            requestDeadline = updatesTimeout;
        }
        JsonNode response = authenticatedPost(credentials, "/ilink/bot/getupdates", body, requestDeadline);
        JsonNode messages = response.path("msgs");
        if (!messages.isMissingNode() && !messages.isArray()) throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
        List<Incoming> incoming = new ArrayList<>();
        for (JsonNode message : messages) {
            if (!message.isObject()) throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
            JsonNode id = message.path("message_id");
            String messageId = id.isTextual() || id.isIntegralNumber() ? id.asText() : null;
            StringBuilder text = new StringBuilder();
            JsonNode items = message.path("item_list");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (item.path("type").asInt() == 1 && item.path("text_item").path("text").isTextual()) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(item.path("text_item").path("text").asText());
                    }
                }
            }
            incoming.add(new Incoming(messageId, optionalText(message, "from_user_id"), optionalText(message, "context_token"),
                    text.toString(), !optionalText(message, "group_id").isBlank(), message.path("message_type").asInt(),
                    message.path("message_state").asInt()));
        }
        String next = optionalText(response, "get_updates_buf");
        if (next.isBlank()) next = cursor == null ? "" : cursor;
        JsonNode timeout = response.path("longpolling_timeout_ms");
        Long suggested = timeout.isIntegralNumber() && timeout.canConvertToLong() && timeout.asLong() > 0 ? timeout.asLong() : null;
        synchronized (this) {
            if (credentials.equals(updatesIdentity)) updatesTimeout = pollingDeadline(pollTimeout, suggested);
        }
        return new Updates(incoming, next, suggested);
    }

    static Duration pollingDeadline(Duration configured, Long hintMillis) {
        if (hintMillis == null || hintMillis <= 0 || hintMillis > 295_000) return configured;
        return Duration.ofMillis(Math.max(configured.toMillis(), hintMillis + 5_000));
    }

    private Duration auxiliaryTimeout() { return requestTimeout.compareTo(Duration.ofSeconds(2)) < 0 ? requestTimeout : Duration.ofSeconds(2); }
    public String getTypingTicket(Credentials credentials, String recipient, String contextToken) {
        if (recipient == null || recipient.isBlank() || contextToken == null || contextToken.isBlank()) throw new IllegalArgumentException("Current recipient and context are required");
        ObjectNode body = botBody().put("ilink_user_id", recipient).put("context_token", contextToken);
        return optionalText(authenticatedPost(credentials, "/ilink/bot/getconfig", body, auxiliaryTimeout()), "typing_ticket");
    }
    public void sendTyping(Credentials credentials, String recipient, String ticket, boolean typing) {
        if (recipient == null || recipient.isBlank() || ticket == null || ticket.isBlank()) throw new IllegalArgumentException("Current recipient and typing ticket are required");
        authenticatedPost(credentials, "/ilink/bot/sendtyping", botBody().put("ilink_user_id", recipient)
                .put("typing_ticket", ticket).put("status", typing ? 1 : 2), auxiliaryTimeout());
    }
    public void notifyLifecycle(Credentials credentials, boolean starting) {
        authenticatedPost(credentials, starting ? "/ilink/bot/msg/notifystart" : "/ilink/bot/msg/notifystop", botBody(), auxiliaryTimeout());
    }

    public void sendText(Credentials credentials, String recipient, String contextToken, String clientId, String text) {
        if (recipient == null || recipient.isBlank() || contextToken == null || contextToken.isBlank()
                || clientId == null || clientId.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Recipient, message context, send ID and text are required");
        }
        ObjectNode body = botBody();
        ObjectNode message = body.putObject("msg");
        message.put("from_user_id", "").put("to_user_id", recipient).put("context_token", contextToken)
                .put("client_id", clientId).put("message_type", 2).put("message_state", 2);
        message.putArray("item_list").addObject().put("type", 1).putObject("text_item").put("text", text);
        authenticatedPost(credentials, "/ilink/bot/sendmessage", body, requestTimeout);
    }

    private JsonNode authenticatedPost(Credentials credentials, String path, ObjectNode body, Duration timeout) {
        if (credentials == null || credentials.botId() == null || credentials.botId().isBlank()
                || credentials.token() == null || credentials.token().isBlank()) throw new IllegalArgumentException("Connected bot credentials are required");
        return post(credentials.origin(), path, body, credentials.token(), timeout);
    }
    private ObjectNode botBody() {
        ObjectNode body = json.createObjectNode();
        body.putObject("base_info").put("channel_version", CHANNEL_VERSION).put("bot_agent", "personal-wechat-assistant/0.1.0 (java)");
        return body;
    }
    private JsonNode post(URI origin, String path, ObjectNode body, String token, Duration timeout) {
        var builder = headers(checkOrigin(origin).resolve(path)).timeout(timeout).header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", Base64.getEncoder().encodeToString(Long.toString(ThreadLocalRandom.current().nextLong(1L << 32)).getBytes(StandardCharsets.US_ASCII)));
        if (token != null) {
            if (token.contains("\r") || token.contains("\n")) throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
            builder.header("Authorization", "Bearer " + token);
        }
        return exchange(builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
    }
    private HttpRequest.Builder headers(URI uri) {
        return HttpRequest.newBuilder(uri).header("iLink-App-Id", "bot").header("iLink-App-ClientVersion", CLIENT_VERSION);
    }
    private JsonNode exchange(HttpRequest request) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new RemoteFailure(WECHAT, HTTP, response.statusCode());
            if (response.body().length > 2 * 1024 * 1024) throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
            JsonNode body;
            try { body = json.readTree(response.body()); }
            catch (IOException e) { throw new RemoteFailure(WECHAT, JSON); }
            if (body == null || !body.isObject()) throw new RemoteFailure(WECHAT, JSON);
            Integer ret = code(body, "ret"), errcode = code(body, "errcode");
            if (Integer.valueOf(-14).equals(ret) || Integer.valueOf(-14).equals(errcode)) throw new RemoteFailure(WECHAT, STALE_TOKEN, -14);
            if (ret != null && ret != 0) throw new RemoteFailure(WECHAT, BUSINESS, ret);
            if (errcode != null && errcode != 0) throw new RemoteFailure(WECHAT, BUSINESS, errcode);
            return body;
        } catch (HttpTimeoutException e) { throw new RemoteFailure(WECHAT, TIMEOUT); }
        catch (IOException e) { throw new RemoteFailure(WECHAT, CONNECTION); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RemoteFailure(WECHAT, CONNECTION); }
    }
    private static Integer code(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
        return value.intValue();
    }
    private URI parseOrigin(String value) {
        try { return checkOrigin(URI.create(value)); }
        catch (IllegalArgumentException e) { throw new RemoteFailure(WECHAT, UNSAFE_ENDPOINT); }
    }
    private URI checkOrigin(URI uri) {
        if (uri == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                || !allowedOrigin.test(uri)) throw new RemoteFailure(WECHAT, UNSAFE_ENDPOINT);
        return uri;
    }
    private static String requiredText(JsonNode body, String name) {
        String value = optionalText(body, name);
        if (value.isBlank()) throw new RemoteFailure(WECHAT, INVALID_RESPONSE);
        return value;
    }
    private static String optionalText(JsonNode body, String name) {
        JsonNode value = body.get(name);
        return value != null && value.isTextual() ? value.asText() : "";
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    @Override public void close() { http.shutdownNow(); }
}


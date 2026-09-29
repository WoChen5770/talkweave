package io.github.wochen5770.talkweave.channel.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import io.github.wochen5770.talkweave.support.FakeHttpService;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class WechatApiClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private WechatApiClient client(FakeHttpService fake) {
        return new WechatApiClient(fake.baseUri(), Duration.ofSeconds(3), Duration.ofSeconds(3), fake.baseUri()::equals);
    }
    private WechatApiClient.Credentials credentials(FakeHttpService fake) {
        return new WechatApiClient.Credentials("fake-bot", "fake-bot-token", fake.baseUri(), "fake-scanner");
    }

    @Test void qrPairingReceiveAndSendUseTheirOwnHeadersAndTokens() throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake)) {
            fake.enqueue(200, "{\"qrcode\":\"fake-qr/?&\",\"qrcode_img_content\":\"fake-display\"}");
            var qr = client.requestQr(List.of());
            var request = fake.take();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri().toString()).isEqualTo("/ilink/bot/get_bot_qrcode?bot_type=3");
            assertThat(request.headers()).doesNotContainKey("Authorization");
            assertThat(json.readTree(request.body()).path("local_token_list").isArray()).isTrue();

            fake.enqueue(200, "{\"status\":\"need_verifycode\"}");
            assertThat(client.pollQr(fake.baseUri(), qr, null).phase()).isEqualTo(WechatApiClient.LoginPhase.NEED_VERIFY_CODE);
            var poll = fake.take();
            assertThat(poll.headers()).doesNotContainKeys("Authorization", "Authorizationtype", "X-wechat-uin");
            assertThat(poll.uri().getRawQuery()).isEqualTo("qrcode=fake-qr%2F%3F%26");

            fake.enqueue(200, "{\"status\":\"confirmed\",\"bot_token\":\"fake-bot-token\",\"ilink_bot_id\":\"fake-bot\",\"ilink_user_id\":\"fake-scanner\",\"baseurl\":\"" + fake.baseUri() + "\"}");
            var session = client.pollQr(fake.baseUri(), qr, "123456").credentials();
            assertThat(fake.take().uri().getRawQuery()).endsWith("&verify_code=123456");
            assertThat(session.scanUserId()).isEqualTo("fake-scanner");

            fake.enqueue(200, "{\"ret\":0,\"get_updates_buf\":\"fake-next\",\"longpolling_timeout_ms\":35000,\"msgs\":[{\"message_id\":18446744073709551615,\"from_user_id\":\"fake-sender\",\"context_token\":\"fake-context\",\"message_type\":1,\"message_state\":2,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"hello\"}}]}]}");
            var updates = client.getUpdates(session, "fake-old");
            var receive = fake.take();
            assertThat(receive.headers().get("Authorization")).containsExactly("Bearer fake-bot-token");
            assertThat(json.readTree(receive.body()).path("get_updates_buf").asText()).isEqualTo("fake-old");
            assertThat(json.readTree(receive.body()).path("base_info").path("channel_version").asText()).isEqualTo("2.4.9");
            assertThat(updates.messages().getFirst().messageId()).isEqualTo("18446744073709551615");
            assertThat(updates.cursor()).isEqualTo("fake-next");

            fake.enqueue(200, "{\"ret\":0}");
            client.sendText(session, "fake-sender", "fake-context", "fake-send-id", "synthetic reply");
            var send = json.readTree(fake.take().body()).path("msg");
            assertThat(send.path("to_user_id").asText()).isEqualTo("fake-sender");
            assertThat(send.path("context_token").asText()).isEqualTo("fake-context");
            assertThat(send.path("client_id").asText()).isEqualTo("fake-send-id");
            assertThat(send.path("message_type").asInt()).isEqualTo(2);
            assertThat(send.path("item_list").get(0).path("text_item").path("text").asText()).isEqualTo("synthetic reply");
            assertThat(qr.toString() + session + updates).doesNotContain("fake-qr", "fake-bot-token", "fake-context");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"{\"ret\":-14}", "{\"ret\":0,\"errcode\":-14}", "{\"ret\":5,\"errcode\":-14}"})
    void recognizesExpiredTokensWithoutRetry(String body) throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake)) {
            fake.enqueue(200, body);
            assertThatThrownBy(() -> client.getUpdates(credentials(fake), "old"))
                    .isInstanceOf(RemoteFailure.class).hasMessageContaining("STALE_TOKEN");
            fake.take();
            assertThat(fake.pendingRequests()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"{\"ret\":5,\"errmsg\":\"fake-secret\"}", "{\"ret\":0,\"errcode\":5}", "not-json", "[]", "{\"ret\":\"0\"}"})
    void doesNotConfuseHttpSuccessWithBusinessSuccess(String body) throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake)) {
            fake.enqueue(200, body);
            assertThatThrownBy(() -> client.sendText(credentials(fake), "owner", "context", "send-id", "reply"))
                    .isInstanceOf(RemoteFailure.class).hasMessageNotContaining("fake-secret");
        }
    }

    @Test void rejectsUntrustedNodeAndHttpRedirectWithoutSendingCredentials() throws Exception {
        try (var fake = new FakeHttpService(); var other = new FakeHttpService(); var client = client(fake)) {
            var unsafe = new WechatApiClient.Credentials("bot", "fake-token", other.baseUri(), "owner");
            assertThatThrownBy(() -> client.getUpdates(unsafe, "")).hasMessageContaining("UNSAFE_ENDPOINT");
            fake.enqueue(302, "{}", Map.of("Location", other.baseUri().toString()));
            assertThatThrownBy(() -> client.getUpdates(credentials(fake), "")).hasMessageContaining("HTTP");
            fake.take();
            assertThat(other.pendingRequests()).isZero();
            fake.enqueue(200, "{\"status\":\"scaned_but_redirect\",\"redirect_host\":\"attacker.invalid\"}");
            assertThatThrownBy(() -> client.pollQr(fake.baseUri(), new WechatApiClient.QrCode("fake", "fake"), null))
                    .hasMessageContaining("UNSAFE_ENDPOINT");
        }
    }

    @Test void preservesCursorOnEmptyResponsesAndRequiresReplyContext() throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake)) {
            fake.enqueue(200, "{\"ret\":0,\"msgs\":[]}");
            assertThat(client.getUpdates(credentials(fake), "old").cursor()).isEqualTo("old");
            fake.take();
            assertThatThrownBy(() -> client.sendText(credentials(fake), "owner", "", "id", "reply"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fake.pendingRequests()).isZero();
        }
    }

    @Test void acceptsOnlyExplicitTrustedWechatRedirectOrigins() throws Exception {
        URI trusted = URI.create("https://verified-node.weixin.qq.com");
        try (var fake = new FakeHttpService(); var client = new WechatApiClient(fake.baseUri(), Duration.ofSeconds(3),
                Duration.ofSeconds(3), uri -> uri.equals(fake.baseUri()) || uri.equals(trusted))) {
            fake.enqueue(200, "{\"status\":\"scaned_but_redirect\",\"redirect_host\":\"verified-node.weixin.qq.com\"}");
            assertThat(client.pollQr(fake.baseUri(), new WechatApiClient.QrCode("fake", "fake"), null).redirect())
                    .isEqualTo(trusted);
        }
        assertThatThrownBy(() -> new WechatApiClient(Duration.ofSeconds(3), Duration.ofSeconds(3),
                java.util.Set.of("ilinkai.weixin.qq.com", "attacker.invalid")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void handlesExpiredAndBlockedPairingStatesWithoutAssumingLoginSuccess() throws Exception {
        try (var fake = new FakeHttpService(); var client = client(fake)) {
            var qr = new WechatApiClient.QrCode("fake-qr", "fake-display");
            fake.enqueue(200, "{\"status\":\"expired\"}");
            assertThat(client.pollQr(fake.baseUri(), qr, null).phase()).isEqualTo(WechatApiClient.LoginPhase.EXPIRED);
            fake.enqueue(200, "{\"status\":\"verify_code_blocked\"}");
            assertThat(client.pollQr(fake.baseUri(), qr, "123456").credentials()).isNull();
            assertThatThrownBy(() -> client.pollQr(URI.create("https://attacker.invalid"), qr, null)).hasMessageContaining("UNSAFE_ENDPOINT");
        }
    }
    @Test void suggestedPollTimeoutHasMarginAndRejectsUnreasonableValues() {
        assertThat(WechatApiClient.pollingDeadline(Duration.ofSeconds(45), 35000L)).isEqualTo(Duration.ofSeconds(45));
        assertThat(WechatApiClient.pollingDeadline(Duration.ofSeconds(45), 60000L)).isEqualTo(Duration.ofSeconds(65));
        assertThat(WechatApiClient.pollingDeadline(Duration.ofSeconds(45), 295000L)).isEqualTo(Duration.ofMinutes(5));
        for (Long hint : Arrays.asList(null, 0L, -1L, 300001L, Long.MAX_VALUE)) {
            assertThat(WechatApiClient.pollingDeadline(Duration.ofSeconds(45), hint)).isEqualTo(Duration.ofSeconds(45));
        }
    }
}


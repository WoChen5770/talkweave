package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.AssistantApplication;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.managed.binding.BindingCoordinator;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Explicit test-classpath launch only. No outbound requests and no identity-verification bypass. */
@TestConfiguration(proxyBeanMethods = false)
@Profile("synthetic-browser-only")
public class ManagedBrowserFixture {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--synthetic.seed-usage=true")) {
            var target = io.github.wochen5770.talkweave.managed.config.ExternalIntegrationTarget.load();
            target.requireSchemaInitialization(); target.requireSchemaUpgrade();
            try (var store = io.github.wochen5770.talkweave.managed.persistence.ManagedStore.open(target.config().mysql())) {
                if (new io.github.wochen5770.talkweave.managed.persistence.ManagedUsers(store, java.time.Clock.systemUTC()).list().isEmpty())
                    AdminUsageFixture.seed(store);
            }
        }
        new SpringApplicationBuilder(AssistantApplication.class, ManagedBrowserFixture.class)
                .profiles("managed", "synthetic-browser-only").run(args);
    }
    @Bean @Primary io.github.wochen5770.talkweave.managed.runtime.ChannelRuntime.Ports syntheticChannelPorts() {
        return () -> new io.github.wochen5770.talkweave.managed.runtime.ChannelRuntime.Port() {
            public void validate(WechatApiClient.Credentials c) { }
            public WechatApiClient.Updates updates(WechatApiClient.Credentials c, String cursor) {
                return new WechatApiClient.Updates(java.util.List.of(), cursor, null);
            }
            public void lifecycle(WechatApiClient.Credentials c, boolean start) { }
            public String typingTicket(WechatApiClient.Credentials c, String recipient, String context) { return "synthetic-ticket"; }
            public void typing(WechatApiClient.Credentials c, String recipient, String ticket, boolean typing) { }
            public void send(WechatApiClient.Credentials c, String recipient, String context, String id, String text) {
                throw new IllegalStateException("Synthetic browser cannot send messages");
            }
            public void close() { }
        };
    }
    @Bean @Primary BindingCoordinator.Ports syntheticBrowserPorts() {
        return () -> new BindingCoordinator.Port() {
            final String id = UUID.randomUUID().toString();
            int polls;
            @Override public WechatApiClient.QrCode request() {
                return new WechatApiClient.QrCode("synthetic-" + id, "SYNTHETIC-ONLY-NOT-A-WECHAT-LOGIN:" + id);
            }
            @Override public WechatApiClient.LoginStatus poll(URI origin, WechatApiClient.QrCode qr, String code) {
                if (code != null) return new WechatApiClient.LoginStatus(WechatApiClient.LoginPhase.CONFIRMED, null,
                        new WechatApiClient.Credentials("synthetic-bot-" + id, "synthetic-token", WechatApiClient.LOGIN_ORIGIN, "synthetic-scanner-" + id));
                return new WechatApiClient.LoginStatus(++polls == 1 ? WechatApiClient.LoginPhase.SCANNED : WechatApiClient.LoginPhase.NEED_VERIFY_CODE, null, null);
            }
            @Override public void validate(WechatApiClient.Credentials credentials) { }
            @Override public void close() { }
        };
    }
}

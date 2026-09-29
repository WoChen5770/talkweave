package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.channel.wechat.ScannerIdentityResolver.VerifiedIdentity;
import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.model.TokenUsage;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.time.*;
import java.util.List;

/** Test classpath only. Uses real repositories with synthetic identities; never calls a remote service. */
final class AdminUsageFixture {
    static final long START = Instant.parse("2026-09-29T02:00:00Z").toEpochMilli();
    record Seed(String a, String b, String firstConversation, String secondConversation, String otherConversation) { }
    static Seed seed(ManagedStore store) {
        var clock = new MutableClock(); var users = new ManagedUsers(store, clock);
        if (!users.list().isEmpty()) throw new IllegalStateException("Synthetic usage requires an empty test store");
        var settings = new ManagedSettings(store, clock); var chat = new ManagedConversations(store, clock);
        var usage = new ManagedUsage(store, clock);
        long model = settings.saveModel(TestProperties.model("https://model.invalid", "synthetic-model-secret")).version();
        var a = bound(users, "测试甲 <img src=x onerror=alert(1)>", "a"); var b = bound(users, "测试乙", "b");
        String first = turn(chat, usage, a, model, "a1", TokenUsage.normalize(2000, 100, 1500));
        clock.now += 60_000;
        turn(chat, usage, a, model, "a2", TokenUsage.unknown());
        String other = turn(chat, usage, b, model, "b1", TokenUsage.normalize(999, 3, 0));
        clock.now += 40 * 60_000;
        String second = turn(chat, usage, a, model, "a3", TokenUsage.fromCompatibleUsage(java.util.Map.of(
                "prompt_tokens", 20, "completion_tokens", 5, "prompt_tokens_details", java.util.Map.of("cached_tokens", 30))));
        clock.now += 60_000;
        turn(chat, usage, a, model, "a4", TokenUsage.normalize(0, 0, 0));
        users.setEnabled(a.userId(), false); users.setEnabled(b.userId(), false);
        return new Seed(a.userId(), b.userId(), first, second, other);
    }
    private static ManagedScope bound(ManagedUsers users, String label, String suffix) {
        var user = users.create(label); var task = users.begin(user.id(), ManagedUsers.Mode.INITIAL);
        users.advance(user.id(), task.id(), ManagedUsers.Phase.REQUESTING_QR, ManagedUsers.Phase.VERIFYING_IDENTITY);
        var identity = new VerifiedIdentity("synthetic-only", "private-account-" + suffix, "private-bot-" + suffix,
                "private-sender-" + suffix, WechatApiClient.LOGIN_ORIGIN, "synthetic-not-production");
        return users.activate(user.id(), task.id(), identity, new WechatApiClient.Credentials(identity.botId(),
                "private-wechat-token-" + suffix, WechatApiClient.LOGIN_ORIGIN, "private-scanner-" + suffix));
    }
    private static String turn(ManagedConversations chat, ManagedUsage usage, ManagedScope scope, long model, String id, TokenUsage value) {
        var event = chat.accept(scope, new WechatApiClient.Updates(List.of(new WechatApiClient.Incoming(id, scope.senderId(),
                "private-context", "private-chat-body", false, 1, 2)), "private-cursor", null)).getFirst();
        chat.claim(scope).orElseThrow(); String attempt = usage.begin(scope, event.sequence(), model);
        usage.record(attempt, ManagedUsage.Outcome.SUCCEEDED, value);
        chat.saveReply(scope, event.sequence(), "private-model-answer", true); chat.claim(scope).orElseThrow();
        chat.finishSend(scope, event.sequence(), ManagedConversations.Delivery.CONFIRMED); return event.conversationId();
    }
    private static final class MutableClock extends Clock {
        long now = START;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}

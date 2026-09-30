package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.channel.wechat.*;
import io.github.wochen5770.talkweave.managed.cache.*;
import io.github.wochen5770.talkweave.managed.config.*;
import io.github.wochen5770.talkweave.managed.persistence.ManagedConversations.*;
import io.github.wochen5770.talkweave.support.TestProperties;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL history + Redis transport. Only synthetic identities and isolated expiring test keys. */
class ExternalHistoryIT {
    @Test void boundedFilteringRevisionIdempotenceAndWarmIncrementalRedis() {
        var target = ExternalIntegrationTarget.load();
        try (var fixture = new ExternalBusinessFixture(); var store = fixture.open()) {
            var clock = Clock.fixed(Instant.parse("2026-09-30T01:00:00Z"), ZoneOffset.UTC);
            var users = new ManagedUsers(store, clock); var chat = new ManagedConversations(store, clock);
            var user = users.create(target.fixtureLabel()); var task = users.begin(user.id(), ManagedUsers.Mode.INITIAL);
            users.advance(user.id(),task.id(), ManagedUsers.Phase.REQUESTING_QR, ManagedUsers.Phase.VERIFYING_IDENTITY);
            var id = UUID.randomUUID().toString();
            var scope = users.activate(user.id(), task.id(), new ScannerIdentityResolver.VerifiedIdentity("synthetic-only", id, id, id,
                    WechatApiClient.LOGIN_ORIGIN,"synthetic"), new WechatApiClient.Credentials(id,"synthetic-token", WechatApiClient.LOGIN_ORIGIN,id));
            var settings = new ManagedSettings(store, clock);
            var model = settings.saveModel(TestProperties.model("https://synthetic.invalid", "synthetic-key"));
            Event last = null;
            var successful = new ArrayList<Pair>();
            for (int i = 0; i < 30; i++) {
                last = accept(chat, scope, "question-" + i); chat.claim(scope).orElseThrow();
                chat.saveReply(scope,last.sequence(),"answer-" + i,true); chat.claim(scope).orElseThrow();
                boolean confirmed = i % 3 == 0;
                var delivered = chat.finishSend(scope,last.sequence(), confirmed ? Delivery.CONFIRMED : Delivery.UNKNOWN);
                if (confirmed) successful.add(delivered.orElseThrow().pair());
                assertTrue(chat.finishSend(scope,last.sequence(),Delivery.CONFIRMED).isEmpty());
            }
            var current = accept(chat,scope,"current");
            var metadata = chat.historySnapshot(scope,current.sequence());
            assertEquals(10,metadata.revision()); assertEquals(10,metadata.count());
            assertEquals(successful.subList(8,10),chat.readHistory(scope,current.sequence(),2).pairs());
            assertTrue(chat.readHistory(scope,current.sequence(),0).pairs().isEmpty());
            assertTrue(chat.readHistory(scope,successful.getFirst().sequence(),20).pairs().isEmpty());
            // Check the actual engine plan, not merely the existence of an index.
            store.transaction(c -> {
                try (var statement = Sql.prepare(c,"EXPLAIN FORMAT=JSON " + ManagedConversations.HISTORY_SQL,
                        scope.userId(),scope.bindingId(),current.conversationId(),current.sequence(),2); var rows = statement.executeQuery()) {
                    assertTrue(rows.next()); String plan = rows.getString(1);
                    assertTrue(plan.contains("history_window"));
                    assertFalse(plan.contains("using_filesort\": true"));
                }
                return null;
            });
            var base = target.config().historyCache();
            var config = new ExternalServices.HistoryCache(true,base.keyPrefix(),Duration.ofSeconds(60),base.maxEntryBytes(),
                    Duration.ofMillis(1000),Duration.ofMillis(2000),2,4);
            try (var redis = new RedisHistoryCache(target.config().redis(),config)) {
                // Map to the expressly approved synthetic sub-prefix; leave keys to expire, no scan/flush.
                var port = new HistoryCachePort() {
                    private String key(String key) { return target.redisKey("history") + ":" + MysqlOwnership.digest(key); }
                    public String get(String key,long deadline) { return redis.get(key(key),deadline); }
                    public void put(String key,String value,long deadline) { redis.put(key(key),value,deadline); }
                    public void discard(String key,String value,long deadline) { redis.discard(key(key),value,deadline); }
                    public void close() { }
                };
                try (var history = new HistoryService(chat,config,port,store.installationId(),store.cacheEpoch())) {
                    assertEquals(20,history.load(scope,current.sequence(),model).size());
                    assertEquals(1,history.status().bodyQueries());
                    assertEquals(20,history.load(scope,current.sequence(),model).size());
                    assertEquals(1,history.status().hit()); assertEquals(1,history.status().bodyQueries());
                    chat.claim(scope).orElseThrow(); chat.saveReply(scope,current.sequence(),"confirmed",true); chat.claim(scope).orElseThrow();
                    var delivered = chat.finishSend(scope,current.sequence(),Delivery.CONFIRMED);
                    history.afterSend(current.sequence(),delivered); history.afterSend(current.sequence(),delivered);
                    var next = accept(chat,scope,"next");
                    assertEquals(22,history.load(scope,next.sequence(),model).size());
                    assertEquals(2,history.status().hit()); assertEquals(1,history.status().bodyQueries());
                    // Lost/corrupt update is detected against MySQL and repairs only the current synthetic key.
                    String key = history.key(chat.historySnapshot(scope,next.sequence()),model.version());
                    port.discard(key,port.get(key,System.nanoTime()+Duration.ofSeconds(2).toNanos()),System.nanoTime()+Duration.ofSeconds(2).toNanos());
                    assertEquals(22,history.load(scope,next.sequence(),model).size());
                    assertEquals(2,history.status().bodyQueries());
                    users.setEnabled(scope.userId(),false); users.setEnabled(scope.userId(),true);
                    assertThrows(ManagedProblem.class, () -> history.load(scope,next.sequence(),model));
                }
            }
            System.out.println("MYSQL HISTORY filterBeforeLimit=PASS zeroRounds=PASS int64Markers=PASS duplicateDelivery=PASS indexedPlan=PASS REDIS warmBodyReads=0 incremental=PASS scopedKeysTTL=60s");
        }
    }
    private static Event accept(ManagedConversations chat, ManagedScope scope, String text) {
        return chat.accept(scope,new WechatApiClient.Updates(List.of(new WechatApiClient.Incoming(UUID.randomUUID().toString(),scope.senderId(),"synthetic-context",text,false,1,2)),"synthetic-cursor",null)).getFirst();
    }
}

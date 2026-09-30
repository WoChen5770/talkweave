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
    @Test void realCacheRejectsCrossUserFramesLateCallbacksAndReplacedBindings() {
        var target=ExternalIntegrationTarget.load();
        try(var fixture=new ExternalBusinessFixture(); var store=fixture.open()) {
            var clock=Clock.systemUTC(); var users=new ManagedUsers(store,clock);
            var chat=new ManagedConversations(store,clock);
            var model=new ManagedSettings(store,clock).saveModel(TestProperties.model("https://synthetic.invalid","synthetic-key"));
            var a=SyntheticHistory.user(store,clock); var b=SyntheticHistory.user(store,clock);
            var firstA=accept(chat,a,"only-a"); confirm(chat,a,firstA,"answer-a");
            var firstB=accept(chat,b,"only-b"); confirm(chat,b,firstB,"answer-b");
            var currentA=accept(chat,a,"a-2"); var currentB=accept(chat,b,"b-2");
            var base=target.config().historyCache();
            var config=new ExternalServices.HistoryCache(true,base.keyPrefix(),Duration.ofSeconds(60),base.maxEntryBytes(),
                    Duration.ofSeconds(1),Duration.ofSeconds(2),2,4);
            try(var redis=new RedisHistoryCache(target.config().redis(),config)) {
                class ControlledPort implements HistoryCachePort {
                    boolean failNextWrite;
                    private String key(String key) { return target.redisKey("scope-callbacks")+":"+MysqlOwnership.digest(key); }
                    public String get(String key,long deadline) { return redis.get(key(key),deadline); }
                    public void put(String key,String value,long deadline) {
                        if(failNextWrite) { failNextWrite=false; throw new IllegalStateException("Synthetic lost cache update"); }
                        redis.put(key(key),value,deadline);
                    }
                    public void discard(String key,String value,long deadline) { redis.discard(key(key),value,deadline); }
                    public void close() { }
                }
                var port=new ControlledPort();
                try(var history=new HistoryService(chat,config,port,store.installationId(),store.cacheEpoch())) {
                    var expectedA=chat.readHistory(a,currentA.sequence(),20).messages();
                    var expectedB=chat.readHistory(b,currentB.sequence(),20).messages();
                    assertEquals(expectedA,history.load(a,currentA.sequence(),model));
                    assertEquals(expectedB,history.load(b,currentB.sequence(),model));
                    assertEquals(expectedA,history.load(a,currentA.sequence(),model));
                    assertEquals(expectedB,history.load(b,currentB.sequence(),model));
                    assertEquals(2,history.status().bodyQueries()); assertEquals(2,history.status().hit());
                    String keyA=history.key(chat.historySnapshot(a,currentA.sequence()),model.version());
                    String keyB=history.key(chat.historySnapshot(b,currentB.sequence()),model.version());
                    assertNotEquals(keyA,keyB);
                    // Inject A's valid serialized frame into B's exact synthetic key, not a fake cache.
                    String rawA=port.get(keyA,deadline());
                    port.discard(keyB,port.get(keyB,deadline()),deadline()); port.put(keyB,rawA,deadline());
                    assertEquals(expectedB,history.load(b,currentB.sequence(),model));
                    assertEquals(1,history.status().stale());
                    assertThrows(ManagedProblem.class,()->history.load(b,currentA.sequence(),model));

                    var delivered2=confirm(chat,a,currentA,"a-answer-2");
                    port.failNextWrite=true;
                    assertDoesNotThrow(()->history.afterSend(currentA.sequence(),delivered2));
                    var third=accept(chat,a,"a-3");
                    assertEquals(2,chat.historySnapshot(a,third.sequence()).revision());
                    assertEquals(chat.readHistory(a,third.sequence(),20).messages(),history.load(a,third.sequence(),model));
                    assertEquals(1,history.status().error());
                    var delivered3=confirm(chat,a,third,"a-answer-3"); // notification deliberately delayed
                    var fourth=accept(chat,a,"a-4");
                    assertEquals(chat.readHistory(a,fourth.sequence(),20).messages(),history.load(a,fourth.sequence(),model));
                    var delivered4=confirm(chat,a,fourth,"a-answer-4");
                    history.afterSend(fourth.sequence(),delivered4);
                    history.afterSend(third.sequence(),delivered3); // old revision cannot overwrite newer Redis value
                    history.afterSend(fourth.sequence(),delivered4); // duplicate notification is a no-op
                    assertEquals(4,HistoryWindow.decode(port.get(keyA,deadline()),config.maxEntryBytes()).revision());
                    var fifth=accept(chat,a,"a-5"); long bodies=history.status().bodyQueries();
                    assertEquals(chat.readHistory(a,fifth.sequence(),20).messages(),history.load(a,fifth.sequence(),model));
                    assertEquals(bodies,history.status().bodyQueries());

                    var renewed=bindAgain(users,a,ManagedUsers.Mode.REAUTHENTICATE,a.botId());
                    assertEquals(a.bindingId(),renewed.bindingId());
                    assertThrows(ManagedProblem.class,()->history.load(a,fifth.sequence(),model));
                    var afterRenewal=accept(chat,renewed,"reauthenticated");
                    assertEquals(chat.readHistory(renewed,afterRenewal.sequence(),20).messages(),history.load(renewed,afterRenewal.sequence(),model));
                    assertEquals(8,history.load(renewed,afterRenewal.sequence(),model).size());

                    var replacement=bindAgain(users,renewed,ManagedUsers.Mode.REPLACE,UUID.randomUUID().toString());
                    assertNotEquals(renewed.bindingId(),replacement.bindingId());
                    var fresh=accept(chat,replacement,"new-identity");
                    assertTrue(history.load(replacement,fresh.sequence(),model).isEmpty());
                    assertThrows(ManagedProblem.class,()->history.load(renewed,afterRenewal.sequence(),model));
                    assertEquals(expectedB,history.load(b,currentB.sequence(),model));
                    // A new conversation is isolated even while old B windows still exist in Redis.
                    accept(chat,b,"/new"); var newConversation=accept(chat,b,"fresh-conversation");
                    assertNotEquals(currentB.conversationId(),newConversation.conversationId());
                    assertTrue(history.load(b,newConversation.sequence(),model).isEmpty());
                }
            }
            System.out.println("REDIS MYSQL scopes=ISOLATED reauth=PRESERVED replacement=EMPTY newConversation=EMPTY lostUpdate=REPAIRED lateDuplicateCallbacks=SAFE");
        }
    }

    private static long deadline() { return System.nanoTime()+Duration.ofSeconds(2).toNanos(); }
    private static Optional<Delivered> confirm(ManagedConversations chat,ManagedScope scope,Event event,String reply) {
        assertEquals(event.sequence(),chat.claim(scope).orElseThrow().event().sequence());
        chat.saveReply(scope,event.sequence(),reply,true);
        assertEquals(event.sequence(),chat.claim(scope).orElseThrow().event().sequence());
        return chat.finishSend(scope,event.sequence(),Delivery.CONFIRMED);
    }
    private static ManagedScope bindAgain(ManagedUsers users,ManagedScope old,ManagedUsers.Mode mode,String account) {
        var task=users.begin(old.userId(),mode);
        users.advance(old.userId(),task.id(),ManagedUsers.Phase.REQUESTING_QR,ManagedUsers.Phase.VERIFYING_IDENTITY);
        String bot=UUID.randomUUID().toString();
        String sender=mode==ManagedUsers.Mode.REAUTHENTICATE ? old.senderId() : account;
        return users.activate(old.userId(),task.id(),
                new ScannerIdentityResolver.VerifiedIdentity("synthetic-only",account,bot,sender,WechatApiClient.LOGIN_ORIGIN,"synthetic"),
                new WechatApiClient.Credentials(bot,"synthetic-token",WechatApiClient.LOGIN_ORIGIN,sender));
    }

    @Test void tenThousandRoundsStayBoundedAcrossConfigurationAndFutureBoundaries() {
        try (var fixture = new ExternalBusinessFixture(); var store = fixture.open()) {
            var clock = Clock.systemUTC(); var chat = new ManagedConversations(store, clock);
            var scope = SyntheticHistory.user(store, clock);
            var current = SyntheticHistory.seed(store, chat, scope, 10000);
            var last = chat.readHistory(scope, current.sequence(), 20);
            assertEquals(10000, last.snapshot().count()); assertEquals(20, last.pairs().size());
            assertEquals(last.pairs().subList(18,20), chat.readHistory(scope,current.sequence(),2).pairs());
            assertTrue(chat.readHistory(scope,current.sequence(),0).pairs().isEmpty());
            long boundary = last.pairs().get(10).sequence();
            var slice = chat.readHistory(scope,boundary,2);
            assertFalse(slice.snapshot().latestTail());
            assertEquals(last.pairs().subList(8,10),slice.pairs());
            store.transaction(c -> {
                Sql.update(c,"UPDATE turn SET stage='DELIVERY_UNKNOWN' WHERE event_sequence=?",last.pairs().get(19).sequence());
                Sql.update(c,"UPDATE turn SET model_succeeded=0 WHERE event_sequence=?",last.pairs().get(18).sequence());
                Sql.update(c,"UPDATE conversation SET history_revision=9998,confirmed_turn_count=9998,last_confirmed_sequence=? WHERE id=?",
                        last.pairs().get(17).sequence(),current.conversationId());
                return null;
            });
            assertEquals(last.pairs().subList(16,18),chat.readHistory(scope,current.sequence(),2).pairs());
            store.transaction(c -> {
                try (var statement = Sql.prepare(c,"EXPLAIN FORMAT=JSON " + ManagedConversations.HISTORY_SQL,
                        scope.userId(),scope.bindingId(),current.conversationId(),current.sequence(),20); var rows = statement.executeQuery()) {
                    assertTrue(rows.next()); String plan = rows.getString(1);
                    assertTrue(plan.contains("history_window")); assertFalse(plan.contains("using_filesort\": true"));
                }
                return null;
            });
            System.out.println("MYSQL HISTORY rounds=10000 returned=20 zeroRounds=PASS futureBoundary=PASS indexedPlan=PASS");
        }
    }
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

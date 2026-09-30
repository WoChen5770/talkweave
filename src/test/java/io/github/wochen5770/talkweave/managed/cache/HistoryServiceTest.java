package io.github.wochen5770.talkweave.managed.cache;

import io.github.wochen5770.talkweave.managed.config.ExternalServices;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.managed.persistence.ManagedConversations.*;
import io.github.wochen5770.talkweave.model.ModelConfiguration;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryServiceTest {
    private final ManagedConversations database = mock(ManagedConversations.class);
    private final ManagedScope scope = new ManagedScope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "bot", "sender", 1, 1);
    private final String conversation = UUID.randomUUID().toString();
    private final String installation = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
    private final MemoryCache cache = new MemoryCache();
    private final ExternalServices.HistoryCache config = new ExternalServices.HistoryCache(true, "synthetic", Duration.ofSeconds(60), 4096,
            Duration.ofMillis(100), Duration.ofMillis(250), 2, 4);
    private final HistoryService service = new HistoryService(database, config, cache, installation, run);
    private ManagedSettings.ModelSnapshot model(int rounds, long version) {
        return new ManagedSettings.ModelSnapshot(version, new ModelConfiguration("https://model.invalid", "NEVER-CACHE-KEY", "test", "NEVER-CACHE-PROMPT", false,
                8192, 1024, 512, rounds, Duration.ofSeconds(5), Duration.ofSeconds(10), 0));
    }
    private HistorySnapshot snapshot(long before, long revision, long watermark) {
        return new HistorySnapshot(scope, conversation, before, revision, revision, watermark);
    }
    private void seed(HistorySnapshot s, List<Pair> pairs, int rounds) {
        when(database.historySnapshot(scope, s.beforeSequence())).thenReturn(s);
        when(database.readHistory(scope, s.beforeSequence(), rounds)).thenReturn(new HistoryRead(s, pairs));
    }
    @Test void coldRefillThenConfirmedAppendKeepsNextTurnWarmWithoutBodyQuery() {
        var initial = snapshot(10, 0, 0); seed(initial, List.of(), 2);
        assertTrue(service.load(scope, 10, model(2, 1)).isEmpty());
        var delivered = new Delivered(scope, conversation, 0, 1, 1, 10, new Pair(10, "原始问题😀", "已送达回复"));
        service.afterSend(10, Optional.of(delivered));
        service.afterSend(10, Optional.of(delivered)); // duplicate notification has no second append
        var next = snapshot(20, 1, 10); seed(next, List.of(delivered.pair()), 2);
        assertEquals(List.of("原始问题😀", "已送达回复"), service.load(scope, 20, model(2, 1)).stream().map(m -> m.text()).toList());
        verify(database, never()).readHistory(scope, 20, 2);
        assertEquals(1, service.status().hit()); assertEquals(1, service.status().bodyQueries());
        assertFalse(cache.value.contains("NEVER-CACHE"));
    }
    @Test void staleLostAppendAndLargerWindowAlwaysRecheckDatabase() {
        var first = snapshot(10, 1, 5); seed(first, List.of(new Pair(5, "a", "b")), 1);
        service.load(scope, 10, model(1, 1));
        var changed = snapshot(30, 2, 20); var pairs = List.of(new Pair(5, "a", "b"), new Pair(20, "c", "d"));
        seed(changed, pairs, 2);
        assertEquals(4, service.load(scope, 30, model(2, 1)).size());
        assertEquals(1, service.status().stale()); verify(database).readHistory(scope, 30, 2);
    }
    @Test void zeroRoundsSkipsRedisAndHistoryBodiesButNotAuthorization() {
        when(database.historySnapshot(scope, 1)).thenReturn(snapshot(1, 0, 0));
        assertTrue(service.load(scope, 1, model(0, 1)).isEmpty());
        assertEquals(0, cache.gets); verify(database, never()).readHistory(any(), anyLong(), anyInt());
        verify(database).historySnapshot(scope, 1);
    }
    @Test void cacheCannotAuthorizeRevokedWorkOrHideDatabaseFailure() {
        var s = snapshot(10, 1, 5); seed(s, List.of(new Pair(5, "a", "b")), 1);
        service.load(scope, 10, model(1, 1)); int gets = cache.gets;
        when(database.historySnapshot(scope, 10)).thenThrow(new ManagedProblem(ManagedProblem.Code.UNAUTHORIZED));
        assertThrows(ManagedProblem.class, () -> service.load(scope, 10, model(1, 1)));
        assertEquals(gets, cache.gets);
        doReturn(s).when(database).historySnapshot(scope, 10);
        cache.fail = true;
        when(database.readHistory(scope, 10, 1)).thenThrow(new ManagedProblem(ManagedProblem.Code.DATABASE_UNAVAILABLE));
        assertThrows(ManagedProblem.class, () -> service.load(scope, 10, model(1, 1)));
    }
    @Test void corruptWindowsRecoverAndCacheFaultAfterCommitDoesNotEscape() {
        var s = snapshot(10, 1, 5); seed(s, List.of(new Pair(5, "a", "b")), 2);
        cache.value = "00000000000000000010000000001\n{broken";
        assertEquals(2, service.load(scope, 10, model(2, 1)).size());
        assertEquals(1, cache.discards); assertNotNull(HistoryWindow.decode(cache.value, 4096));
        cache.fail = true;
        assertDoesNotThrow(() -> service.afterSend(10, Optional.of(new Delivered(scope, conversation, 1, 2, 2, 10, new Pair(10, "c", "d")))));
        assertEquals("DEGRADED", service.status().state());
        cache.fail = false;
        service.load(scope, 10, model(2, 1)); assertEquals("AVAILABLE", service.status().state());
    }
    @Test void historicalSlicesAndOversizedCompleteWindowsAreNotRefilled() {
        var slice = snapshot(10, 2, 20); seed(slice, List.of(new Pair(5, "a", "b")), 2);
        service.load(scope, 10, model(2, 1)); assertEquals(0, cache.gets); assertNull(cache.value);
        var latest = snapshot(30, 1, 20); seed(latest, List.of(new Pair(20, "大".repeat(5000), "reply")), 2);
        assertEquals(2, service.load(scope, 30, model(2, 1)).size()); assertNull(cache.value);
    }
    @Test void keysSeparateInstallationRunUserBindingConversationAndModel() {
        var s = snapshot(10, 0, 0);
        assertNotEquals(service.key(s, 1), service.key(s, 2));
        var restart = new HistoryService(database, config, cache, installation, UUID.randomUUID().toString());
        assertNotEquals(service.key(s, 1), restart.key(s, 1));
        var other = new ManagedScope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "bot2", "sender2", 1, 1);
        assertNotEquals(service.key(s, 1), service.key(new HistorySnapshot(other, UUID.randomUUID().toString(), 10, 0, 0, 0), 1));
    }
    @Test void windowValidatesInt64OrderingCompletenessHeaderAndIncrementalGaps() {
        long revision = 9_007_199_254_740_993L;
        var window = new HistoryWindow(1, scope, conversation, 1, revision, revision, 99, 2, List.of(new Pair(98,"x","y"),new Pair(99,"z","😀")));
        assertEquals(window, HistoryWindow.decode(window.encode(4096), 4096));
        assertTrue(window.append(new Delivered(scope, conversation, revision + 1, revision + 2, revision + 2, 100, new Pair(100,"a","b"))).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HistoryWindow(1,scope,conversation,1,2,2,99,2,List.of(new Pair(99,"x","y"))));
        assertThrows(IllegalArgumentException.class, () -> new HistoryWindow(1,scope,conversation,1,2,2,99,2,List.of(new Pair(99,"x","y"),new Pair(98,"z","w"))));
        assertThrows(IllegalArgumentException.class, () -> HistoryWindow.decode("x" + window.encode(4096).substring(1), 4096));
        assertFalse(window.toString().contains("😀"));
    }
    @Test void identicalConcurrentMissesShareOneBodyReadAndFollowersReauthorize() throws Exception {
        var s = snapshot(10,1,5); var read = new HistoryRead(s,List.of(new Pair(5,"a","b")));
        when(database.historySnapshot(scope,10)).thenReturn(s);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var gets = new AtomicInteger();
        var port = new HistoryCachePort() {
            public String get(String key,long deadline) { gets.incrementAndGet(); return null; }
            public void put(String key,String value,long deadline) { }
            public void discard(String key,String value,long deadline) { }
            public void close() { }
        };
        var slowConfig = new ExternalServices.HistoryCache(true,"synthetic",Duration.ofSeconds(60),4096,Duration.ofMillis(100),Duration.ofSeconds(2),2,4);
        when(database.readHistory(scope,10,2)).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(2,TimeUnit.SECONDS)); return read; });
        try (var history = new HistoryService(database,slowConfig,port,installation,run);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var leader = executor.submit(() -> history.load(scope,10,model(2,1)));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            var followers = new ArrayList<Future<?>>();
            for (int i=0;i<3;i++) followers.add(executor.submit(() -> history.load(scope,10,model(2,1))));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while (gets.get()<4 && System.nanoTime()<deadline) Thread.sleep(1);
            assertEquals(4,gets.get()); Thread.sleep(30); release.countDown();
            assertEquals(2,leader.get(2,TimeUnit.SECONDS).size());
            for (var follower : followers) follower.get(2,TimeUnit.SECONDS);
            assertEquals(1,history.status().bodyQueries());
            assertEquals(8,history.status().metadataReads()); // 4 initial + leader source + 3 follower rechecks
        } finally { release.countDown(); }
    }
    @Test void cacheMissReauthorizationRevocationPropagatesAndBypassIsNotAnError() {
        var s = snapshot(10,1,5); seed(s,List.of(new Pair(5,"a","b")),2);
        var bypass = new HistoryCachePort() {
            public String get(String key,long deadline) { throw new HistoryCachePort.Bypassed(); }
            public void put(String key,String value,long deadline) { throw new HistoryCachePort.Bypassed(); }
            public void discard(String key,String value,long deadline) { }
            public void close() { }
        };
        try (var history = new HistoryService(database,config,bypass,installation,run)) {
            assertEquals(2,history.load(scope,10,model(2,1)).size());
            assertEquals(2,history.status().bypass()); assertEquals(0,history.status().error());
            when(database.readHistory(scope,10,2)).thenThrow(new ManagedProblem(ManagedProblem.Code.UNAUTHORIZED));
            assertEquals(ManagedProblem.Code.UNAUTHORIZED,assertThrows(ManagedProblem.class,() -> history.load(scope,10,model(2,1))).code());
        }
    }
    @Test void failedOrGappedDeliveryCannotAppendAndModelVersionsHaveSeparateWindows() {
        var s = snapshot(10,1,5); seed(s,List.of(new Pair(5,"a","b")),2);
        service.load(scope,10,model(2,1)); String original=cache.value;
        service.afterSend(10,Optional.empty()); assertEquals(original,cache.value);
        service.load(scope,10,model(2,1));
        service.afterSend(10,Optional.of(new Delivered(scope,conversation,2,3,3,10,new Pair(10,"gap","gap"))));
        assertEquals(original,cache.value); assertEquals(1,service.status().stale());
        service.load(scope,10,model(2,2));
        assertEquals(2,HistoryWindow.decode(cache.value,4096).modelVersion());
        assertEquals(2,service.status().bodyQueries());
    }
    private static final class MemoryCache implements HistoryCachePort {
        String value; int gets, discards; boolean fail;
        public String get(String key, long deadline) { gets++; if(fail) throw new IllegalStateException(); return value; }
        public void put(String key, String candidate, long deadline) {
            if(fail) throw new IllegalStateException();
            if(value == null || candidate.substring(0,29).compareTo(value.substring(0,29)) > 0) value = candidate;
        }
        public void discard(String key, String expected, long deadline) { discards++; if(Objects.equals(value,expected)) value=null; }
        public void close() { }
    }
}

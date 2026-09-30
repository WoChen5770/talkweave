package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.assistant.AssistantService;
import io.github.wochen5770.talkweave.conversation.ConversationTimeContext;
import io.github.wochen5770.talkweave.managed.cache.*;
import io.github.wochen5770.talkweave.managed.config.*;
import io.github.wochen5770.talkweave.managed.runtime.ManagedTurnWorker;
import io.github.wochen5770.talkweave.model.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed synthetic workload, real repository stages and Redis, no real model/WeChat calls. */
class HistoryBenchmarkIT {
    private static final int SAMPLES=20, N=20;
    enum Mode { MYSQL, COLD, HOT, FAILURE }
    @Test void compareHistoryAndCompleteDurableTurns() throws Exception {
        if(Boolean.getBoolean("talkweave.benchmark.tuned")) {
            measure(10000,4,Mode.MYSQL); measure(10000,4,Mode.HOT); return;
        }
        for(int rounds:new int[]{100,1000,10000}) for(int concurrency:new int[]{1,4})
            for(var mode:Mode.values()) measure(rounds,concurrency,mode);
    }
    private void measure(int rounds,int concurrency,Mode mode) throws Exception {
        var target=ExternalIntegrationTarget.load();
        var config=new ExternalServices.HistoryCache(mode!=Mode.MYSQL,target.config().historyCache().keyPrefix(),Duration.ofSeconds(60),262144,
                Duration.ofMillis(Boolean.getBoolean("talkweave.benchmark.tuned")?50:100),
                Duration.ofMillis(Boolean.getBoolean("talkweave.benchmark.tuned")?150:250),8,32);
        try(var fixture=new ExternalBusinessFixture(); var store=fixture.open();
            var stalled=new ServerSocket(0,8,InetAddress.getLoopbackAddress())) {
            var clock=Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"),ZoneOffset.UTC);
            var chat=new ManagedConversations(store,clock); var users=new ManagedUsers(store,clock);
            var settings=new ManagedSettings(store,clock); var usage=new ManagedUsage(store,clock);
            var model=settings.saveModel(new ModelConfiguration("https://synthetic.invalid","synthetic-key","synthetic","synthetic fixed prompt",false,
                    32768,1024,512,N,Duration.ofSeconds(1),Duration.ofSeconds(2),0));
            var scopes=new ArrayList<ManagedScope>(); var currents=new ArrayList<ManagedConversations.Event>();
            for(int i=0;i<concurrency;i++) {
                var scope=SyntheticHistory.user(store,clock); scopes.add(scope);
                currents.add(SyntheticHistory.seed(store,chat,scope,rounds));
            }
            var redisConfig=mode==Mode.FAILURE ? new ExternalServices.Redis("127.0.0.1",stalled.getLocalPort(),0,"","",false) : target.config().redis();
            try(var redis=new RedisHistoryCache(redisConfig,config)) {
                var port=new HistoryCachePort() {
                    private String key(String key) { return target.redisKey("benchmark")+":"+MysqlOwnership.digest(key); }
                    public String get(String key,long deadline) { return redis.get(key(key),deadline); }
                    public void put(String key,String value,long deadline) { redis.put(key(key),value,deadline); }
                    public void discard(String key,String value,long deadline) { redis.discard(key(key),value,deadline); }
                    public void close() { }
                };
                var times=new CopyOnWriteArrayList<Long>(); var modelTimes=new CopyOnWriteArrayList<Long>(); var totals=new CopyOnWriteArrayList<Long>();
                try(var history=spy(new HistoryService(chat,config,port,store.installationId(),store.cacheEpoch()));
                    var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                    doAnswer(invocation->{ long start=System.nanoTime(); try { return invocation.callRealMethod(); }
                        finally { times.add(System.nanoTime()-start); } }).when(history).load(any(),anyLong(),any());
                    var worker=new ManagedTurnWorker(users,settings,chat,usage,(snapshot,context,messages,observer)->{
                        observer.beforeAttempt(1); long start=System.nanoTime();
                        try { Thread.sleep(10); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                        modelTimes.add(System.nanoTime()-start);
                        observer.afterAttempt(1,ModelAttemptObserver.Outcome.SUCCEEDED,TokenUsage.unknown());
                        return new AssistantService.Reply("synthetic measured reply",false);
                    },(credentials,recipient,context,id,text)->{},4096,event->()->{},new ConversationTimeContext(clock,"Asia/Shanghai"),history);
                    // One complete warm-up turn per user in every mode; dataset progression is identical.
                    for(var scope:scopes) { assertTrue(worker.runOnce(scope)); assertTrue(worker.runOnce(scope)); }
                    if(mode==Mode.HOT) for(int i=0;i<scopes.size();i++) {
                        // Verify the warm-up filled a valid current version; a slow handshake is not silently counted as a hot hit.
                        var next=SyntheticHistory.accept(chat,scopes.get(i),"synthetic warm-up"); currents.set(i,next);
                        history.load(scopes.get(i),next.sequence(),model);
                        assertTrue(worker.runOnce(scopes.get(i))); assertTrue(worker.runOnce(scopes.get(i)));
                    }
                    else for(int i=0;i<scopes.size();i++) {
                        currents.set(i,SyntheticHistory.accept(chat,scopes.get(i),"synthetic warm-up"));
                        assertTrue(worker.runOnce(scopes.get(i))); assertTrue(worker.runOnce(scopes.get(i)));
                    }
                    times.clear(); modelTimes.clear(); var before=history.status(); long poolBefore=store.poolAcquireNanos();
                    var jobs=new ArrayList<Future<?>>();
                    for(int i=0;i<scopes.size();i++) { final int index=i; jobs.add(executor.submit(()->{
                        var scope=scopes.get(index); var previous=currents.get(index);
                        for(int sample=0;sample<SAMPLES;sample++) {
                            if(mode==Mode.COLD) {
                                String key=history.key(chat.historySnapshot(scope,previous.sequence()),model.version());
                                long deadline=System.nanoTime()+config.stageBudget().toNanos();
                                String raw=port.get(key,deadline); if(raw!=null) port.discard(key,raw,deadline);
                            }
                            long start=System.nanoTime();
                            previous=SyntheticHistory.accept(chat,scope,"synthetic measured question");
                            assertTrue(worker.runOnce(scope)); assertTrue(worker.runOnce(scope));
                            totals.add(System.nanoTime()-start);
                        }
                    })); }
                    for(var job:jobs) job.get(90,TimeUnit.SECONDS);
                    var after=history.status(); long bodies=after.bodyQueries()-before.bodyQueries(), metadata=after.metadataReads()-before.metadataReads();
                    assertEquals(SAMPLES*concurrency,totals.size()); assertEquals(totals.size(),modelTimes.size());
                    assertEquals(totals.size(),times.size());
                    if(mode==Mode.HOT) { assertEquals(0,bodies); assertEquals(totals.size(),after.hit()-before.hit()); }
                    else assertEquals(totals.size(),bodies);
                    for(var scope:scopes) {
                        var summary=usage.summary(scope.userId(),null,0,Long.MAX_VALUE);
                        assertNull(summary.knownCachedInputTokens()); assertNull(summary.coveredCacheHitRatio());
                    }
                    System.out.printf("BENCH_CONFIG command_ms=%d stage_ms=%d providerCacheTokens=UNKNOWN%n",config.commandTimeout().toMillis(),config.stageBudget().toMillis());
                    System.out.printf(Locale.ROOT,"BENCH rounds=%d concurrent=%d mode=%s samples=%d N=%d history_ms=%.3f/%.3f model_ms=%.3f/%.3f whole_ms=%.3f/%.3f body=%d metadataReads=%d metadataSql=%d poolAcquire_ms=%.3f hit=%d miss=%d stale=%d error=%d bypass=%d%n",
                            rounds,concurrency,mode,totals.size(),N,p(times,50),p(times,95),p(modelTimes,50),p(modelTimes,95),p(totals,50),p(totals,95),
                            bodies,metadata,4*metadata,(store.poolAcquireNanos()-poolBefore)/1_000_000d,
                            after.hit()-before.hit(),after.miss()-before.miss(),after.stale()-before.stale(),after.error()-before.error(),after.bypass()-before.bypass());
                }
            }
        }
    }
    private static double p(List<Long> values,int percentile) {
        var sorted=new ArrayList<>(values); Collections.sort(sorted);
        return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*percentile/100d)-1))/1_000_000d;
    }
}

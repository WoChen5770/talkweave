package io.github.wochen5770.talkweave.managed.cache;

import io.github.wochen5770.talkweave.managed.config.*;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.managed.persistence.ManagedConversations.*;
import io.lettuce.core.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.net.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalRedisIT {
    @Test void ownProxyDisconnectRecoversWithDefaultBudgetsWithoutRestartingRedis() throws Exception {
        var target=ExternalIntegrationTarget.load(); var original=target.config().redis();
        // TLS passthrough with a substituted host would invalidate certificate verification, never weaken it.
        assertFalse(original.tls(),"This explicitly no-SSL synthetic proxy fixture requires the operator's no-SSL target");
        var config=new ExternalServices.HistoryCache(true,target.config().historyCache().keyPrefix(),Duration.ofSeconds(60),4096,
                Duration.ofMillis(100),Duration.ofMillis(250),2,4);
        try(var proxy=new OwnProxy(original.host(),original.port());
            var cache=new RedisHistoryCache(new ExternalServices.Redis("127.0.0.1",proxy.server.getLocalPort(),original.database(),original.username(),original.password(),false),config)) {
            String key=target.redisKey("proxy-recovery"); String value=HistoryCacheFrame.encode(1,1,"{\"synthetic\":true}",4096);
            boolean ready=false;
            for(int i=0;i<4 && !ready;i++) try { cache.put(key,value,deadline()); ready=true; }
            catch(RuntimeException unavailable) { if(i==3) throw unavailable; Thread.sleep(1100); }
            assertEquals(value,cache.get(key,deadline()));
            proxy.disconnect(); long start=System.nanoTime();
            assertThrows(RuntimeException.class,()->cache.get(key,deadline()));
            assertTrue(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(500));
            for(int i=0;i<20;i++) assertThrows(HistoryCachePort.Bypassed.class,()->cache.get(key,deadline()));
            proxy.blocked=false; Thread.sleep(1100);
            assertEquals(value,cache.get(key,deadline()));
            System.out.println("REDIS ownProxyDisconnect=PASS cooldown=PASS recovered=PASS defaultBudget=100ms/250ms sharedServiceChanges=NONE");
        }
    }
    private static final class OwnProxy implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        volatile boolean blocked;
        OwnProxy(String host,int port) throws Exception {
            server=new ServerSocket(0,4,InetAddress.getLoopbackAddress());
            workers.submit(()->{
                while(!server.isClosed()) try {
                    var incoming=server.accept();
                    if(blocked || sockets.size()>=8) { incoming.close(); continue; }
                    sockets.add(incoming); var outgoing=new Socket(); sockets.add(outgoing);
                    outgoing.connect(new InetSocketAddress(host,port),1000);
                    workers.submit(()->copy(incoming,outgoing)); workers.submit(()->copy(outgoing,incoming));
                } catch(Exception ignored) { if(server.isClosed()) break; }
            });
        }
        private void copy(Socket source,Socket destination) {
            try { source.getInputStream().transferTo(destination.getOutputStream()); }
            catch(Exception ignored) { }
            finally { close(source); close(destination); }
        }
        private void close(Socket socket) { sockets.remove(socket); try { socket.close(); } catch(Exception ignored) { } }
        void disconnect() { blocked=true; for(var socket:List.copyOf(sockets)) close(socket); }
        public void close() throws Exception { server.close(); disconnect(); workers.shutdownNow(); assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS)); }
    }
    @Test void defaultBudgetsExactCasCorruptionOversizeExpiryAndLateReentry() throws Exception {
        try { exercise(); }
        catch (Throwable failure) { fail("Synthetic Redis verification failed ("+failure.getClass().getSimpleName()+")"); }
    }
    private void exercise() throws Exception {
        var target=ExternalIntegrationTarget.load(); var redis=target.config().redis();
        var uri=RedisURI.builder().withHost(redis.host()).withPort(redis.port()).withDatabase(redis.database()).withSsl(redis.tls()).withTimeout(Duration.ofSeconds(2));
        if (!redis.username().isEmpty()) uri.withAuthentication(redis.username(),redis.password());
        else if (!redis.password().isEmpty()) uri.withPassword(redis.password().toCharArray());
        var client=RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder().autoReconnect(false).requestQueueSize(8).build());
        var config=new ExternalServices.HistoryCache(true,target.config().historyCache().keyPrefix(),Duration.ofSeconds(1),4096,
                Duration.ofMillis(100),Duration.ofMillis(250),2,4);
        String key=target.redisKey("default-budget-window");
        try (var control=client.connect(); var cache=new RedisHistoryCache(redis,config)) {
            var commands=control.sync(); target.verifyRedis(commands.info("server"));
            // A fresh JVM/client handshake is separately warmed up; command/stage settings remain defaults.
            boolean connected=false;
            for(int i=0;i<4 && !connected;i++) {
                try { cache.get(key,deadline()); connected=true; }
                catch (RuntimeException unavailable) { if(i==3) throw unavailable; Thread.sleep(1100); }
            }
            var scope=new ManagedScope(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"synthetic-bot","synthetic-sender",1,1);
            String conversation=UUID.randomUUID().toString(); long revision=9_007_199_254_740_993L;
            var old=new HistoryWindow(1,scope,conversation,1,revision,revision,99,2,List.of(new Pair(98,"原始😀","a"),new Pair(99,"b","c")));
            var next=old.append(new Delivered(scope,conversation,revision,revision+1,revision+1,100,new Pair(100,"new","reply"))).orElseThrow();
            cache.put(key,next.encode(4096),deadline());
            cache.put(key,old.encode(4096),deadline()); // delayed refill cannot replace a newer revision
            assertEquals(next,HistoryWindow.decode(cache.get(key,deadline()),4096));
            var smaller=new HistoryWindow(1,scope,conversation,1,next.revision(),next.count(),100,1,List.of(next.pairs().getLast()));
            cache.put(key,smaller.encode(4096),deadline());
            assertEquals(next,HistoryWindow.decode(cache.get(key,deadline()),4096));
            assertTrue(commands.pttl(key)>0 && commands.pttl(key)<=1000);
            Thread.sleep(1100); assertNull(cache.get(key,deadline()));
            // Simulated key loss (expiry), not server eviction or a real database backup restore.
            cache.put(key,old.encode(4096),deadline());
            var snapshot=new HistorySnapshot(scope,conversation,101,next.revision(),next.count(),100);
            assertFalse(HistoryWindow.decode(cache.get(key,deadline()),4096).matches(snapshot,1,2));
            cache.put(key,next.encode(4096),deadline());
            String corrupt=HistoryCacheFrame.encode(next.revision()+1,2,"{broken",4096);
            commands.set(key,corrupt,SetArgs.Builder.px(1000));
            String raw=cache.get(key,deadline()); assertThrows(IllegalArgumentException.class,()->HistoryWindow.decode(raw,4096));
            cache.discard(key,raw,deadline()); cache.put(key,next.encode(4096),deadline());
            assertEquals(next,HistoryWindow.decode(cache.get(key,deadline()),4096));
            commands.set(key,"大".repeat(4096),SetArgs.Builder.px(1000));
            assertNull(cache.get(key,deadline())); // STRLEN rejects before returning the oversized body
            cache.put(key,next.encode(4096),deadline()); assertEquals(next,HistoryWindow.decode(cache.get(key,deadline()),4096));
            System.out.println("REDIS defaults=100ms/250ms exact64bitCAS=PASS smallerWindow=REJECTED expiry=PASS simulatedOldReentry=REJECTED corrupt=REPAIRED oversized=BOUNDED sharedCleanup=NONE");
        } finally { client.shutdown(Duration.ZERO,Duration.ofSeconds(1)); }
    }
    private static long deadline() { return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250); }
}

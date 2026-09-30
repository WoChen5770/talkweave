package io.github.wochen5770.talkweave.managed.cache;

import io.github.wochen5770.talkweave.managed.config.ExternalServices;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RedisHistoryCacheTest {
    @Test void stalledLocalPeerHasBoundedAdmissionCooldownAndClose() throws Exception {
        var config = new ExternalServices.HistoryCache(true,"synthetic",Duration.ofSeconds(60),4096,
                Duration.ofMillis(100),Duration.ofMillis(250),2,4);
        try (var server = new ServerSocket(0,16,InetAddress.getLoopbackAddress());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(1500);
            var accepted = executor.submit(() -> server.accept()); // Intentionally never speaks Redis.
            var redisConfig = new ExternalServices.Redis("127.0.0.1",server.getLocalPort(),0,"","",false);
            try (var cache = new RedisHistoryCache(redisConfig,config)) {
                // Prime client class loading before timing concurrent admission.
                assertThrows(RuntimeException.class,() -> cache.get("synthetic:one",System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250)));
                try (var socket = accepted.get(2,TimeUnit.SECONDS)) {
                    var results = new ArrayList<Future<Long>>();
                    for(int i=0;i<32;i++) results.add(executor.submit(() -> {
                        long start=System.nanoTime();
                        assertThrows(HistoryCachePort.Bypassed.class,() -> cache.get("synthetic:one",start+TimeUnit.MILLISECONDS.toNanos(250)));
                        return System.nanoTime()-start;
                    }));
                    for(var result:results) assertTrue(result.get(1,TimeUnit.SECONDS)<TimeUnit.MILLISECONDS.toNanos(250));
                    long start=System.nanoTime(); cache.close();
                    assertTrue(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(250));
                }
            }
        }
    }
}

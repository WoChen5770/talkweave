package io.github.wochen5770.talkweave.managed.cache;

import io.github.wochen5770.talkweave.managed.config.ExternalServices;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.DefaultClientResources;
import java.util.concurrent.*;

/** Bounded client, one shared async connection and no queued reconstruction executor. */
public final class RedisHistoryCache implements HistoryCachePort {
    private static final String BOUNDED_GET = "local n=redis.call('STRLEN',KEYS[1]); if n>tonumber(ARGV[1]) then return false end; return redis.call('GET',KEYS[1])";
    private final ExternalServices.HistoryCache config;
    private final DefaultClientResources resources;
    private final RedisClient client;
    private final RedisURI uri;
    private final Semaphore permits;
    private CompletableFuture<StatefulRedisConnection<String, String>> connection;
    private volatile boolean closed;
    private volatile long retryAfter;

    public RedisHistoryCache(ExternalServices.Redis redis, ExternalServices.HistoryCache config) {
        this.config = config; permits = new Semaphore(config.maxConcurrent());
        var builder = RedisURI.Builder.redis(redis.host(), redis.port()).withDatabase(redis.database()).withSsl(redis.tls())
                .withTimeout(config.commandTimeout());
        if (!redis.username().isEmpty()) builder.withAuthentication(redis.username(), redis.password());
        else if (!redis.password().isEmpty()) builder.withPassword(redis.password().toCharArray());
        uri = builder.build();
        resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        client = RedisClient.create(resources, uri);
        client.setOptions(ClientOptions.builder().autoReconnect(false).requestQueueSize(config.requestQueueSize())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(config.commandTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled(config.commandTimeout())).build());
    }
    private synchronized CompletableFuture<StatefulRedisConnection<String, String>> connection() {
        if (closed) throw new IllegalStateException("History cache unavailable");
        if (connection == null || connection.isCompletedExceptionally())
            connection = client.connectAsync(StringCodec.UTF8, uri).toCompletableFuture();
        return connection;
    }
    private synchronized void invalidate(CompletableFuture<StatefulRedisConnection<String, String>> attempted) {
        if (attempted == null || connection != attempted) return;
        connection = null;
        // A timed-out connect can still finish. Dispose it instead of leaving a detached reconnect loop.
        attempted.thenAccept(StatefulRedisConnection::closeAsync);
    }
    @FunctionalInterface private interface Command<T> { RedisFuture<T> run(StatefulRedisConnection<String, String> c); }
    private <T> T execute(long deadline, Command<T> command) {
        if (closed || System.nanoTime() >= deadline || System.nanoTime() < retryAfter || !permits.tryAcquire()) throw new HistoryCachePort.Bypassed();
        CompletableFuture<StatefulRedisConnection<String, String>> attempted = null;
        RedisFuture<T> pending = null;
        try {
            attempted = connection();
            var c = await(attempted, deadline);
            if (System.nanoTime() >= deadline) throw new TimeoutException();
            pending = command.run(c);
            return await(pending, deadline);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            if (pending != null) pending.cancel(false);
            invalidate(attempted);
            throw new IllegalStateException("History cache unavailable");
        } finally { permits.release(); }
    }
    private <T> T await(Future<T> future, long deadline) throws Exception {
        long remaining = Math.min(config.commandTimeout().toNanos(), deadline - System.nanoTime());
        if (remaining <= 0) throw new TimeoutException();
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }
    @Override public String get(String key, long deadline) {
        return execute(deadline, c -> c.async().eval(BOUNDED_GET, ScriptOutputType.VALUE, new String[]{key}, Integer.toString(config.maxEntryBytes())));
    }
    @Override public void put(String key, String value, long deadline) {
        execute(deadline, c -> c.async().eval(HistoryCacheFrame.COMPARE_SET, ScriptOutputType.INTEGER, new String[]{key}, value,
                Long.toString(config.ttl().toMillis()), Integer.toString(config.maxEntryBytes())));
    }
    @Override public void discard(String key, String expected, long deadline) {
        execute(deadline, c -> c.async().eval("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0",
                ScriptOutputType.INTEGER, new String[]{key}, expected));
    }
    @Override public void close() {
        synchronized(this) {
            if (closed) return;
            closed = true;
            if (connection != null) connection.thenAccept(StatefulRedisConnection::closeAsync);
        }
        var stopped = new CompletableFuture<Void>();
        client.shutdownAsync(0, 1, TimeUnit.SECONDS).whenComplete((ignored, clientFailure) ->
                resources.shutdown(0, 1, TimeUnit.SECONDS).addListener(future -> {
                    if (clientFailure == null && future.isSuccess()) stopped.complete(null);
                    else stopped.completeExceptionally(new IllegalStateException("History cache shutdown incomplete"));
                }));
        try { stopped.get(2,TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("History cache shutdown interrupted"); }
        catch (ExecutionException | TimeoutException incomplete) { throw new IllegalStateException("History cache shutdown incomplete"); }
    }
}

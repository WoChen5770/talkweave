package io.github.personalassistant.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** All contract tests use loopback and synthetic data, never a live account or model. */
public final class FakeHttpService implements AutoCloseable {
    public record Request(String method, URI uri, Map<String, List<String>> headers, String body) { }
    private record Reply(int status, String body, Map<String, String> headers, long delayMillis) { }
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

    public FakeHttpService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replies.poll();
            if (reply == null) reply = new Reply(500, "{\"error\":\"no synthetic response queued\"}", Map.of(), 0);
            if (reply.delayMillis() > 0) {
                try { Thread.sleep(reply.delayMillis()); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); return; }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            exchange.close();
        });
        server.start();
    }
    public URI baseUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    public void enqueue(int status, String body) { enqueue(status, body, Map.of()); }
    public void enqueue(int status, String body, Map<String, String> headers) { replies.add(new Reply(status, body, headers, 0)); }
    public void enqueueDelayed(int status, String body, long millis) { replies.add(new Reply(status, body, Map.of(), millis)); }
    public Request take() throws InterruptedException {
        Request request = requests.poll(5, TimeUnit.SECONDS);
        if (request == null) throw new AssertionError("Expected a loopback request");
        return request;
    }
    public int pendingRequests() { return requests.size(); }
    @Override public void close() { server.stop(0); executor.close(); }
}

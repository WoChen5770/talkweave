package io.github.wochen5770.talkweave.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Loopback only, no dependency probes and no credentials in responses. */
public final class HealthServer implements AutoCloseable {
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    public HealthServer(int port, BooleanSupplier live, Supplier<Map<String, Object>> state) throws java.io.IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid health port");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/health/live", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            boolean up = live.getAsBoolean();
            reply(exchange, up ? 200 : 503, Map.of("live", up));
        });
        server.createContext("/health/ready", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            try {
                Map<String, Object> snapshot = state.get();
                reply(exchange, Boolean.TRUE.equals(snapshot.get("ready")) ? 200 : 503, snapshot);
            } catch (RuntimeException failure) { reply(exchange, 503, Map.of("ready", false, "storage", "UNAVAILABLE")); }
        });
    }
    private void reply(com.sun.net.httpserver.HttpExchange exchange, int code, Object value) throws java.io.IOException {
        byte[] body = json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (var stream = exchange.getResponseBody()) { stream.write(body); }
        exchange.close();
    }
    public void start() { server.start(); }
    java.net.InetAddress address() { return server.getAddress().getAddress(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }
}
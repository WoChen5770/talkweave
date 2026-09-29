package io.github.personalassistant.runtime;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Container liveness probe only: no model, login, database or configuration is loaded. */
public final class HealthCheck {
    private HealthCheck() { }
    public static void main(String[] args) {
        int code = 1;
        try { code = check(Integer.parseInt(System.getenv().getOrDefault("HEALTH_PORT", "8081"))); }
        catch (RuntimeException ignored) { }
        System.exit(code);
    }
    static int check(int port) {
        if (port < 1 || port > 65535) return 1;
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 ? 0 : 1;
        } catch (Exception ignored) { return 1; }
    }
}

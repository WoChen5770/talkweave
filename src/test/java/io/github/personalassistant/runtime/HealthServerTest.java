package io.github.personalassistant.runtime;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HealthServerTest {
    @Test void onlyLoopbackLivenessDrivesContainerHealthEvenWhenDependenciesAreNotReady() throws Exception {
        var live = new AtomicBoolean(true);
        try (var server = new HealthServer(0, live::get, () -> Map.of("ready", false, "connection", "WAITING_LOGIN"));
             var http = HttpClient.newHttpClient()) {
            server.start(); assertThat(server.address().isLoopbackAddress()).isTrue();
            assertThat(HealthCheck.check(server.port())).isZero();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health/ready")).build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
            live.set(false); assertThat(HealthCheck.check(server.port())).isEqualTo(1);
        }
    }
    @Test void storageErrorsAreSanitizedAndDoNotChangeProcessLiveness() throws Exception {
        try (var server = new HealthServer(0, () -> true, () -> { throw new IllegalStateException("secret-db-detail"); });
             var http = HttpClient.newHttpClient()) {
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health/ready")).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503); assertThat(response.body()).contains("UNAVAILABLE").doesNotContain("secret-db-detail");
            assertThat(HealthCheck.check(server.port())).isZero();
        }
    }
}

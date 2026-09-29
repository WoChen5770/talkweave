package io.github.wochen5770.talkweave.runtime.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.runtime.PrivateStateFiles;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;

/** Explicit offline CI checks. Never creates bindings, contacts WeChat or calls a model. */
public final class ManagedContainerProbe {
    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("--web")) web();
            else if (args.length == 2 && args[0].equals("--legacy")) legacy(Path.of(args[1]));
            else if (args.length == 2) storage(args[0], Path.of(args[1]));
            else throw new IllegalArgumentException("Invalid probe arguments");
            System.out.println("CI_MANAGED_OK mode=" + args[0] + " externalCalls=0");
        } catch (Exception failure) {
            String category = failure instanceof ManagedProblem problem ? problem.code().name() : failure.getClass().getSimpleName();
            System.err.println("CI_MANAGED_FAILED category=" + category + ": inspect isolated fixture and configuration; no raw errors printed.");
            System.exit(1);
        }
    }
    static void legacy(Path root) throws Exception {
        Files.createDirectory(root);
        PrivateStateFiles.restrict(root, true);
        Path file = root.resolve("assistant.sqlite");
        Files.writeString(file, "synthetic-legacy-fixture", StandardOpenOption.CREATE_NEW);
        PrivateStateFiles.restrict(file, false);
        var directoryPermissions = Files.getPosixFilePermissions(root);
        var filePermissions = Files.getPosixFilePermissions(file);
        try (var ignored = ManagedStore.open(root)) {
            throw new IllegalStateException("Legacy directory accepted");
        } catch (ManagedProblem expected) {
            if (expected.code() != ManagedProblem.Code.INCOMPATIBLE_LAYOUT) throw expected;
        }
        try (var files = Files.list(root)) {
            if (files.count() != 1 || !Files.readString(file).equals("synthetic-legacy-fixture")
                    || !Files.getPosixFilePermissions(root).equals(directoryPermissions)
                    || !Files.getPosixFilePermissions(file).equals(filePermissions))
                throw new IllegalStateException("Legacy fixture modified");
        }
    }
    static void storage(String mode, Path root) throws Exception {
        if (!mode.equals("--write") && !mode.equals("--verify")) throw new IllegalArgumentException("Invalid mode");
        if (mode.equals("--write")) {
            Files.createDirectory(root); // Refuse all existing data, including production directories.
            PrivateStateFiles.restrict(root, true);
        } else if (!Files.readString(root.resolve("ci-only")).equals("managed-ci-v1")) {
            throw new IllegalArgumentException("Not a CI fixture");
        }
        try (var store = ManagedStore.open(root.resolve("state"))) {
            var settings = new ManagedSettings(store, Clock.systemUTC());
            var users = new ManagedUsers(store, Clock.systemUTC());
            if (mode.equals("--write")) {
                settings.setIdleMinutes(17);
                users.create("synthetic-container-user");
            } else if (settings.settings().idleMinutes() != 17 || users.list().size() != 1
                    || !users.list().getFirst().label().equals("synthetic-container-user")) {
                throw new IllegalStateException("Managed state did not survive replacement");
            }
        }
        if (mode.equals("--write")) {
            Files.writeString(root.resolve("ci-only"), "managed-ci-v1", StandardOpenOption.CREATE_NEW);
            PrivateStateFiles.restrict(root.resolve("ci-only"), false);
        }
        var expectedFile = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        var expectedDir = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        if (!Files.getPosixFilePermissions(root.resolve("state")).equals(expectedDir)
                || !Files.getPosixFilePermissions(root.resolve("state").resolve(ManagedStore.DATABASE)).equals(expectedFile))
            throw new IllegalStateException("Non-private state permissions");
    }
    private static void web() throws Exception {
        var json = new ObjectMapper();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build()) {
            String origin = "http://127.0.0.1:8680";
            var page = client.send(HttpRequest.newBuilder(URI.create(origin + "/")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            if (page.statusCode() != 200 || !page.body().contains("html")) throw new IllegalStateException("Missing page");
            var anonymous = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/admin/settings")).build(), HttpResponse.BodyHandlers.discarding());
            if (anonymous.statusCode() != 401) throw new IllegalStateException("Anonymous settings access");
            var session = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/admin/session")).build(), HttpResponse.BodyHandlers.ofString());
            var csrf = json.readTree(session.body());
            var login = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/admin/login"))
                    .header("Origin", origin).header(csrf.get("csrfHeader").asText(), csrf.get("csrfToken").asText())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=ci-admin&password=synthetic-container-password"))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (login.statusCode() != 200) throw new IllegalStateException("Bootstrap login failed");
            var settings = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/admin/settings")).build(), HttpResponse.BodyHandlers.ofString());
            if (settings.statusCode() != 200 || !json.readTree(settings.body()).get("model").isNull())
                throw new IllegalStateException("Model-free configuration unavailable");
        }
    }
}

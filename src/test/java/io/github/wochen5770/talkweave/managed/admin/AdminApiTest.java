package io.github.wochen5770.talkweave.managed.admin;

import com.fasterxml.jackson.databind.*;
import io.github.wochen5770.talkweave.AssistantApplication;
import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.persistence.SqliteStore;
import io.github.wochen5770.talkweave.runtime.AssistantRuntime;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AdminApiTest {
    private static final String PASSWORD = "synthetic-admin-password";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AssistantApplication.class)
                .withPropertyValues("spring.profiles.active=managed", "managed.health-port=0", "managed.directory=" + directory.resolve("managed"),
                        "managed.bootstrap-username=operator", "managed.bootstrap-password=" + PASSWORD);
    }
    @Test void independentStartupWithoutModelAndNoLegacyRuntime(CapturedOutput output) {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ManagedStore.class).doesNotHaveBean(SqliteStore.class).doesNotHaveBean(AssistantRuntime.class).doesNotHaveBean(ManagedBrowserFixture.class);
            var client = new Browser(context); client.login(PASSWORD, 200);
            context.getBean(io.github.wochen5770.talkweave.managed.runtime.RuntimeManager.class).reconcile();
            client.get("/api/admin/runtime").andExpect(status().isOk()).andExpect(jsonPath("$.health").value("RUNNING"));
            client.get("/api/admin/settings").andExpect(status().isOk()).andExpect(jsonPath("$.model").isEmpty())
                    .andExpect(jsonPath("$.settings.idleMinutes").value(30)).andExpect(jsonPath("$.runtimeAvailable").value(true));
            assertThat(context.getBean(ManagedSettings.class).administrator().orElseThrow().passwordHash()).startsWith("$2a$12$").doesNotContain(PASSWORD);
        });
        assertThat(output.getAll()).doesNotContain(PASSWORD, "Using generated security password");
    }
    @Test void absentBootstrapDoesNotOpenAccessAndResidualValuesCannotResetPassword() {
        runner().withPropertyValues("managed.bootstrap-password=").run(context -> {
            assertThat(context.getBean(ManagedSettings.class).administrator()).isEmpty();
            new Browser(context).login(PASSWORD, 401);
        });
        runner().run(context -> new Browser(context).login(PASSWORD, 200));
        runner().withPropertyValues("managed.bootstrap-password=another-valid-password", "managed.bootstrap-username=intruder").run(context -> {
            var browser = new Browser(context); browser.login(PASSWORD, 200);
            assertThat(context.getBean(ManagedSettings.class).administrator().orElseThrow().username()).isEqualTo("operator");
        });
    }
    @Test void anonymousAndForgedRequestsCannotMutateOrReadSecrets() {
        runner().run(context -> {
            var client = new Browser(context);
            for (String path : new String[]{"/api/admin/users", "/api/admin/runtime", "/api/admin/settings", "/api/admin/audit", "/api/admin/users/fake", "/api/admin/users/fake/conversations", "/api/admin/users/fake/usage", "/api/admin/users/fake/bindings/fake/qr"})
                client.get(path).andExpect(status().isUnauthorized());
            client.mvc.perform(post("/api/admin/login").servletPath("/api/admin/login").header("Origin", "http://localhost")
                    .param("username", "operator").param("password", PASSWORD)).andExpect(status().isForbidden());
            client.login(PASSWORD, 200);
            client.mvc.perform(post("/api/admin/users").servletPath("/api/admin/users").session(client.session)
                    .header("Origin", "http://localhost").contentType("application/json").content("{\"label\":\"forged\"}"))
                    .andExpect(status().isForbidden());
            client.mvc.perform(client.mutation(post("/api/admin/users"), "/api/admin/users").header("Origin", "https://evil.invalid")
                    .contentType("application/json").content("{\"label\":\"forged\"}"))
                    .andExpect(status().isForbidden());
            client.get("/api/admin/users").andExpect(jsonPath("$.length()").value(0));
            client.get("/managed-layout").andExpect(status().isForbidden());
            client.get("/talkweave-admin.sqlite").andExpect(status().isForbidden());
            client.get("/api/admin/users/fake/transcript").andExpect(status().isNotFound());
        });
    }
    @Test void logoutAndExpiredSessionDenySubsequentAccess() {
        runner().run(context -> {
            var client = new Browser(context); client.login(PASSWORD, 200);
            client.send(post("/api/admin/logout"), "/api/admin/logout", null).andExpect(status().isOk());
            assertThat(client.session.isInvalid()).isTrue(); client.session = null;
            client.get("/api/admin/users").andExpect(status().isUnauthorized());
            client.login(PASSWORD, 200);
            client.session.invalidate(); client.session = null;
            client.get("/api/admin/users").andExpect(status().isUnauthorized());
        });
    }
    @Test void loginThrottlesBeforeExpensiveAuthenticationAndDoesNotLogCredentials(CapturedOutput output) {
        runner().run(context -> {
            var client = new Browser(context);
            for (int i = 0; i < 5; i++) client.login("wrong-secret", 401);
            client.login(PASSWORD, 429);
            var events = context.getBean(ManagedAudit.class).page(Long.MAX_VALUE, 100);
            assertThat(events.stream().filter(e -> e.action().equals("LOGIN_FAILED"))).hasSize(5);
            assertThat(events.stream().filter(e -> e.action().equals("LOGIN_THROTTLED"))).hasSize(1);
            client.login(PASSWORD, 429);
            assertThat(context.getBean(ManagedAudit.class).page(Long.MAX_VALUE, 100)).hasSameSizeAs(events);
        });
        assertThat(output.getAll()).doesNotContain("wrong-secret", PASSWORD);
    }
    @Test void settingsAreValidatedAndKeyNeverReadBack(CapturedOutput output) {
        runner().run(context -> {
            var client = new Browser(context); client.login(PASSWORD, 200);
            client.send(put("/api/admin/settings/idle-timeout"), "/api/admin/settings/idle-timeout", Map.of("minutes", 7)).andExpect(status().isOk());
            for (Object invalid : new Object[]{0, 1441, 1.5, "7", null}) {
                String body = "{\"minutes\":" + JSON.writeValueAsString(invalid) + "}";
                client.mvc.perform(client.mutation(put("/api/admin/settings/idle-timeout"), "/api/admin/settings/idle-timeout").header("Origin", "http://localhost").contentType("application/json").content(body))
                        .andExpect(status().isBadRequest());
            }
            var model = new java.util.HashMap<String, Object>(Map.of("apiBaseUrl", "https://relay.invalid/gateway", "apiKey", "synthetic-write-only-key", "name", "any-model",
                    "systemPrompt", "你是助手", "contextCapacity", 8192, "outputBudget", 1024, "safetyMargin", 512, "historyRounds", 20, "requestTimeoutSeconds", 60, "totalTimeBudgetSeconds", 90));
            model.put("maxRetries", 2); model.put("allowInsecureLocalHttp", false);
            String saved = client.send(put("/api/admin/settings/model"), "/api/admin/settings/model", model).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(saved).doesNotContain("synthetic-write-only-key", "apiKey");
            var snapshot = context.getBean(ManagedSettings.class).currentModel().orElseThrow();
            model.remove("apiKey"); model.put("name", "next-model");
            client.send(put("/api/admin/settings/model"), "/api/admin/settings/model", model).andExpect(status().isOk());
            assertThat(snapshot.configuration().name()).isEqualTo("any-model");
            assertThat(context.getBean(ManagedSettings.class).currentModel().orElseThrow().configuration().apiKey()).isEqualTo("synthetic-write-only-key");
            model.put("outputBudget", 99999);
            client.send(put("/api/admin/settings/model"), "/api/admin/settings/model", model).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("assistant.model.output-budget"));
            client.get("/api/admin/settings").andExpect(jsonPath("$.model.name").value("next-model")).andExpect(jsonPath("$.settings.idleMinutes").value(7));
            assertThat(client.get("/api/admin/audit").andReturn().getResponse().getContentAsString()).doesNotContain("synthetic-write-only-key");
        });
        assertThat(output.getAll()).doesNotContain("synthetic-write-only-key");
    }
    @Test void userChangesAreIdempotentAndUsagePreservesUnknownValues() {
        runner().run(context -> {
            var client = new Browser(context); client.login(PASSWORD, 200);
            String label = "<img src=x onerror=alert(1)>";
            String body = client.send(post("/api/admin/users"), "/api/admin/users", Map.of("label", label)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String id = JSON.readTree(body).get("id").asText();
            String path = "/api/admin/users/" + id + "/enabled";
            String disabled = client.send(put(path), path, Map.of("enabled", false)).andReturn().getResponse().getContentAsString();
            assertThat(client.send(put(path), path, Map.of("enabled", false)).andReturn().getResponse().getContentAsString()).isEqualTo(disabled);
            client.get("/api/admin/users/" + id + "/usage").andExpect(status().isOk()).andExpect(jsonPath("$.attempts").value(0)).andExpect(jsonPath("$.knownInputTokens").isEmpty());
            client.get("/api/admin/users/" + id + "/usage?conversationId=another-users-conversation").andExpect(status().isNotFound());
            client.get("/api/admin/audit?limit=101").andExpect(status().isBadRequest());
        });
    }
    @Test void bindingApiProtectsImagesPairingAndTaskOwnership(CapturedOutput output) {
        var login = new java.util.concurrent.atomic.AtomicReference<>(new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginStatus(
                io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginPhase.WAIT, null, null));
        var codes = new java.util.concurrent.CopyOnWriteArrayList<String>();
        io.github.wochen5770.talkweave.managed.binding.BindingCoordinator.Ports ports = () -> new io.github.wochen5770.talkweave.managed.binding.BindingCoordinator.Port() {
            @Override public io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.QrCode request() {
                return new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.QrCode("secret-qr", "synthetic-qr-display");
            }
            @Override public io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginStatus poll(java.net.URI origin,
                    io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.QrCode qr, String code) {
                if (code != null) {
                    codes.add(code);
                    return new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginStatus(
                            io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginPhase.CONFIRMED, null,
                            new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.Credentials("bot-a", "secret-wechat-token",
                                    io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LOGIN_ORIGIN, "scanner-a"));
                }
                return login.get();
            }
            @Override public void validate(io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.Credentials c) { }
            @Override public void close() { }
        };
        runner().withBean("syntheticBindingPorts", io.github.wochen5770.talkweave.managed.binding.BindingCoordinator.Ports.class,
                () -> ports, definition -> definition.setPrimary(true)).run(context -> {
            var client = new Browser(context); client.login(PASSWORD, 200);
            var users = context.getBean(ManagedUsers.class); var a = users.create("A"); var b = users.create("B");
            String base = "/api/admin/users/" + a.id() + "/binding-attempts";
            var request = Map.of("mode", "INITIAL", "authEpoch", a.authEpoch(), "confirmReplacement", false);
            client.mvc.perform(post(base).servletPath(base).session(client.session).header("Origin", "http://localhost")
                    .contentType("application/json").content(JSON.writeValueAsString(request))).andExpect(status().isForbidden());
            String created = client.send(post(base), base, request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String id = JSON.readTree(created).get("id").asText();
            var coordinator = context.getBean(io.github.wochen5770.talkweave.managed.binding.BindingCoordinator.class);
            waitPhase(coordinator, a.id(), id, ManagedUsers.Phase.QR_READY);
            String qr = base + "/" + id + "/qr";
            client.get(qr).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control", "no-store"));
            new Browser(context).get(qr).andExpect(status().isUnauthorized());
            String wrong = "/api/admin/users/" + b.id() + "/binding-attempts/" + id;
            client.get(wrong).andExpect(status().isNotFound());
            client.send(post(wrong + "/cancel"), wrong + "/cancel", null).andExpect(status().isNotFound());
            client.get(base + "/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.imageReady").value(true));
            client.send(post(base), base, request).andExpect(status().isConflict());
            login.set(new io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginStatus(
                    io.github.wochen5770.talkweave.channel.wechat.WechatApiClient.LoginPhase.NEED_VERIFY_CODE, null, null));
            waitPhase(coordinator, a.id(), id, ManagedUsers.Phase.NEED_PAIRING);
            String pair = base + "/" + id + "/pairing";
            client.send(post(pair), pair, Map.of("code", "secret-bad-code")).andExpect(status().isBadRequest());
            client.send(post(pair), pair, Map.of("code", "84930271")).andExpect(status().isOk());
            waitPhase(coordinator, a.id(), id, ManagedUsers.Phase.IDENTITY_UNVERIFIED);
            assertThat(codes).containsExactly("84930271");
            client.get(qr).andExpect(status().isConflict());
            String state = client.get(base + "/" + id).andReturn().getResponse().getContentAsString();
            assertThat(state).contains("IDENTITY_UNVERIFIED").doesNotContain("secret-wechat-token", "secret-qr", "scanner-a", "84930271");
            assertThat(client.get("/api/admin/audit").andReturn().getResponse().getContentAsString())
                    .contains(id).doesNotContain("secret-wechat-token", "secret-qr", "84930271");
        });
        assertThat(output.getAll()).doesNotContain("secret-wechat-token", "secret-qr", "secret-bad-code", "84930271");
    }
    @Test void metadataAndFilteredUsageAreScopedPrivateAndTruthful(CapturedOutput output) {
        runner().run(context -> {
            // Never let test identities reach production channel/model clients.
            context.getBean(io.github.wochen5770.talkweave.managed.runtime.RuntimeManager.class).close();
            var seed = AdminUsageFixture.seed(context.getBean(ManagedStore.class));
            var client = new Browser(context); client.login(PASSWORD, 200);
            String a = "/api/admin/users/" + seed.a(), b = "/api/admin/users/" + seed.b();
            String detail = client.get(a).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.bound").value(true)).andReturn().getResponse().getContentAsString();
            String page = client.get(a + "/conversations?limit=1").andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(seed.secondConversation()))
                    .andReturn().getResponse().getContentAsString();
            long before = JSON.readTree(page).get(0).get("sequence").asLong();
            client.get(a + "/conversations?limit=1&before=" + before).andExpect(jsonPath("$[0].id").value(seed.firstConversation()));
            client.get(b + "/conversations").andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(seed.otherConversation()));
            client.get(a + "/usage?conversationId=" + seed.otherConversation()).andExpect(status().isNotFound());
            for (String query : new String[]{"limit=0", "limit=101", "before=0", "before=bad"})
                client.get(a + "/conversations?" + query).andExpect(status().isBadRequest());
            for (String query : new String[]{"from=-1", "from=10&to=10", "from=10&to=9", "from=bad"})
                client.get(a + "/usage?" + query).andExpect(status().isBadRequest());
            client.get("/api/admin/users/missing").andExpect(status().isNotFound());
            client.get("/api/admin/users/missing/conversations").andExpect(status().isNotFound());
            client.get(a + "/usage").andExpect(jsonPath("$.attempts").value(4)).andExpect(jsonPath("$.unreportedAttempts").value(1))
                    .andExpect(jsonPath("$.incompleteAttempts").value(2)).andExpect(jsonPath("$.invalidAttempts").value(1))
                    .andExpect(jsonPath("$.knownInputTokens").value(2020)).andExpect(jsonPath("$.knownOutputTokens").value(105))
                    .andExpect(jsonPath("$.knownCachedInputTokens").value(1500)).andExpect(jsonPath("$.coveredCacheHitRatio").value(0.75));
            client.get(a + "/usage?conversationId=" + seed.secondConversation()).andExpect(jsonPath("$.attempts").value(2))
                    .andExpect(jsonPath("$.invalidAttempts").value(1)).andExpect(jsonPath("$.coveredCacheHitRatio").isEmpty());
            String window = "&from=" + AdminUsageFixture.START + "&to=" + (AdminUsageFixture.START + 60_000);
            client.get(a + "/usage?conversationId=" + seed.firstConversation() + window)
                    .andExpect(jsonPath("$.attempts").value(1)).andExpect(jsonPath("$.knownInputTokens").value(2000));
            client.get(a + "/usage?from=" + (AdminUsageFixture.START + 60_000) + "&to=" + (AdminUsageFixture.START + 120_000))
                    .andExpect(jsonPath("$.attempts").value(1)).andExpect(jsonPath("$.knownInputTokens").isEmpty())
                    .andExpect(jsonPath("$.coveredCacheHitRatio").isEmpty());
            client.get(a + "/usage?from=" + (AdminUsageFixture.START + 42 * 60_000) + "&to=" + (AdminUsageFixture.START + 43 * 60_000))
                    .andExpect(jsonPath("$.knownInputTokens").value(0)).andExpect(jsonPath("$.incompleteAttempts").value(0));
            client.get(a + "/usage?to=1").andExpect(jsonPath("$.attempts").value(0)).andExpect(jsonPath("$.knownInputTokens").isEmpty());
            String audit = client.get("/api/admin/audit?userId=" + seed.a()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JSON.readTree(audit)).isNotEmpty();
            for (var entry : JSON.readTree(audit)) assertThat(entry.get("target").asText()).startsWith(seed.a());
            long oldest = JSON.readTree(audit).get(JSON.readTree(audit).size()-1).get("sequence").asLong();
            client.get("/api/admin/audit?userId=" + seed.a() + "&before=" + oldest).andExpect(jsonPath("$.length()").value(0));
            client.get("/api/admin/audit?userId=missing").andExpect(status().isNotFound());
            assertThat(detail + page + audit).doesNotContain("private-chat-body", "private-model-answer", "private-wechat-token",
                    "private-context", "private-cursor", "private-sender", "private-account", "private-scanner", "synthetic-model-secret");
        });
        assertThat(output.getAll()).doesNotContain("private-chat-body", "private-wechat-token", "synthetic-model-secret");
    }

    private static void waitPhase(io.github.wochen5770.talkweave.managed.binding.BindingCoordinator coordinator, String user, String id,
                                  ManagedUsers.Phase phase) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            coordinator.tick();
            if (coordinator.get(user, id).phase() == phase) return;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        throw new AssertionError("Synthetic binding phase timeout");
    }

    static final class Browser {
        final MockMvc mvc;
        MockHttpSession session;
        String header;
        String token;
        Browser(WebApplicationContext context) throws Exception {
            mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build(); refresh();
        }
        void refresh() throws Exception {
            var result = get("/api/admin/session").andExpect(status().isOk()).andReturn();
            session = (MockHttpSession) result.getRequest().getSession();
            JsonNode value = JSON.readTree(result.getResponse().getContentAsString()); header = value.get("csrfHeader").asText(); token = value.get("csrfToken").asText();
        }
        void login(String password, int status) throws Exception {
            if (session == null) refresh();
            mvc.perform(mutation(post("/api/admin/login"), "/api/admin/login").header("Origin", "http://localhost").param("username", "operator").param("password", password))
                    .andExpect(status().is(status));
            if (status == 200) refresh();
        }
        ResultActions get(String path) throws Exception {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).servletPath(path.split("\\?")[0]);
            if (session != null) request.session(session); return mvc.perform(request);
        }
        MockHttpServletRequestBuilder mutation(MockHttpServletRequestBuilder request, String path) {
            return request.servletPath(path).session(session).header(header, token);
        }
        ResultActions send(MockHttpServletRequestBuilder request, String path, Object value) throws Exception {
            request = mutation(request, path).header("Origin", "http://localhost");
            if (value != null) request.contentType("application/json").content(JSON.writeValueAsString(value));
            return mvc.perform(request);
        }
    }
}

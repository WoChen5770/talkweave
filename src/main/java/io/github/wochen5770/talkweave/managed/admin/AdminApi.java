package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.model.ModelConfiguration;

import io.github.wochen5770.talkweave.managed.persistence.*;
import io.github.wochen5770.talkweave.managed.runtime.*;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("managed")
@RequestMapping("/api/admin")
public class AdminApi {
    private final ManagedSettings settings;
    private final ManagedUsers users;
    private final ManagedUsage usage;
    private final ManagedAudit audit;
    private final RuntimeManager runtime;
    public AdminApi(ManagedSettings settings, ManagedUsers users, ManagedUsage usage, ManagedAudit audit, RuntimeManager runtime) {
        this.runtime = runtime;
        this.settings = settings; this.users = users; this.usage = usage; this.audit = audit;
    }
    public record Session(boolean authenticated, String csrfHeader, String csrfToken) { }
    public record State(ManagedSettings.Settings settings, ModelView model, boolean runtimeAvailable, String bindingCapability) { }
    public record Label(String label) { }
    public record Enabled(Boolean enabled) { }
    public record Idle(int minutes) { }
    public record ModelView(long version, String apiBaseUrl, String name, String systemPrompt, boolean keyConfigured,
                            boolean allowInsecureLocalHttp, int contextCapacity, int outputBudget, int safetyMargin,
                            int historyRounds, long requestTimeoutSeconds, long totalTimeBudgetSeconds, int maxRetries) {
        static ModelView from(ManagedSettings.ModelSnapshot snapshot) {
            var m = snapshot.configuration();
            return new ModelView(snapshot.version(), m.apiBaseUrl(), m.name(), m.systemPrompt(), true,
                    m.allowInsecureLocalHttp(), m.contextCapacity(), m.outputBudget(), m.safetyMargin(), m.historyRounds(),
                    m.requestTimeout().toSeconds(), m.totalTimeBudget().toSeconds(), m.maxRetries());
        }
    }
    public record ModelUpdate(String apiBaseUrl, String apiKey, String name, String systemPrompt, boolean allowInsecureLocalHttp,
                              int contextCapacity, int outputBudget, int safetyMargin, int historyRounds,
                              long requestTimeoutSeconds, long totalTimeBudgetSeconds, int maxRetries) {
        @Override public String toString() { return "ModelUpdate[REDACTED]"; }
    }
    @GetMapping("/session") Session session(Authentication auth, CsrfToken csrf) {
        return new Session(auth != null && auth.isAuthenticated(), csrf.getHeaderName(), csrf.getToken());
    }
    @GetMapping("/settings") State state() {
        // Runtime wiring exists, but production identity provisioning remains unverified.
        return new State(settings.settings(), settings.currentModel().map(ModelView::from).orElse(null), true, "IDENTITY_UNVERIFIED");
    }
    @PutMapping("/settings/idle-timeout") ManagedSettings.Settings idle(@RequestBody Idle value) {
        settings.setIdleMinutes(value.minutes()); return settings.settings();
    }
    @PutMapping("/settings/model") ModelView model(@RequestBody ModelUpdate value) {
        String key = value.apiKey();
        if (key == null) key = settings.currentModel().map(s -> s.configuration().apiKey()).orElse(null);
        if (value.systemPrompt() != null && value.systemPrompt().length() > 32_768 || key != null && key.length() > 4096)
            throw new ManagedProblem(ManagedProblem.Code.INVALID_INPUT);
        var model = new ModelConfiguration(value.apiBaseUrl(), key, value.name(), value.systemPrompt(),
                value.allowInsecureLocalHttp(), value.contextCapacity(), value.outputBudget(), value.safetyMargin(), value.historyRounds(),
                Duration.ofSeconds(value.requestTimeoutSeconds()), Duration.ofSeconds(value.totalTimeBudgetSeconds()), value.maxRetries());
        return ModelView.from(settings.saveModel(model));
    }
    public record UserView(String id, String label, boolean enabled, long authEpoch, long createdAt, boolean bound,
                           boolean sessionActive, Long lastActivity, ManagedUsers.Attempt currentAttempt, ChannelRuntime.Status connection) { }
    @GetMapping("/runtime") RuntimeManager.Status runtime() { return runtime.status(); }
    @GetMapping("/users") List<UserView> users() {
        return users.overview().stream().map(this::userView).toList();
    }
    private UserView userView(ManagedUsers.Overview u) {
        return new UserView(u.id(), u.label(), u.enabled(), u.authEpoch(), u.createdAt(), u.bound(), u.sessionActive(),
                u.lastActivity(), u.currentAttempt(), runtime.userStatus(u));
    }
    @GetMapping("/users/{id}") UserView user(@PathVariable String id) { return userView(users.overview(id)); }
    @GetMapping("/users/{id}/conversations") List<ManagedUsage.Conversation> conversations(@PathVariable String id,
            @RequestParam(defaultValue = "9223372036854775807") long before, @RequestParam(defaultValue = "50") int limit) {
        return usage.conversations(id, before, limit);
    }
    @PostMapping("/users") ManagedUsers.User create(@RequestBody Label value) { return users.create(value.label()); }
    @PutMapping("/users/{id}/enabled") ManagedUsers.User enabled(@PathVariable String id, @RequestBody Enabled value) {
        if (value.enabled() == null) throw new ManagedProblem(ManagedProblem.Code.INVALID_INPUT);
        var updated = users.setEnabled(id, value.enabled()); runtime.reconcile(); return updated;
    }
    @GetMapping("/users/{id}/usage") ManagedUsage.Summary usage(@PathVariable String id,
            @RequestParam(required = false) String conversationId,
            @RequestParam(defaultValue = "0") long from,
            @RequestParam(defaultValue = "9223372036854775807") long to) {
        return usage.summary(id, conversationId, from, to);
    }
    @GetMapping("/audit") List<ManagedAudit.Entry> audit(@RequestParam(required = false) String userId,
                                                       @RequestParam(defaultValue = "9223372036854775807") long before,
                                                       @RequestParam(defaultValue = "50") int limit) { return audit.page(userId, before, limit); }
}

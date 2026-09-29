package io.github.personalassistant.runtime.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.personalassistant.assistant.AssistantService;
import io.github.personalassistant.conversation.DialogueMessage;
import io.github.personalassistant.model.CompatibleChatClient;
import io.github.personalassistant.runtime.AssistantProperties;
import io.github.personalassistant.runtime.ConfigurationProblem;
import io.github.personalassistant.runtime.RemoteFailure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import static io.github.personalassistant.conversation.DialogueMessage.Role.*;

/** Opt-in, at most two paid requests. No Spring application, WeChat, database, or transcript files. */
public final class ModelConnectivityProbe {
    private static final String SYSTEM = "This is a short connectivity test. Follow the user's instructions exactly.";
    private final PrivateProbeFiles files;
    private final AssistantProperties.Model config;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private String phase = "READY";
    private int attempts;
    private int completed;

    ModelConnectivityProbe(PrivateProbeFiles files, AssistantProperties.Model config) {
        this.files = files;
        this.config = config;
        evidence.put("configuredModel", redact(config.name(), config.apiKey()));
        evidence.put("completionEndpoint", redact(config.completionUri().toString(), config.apiKey()));
        evidence.put("contextCapacity", config.contextCapacity());
        evidence.put("contextCapacityVerified", false); // A short test cannot prove the context ceiling.
        evidence.put("requestFields", List.of("model", "messages", "stream", "max_tokens"));
        evidence.put("stream", false);
        evidence.put("outputBudget", config.outputBudget());
        evidence.put("requestTimeoutSeconds", config.requestTimeout().toSeconds());
        evidence.put("maxAttempts", 2);
        evidence.put("automaticRetries", 0);
        evidence.put("secondRequestRoles", List.of("system", "user", "assistant", "user"));
    }

    public static void main(String[] args) { System.exit(runCommand(args)); }

    static int runCommand(String[] args) {
        ModelConnectivityProbe probe = null;
        try {
            boolean checkOnly = args.length == 2 && args[0].equals("--check-config");
            boolean live = args.length == 3 && args[0].equals("--allow-paid-model-check");
            if (!checkOnly && !live) {
                System.err.println("Use --check-config <local-yaml> or --allow-paid-model-check <local-yaml> <new-private-directory>");
                return 2;
            }
            var config = probeConfig(loadConfig(Path.of(args[1])));
            if (checkOnly) {
                System.out.println(new ObjectMapper().writeValueAsString(Map.of("configurationValid", true,
                        "contextCapacity", config.contextCapacity(), "probeOutputBudget", config.outputBudget(),
                        "requestTimeoutSeconds", config.requestTimeout().toSeconds(), "maxAttempts", 2)));
                return 0;
            }
            probe = new ModelConnectivityProbe(new PrivateProbeFiles(Path.of(args[2])), config);
            probe.save("READY"); // Persist before constructing any network client.
            try (var client = new CompatibleChatClient(config)) {
                probe.execute(client, UUID.randomUUID().toString());
            }
            return probe.phase.equals("COMPLETED") ? 0 : 1;
        } catch (ConfigurationProblem failure) {
            if (probe != null) probe.fail(failure);
            System.err.println(failure.getMessage()); // Only our fixed field/rule text, no rejected values.
            return 2;
        } catch (Exception failure) {
            if (probe != null) probe.fail(failure);
            System.err.println("Model probe stopped. No automatic retry; consult the private status file.");
            return 1;
        }
    }

    static AssistantProperties.Model loadConfig(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) {
                throw new ConfigurationProblem("model probe file", "must be a regular local YAML file of at most 64 KiB");
            }
            var environment = new StandardEnvironment();
            var loader = new YamlPropertySourceLoader();
            for (var source : loader.load("probe-defaults", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
            var sources = loader.load("probe-local", new FileSystemResource(path));
            if (sources.size() != 1) throw new ConfigurationProblem("model probe file", "must contain one YAML document");
            environment.getPropertySources().addFirst(sources.getFirst());
            // Imports and profile activation are deliberately not processed by this isolated probe.
            var config = Binder.get(environment).bind("assistant.model", AssistantProperties.Model.class)
                    .orElseThrow(() -> new ConfigurationProblem("assistant.model", "is required"));
            config.validate();
            return config;
        } catch (ConfigurationProblem failure) {
            throw failure;
        } catch (Exception ignored) {
            // YAML and binding exceptions may include the entire secret-bearing source line.
            throw new ConfigurationProblem("model probe file", "could not be read or bound; check YAML syntax and field types locally");
        }
    }

    static AssistantProperties.Model probeConfig(AssistantProperties.Model original) {
        original.validate();
        var timeout = original.requestTimeout().compareTo(Duration.ofSeconds(60)) > 0
                ? Duration.ofSeconds(60) : original.requestTimeout();
        var config = new AssistantProperties.Model(original.apiBaseUrl(), original.apiKey(), original.name(), SYSTEM,
                original.allowInsecureLocalHttp(), original.contextCapacity(), Math.min(original.outputBudget(), 256),
                original.safetyMargin(), original.historyRounds(), timeout, timeout, 0);
        config.validate();
        return config;
    }

    void execute(AssistantService service, String nonce) throws Exception {
        var history = new ArrayList<DialogueMessage>();
        history.add(new DialogueMessage(DialogueMessage.Role.SYSTEM, SYSTEM));
        history.add(new DialogueMessage(USER, "Remember this code for my next question: " + nonce + ". Reply only OK."));
        var first = call(service, history);
        evidence.put("firstReplyNonempty", true);
        evidence.put("firstReplyTruncated", first.truncated());
        if (first.truncated()) { save("FIRST_REPLY_TRUNCATED"); return; }
        history.add(new DialogueMessage(ASSISTANT, first.text()));
        history.add(new DialogueMessage(USER, "What code did I ask you to remember in my previous message? Reply with only that code."));
        var second = call(service, history);
        boolean matches = nonce.equals(second.text().strip());
        evidence.put("secondReplyNonempty", true);
        evidence.put("secondReplyTruncated", second.truncated());
        evidence.put("contextRecallMatched", matches);
        evidence.put("responseTextExtracted", true);
        save(matches && !second.truncated() ? "COMPLETED" : "CONTEXT_NOT_CONFIRMED");
    }

    private AssistantService.Reply call(AssistantService service, List<DialogueMessage> messages) throws Exception {
        long conservativeInput = messages.stream().mapToLong(m -> m.text().getBytes(StandardCharsets.UTF_8).length + 32L).sum();
        if (conservativeInput + config.outputBudget() + config.safetyMargin() >= config.contextCapacity()) {
            throw new ConfigurationProblem("model probe budget", "does not leave enough space for the synthetic conversation");
        }
        if (attempts >= 2) throw new IllegalStateException("Probe attempt budget exhausted");
        attempts++;
        save("CALLING_" + attempts); // A crash here is not proof that the request was unsent; never resume this run.
        var reply = service.answer(List.copyOf(messages));
        if (reply == null || reply.text() == null || reply.text().isBlank()) {
            throw new RemoteFailure(RemoteFailure.Source.MODEL, RemoteFailure.Kind.INVALID_RESPONSE);
        }
        completed++;
        save("RECEIVED_" + attempts);
        return reply;
    }

    void fail(Exception failure) {
        String category = failure instanceof RemoteFailure remote ? remote.kind().name() : "LOCAL_OPERATION";
        evidence.put("failureCategory", category);
        if (failure instanceof RemoteFailure remote && remote.code() != null) evidence.put("httpStatus", remote.code());
        try { save("FAILED_" + category); } catch (Exception ignored) { }
    }

    private void save(String next) throws Exception {
        phase = next;
        evidence.put("phase", next);
        evidence.put("attemptsStarted", attempts);
        evidence.put("repliesReceived", completed);
        evidence.put("updatedAt", Instant.now().toString());
        files.writeJson("evidence.json", evidence);
        files.writeJson("status.json", Map.of("phase", next, "attemptsStarted", attempts,
                "repliesReceived", completed, "updatedAt", Instant.now().toString()));
        System.out.println("Model probe phase: " + next);
    }

    private static String redact(String value, String secret) { return value.replace(secret, "[REDACTED]"); }
}
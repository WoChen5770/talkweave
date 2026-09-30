package io.github.wochen5770.talkweave.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static org.assertj.core.api.Assertions.*;

/** Offline policy assertions complement actionlint; they do not claim GitHub or container execution. */
@SuppressWarnings("unchecked")
class WorkflowContractTest {
    private Map<String, Object> yaml(String file) throws Exception {
        var options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options)).load(Files.readString(Path.of(file)));
    }
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private Map<String, Object> jobs() throws Exception { return map(yaml(".github/workflows/container.yml").get("jobs")); }
    private List<Map<String, Object>> steps(Object job) { return (List<Map<String, Object>>) map(job).get("steps"); }
    @Test void pullRequestsAndBuildsHaveNoPublicationCredentials() throws Exception {
        var workflow = yaml(".github/workflows/container.yml");
        assertThat(map(workflow.get("on"))).containsKey("pull_request").doesNotContainKey("pull_request_target");
        assertThat(map(workflow.get("permissions"))).containsOnly(entry("contents", "read"));
        var jobs = map(workflow.get("jobs"));
        for (String id : List.of("verify", "build")) {
            var job = map(jobs.get(id));
            if (job.containsKey("permissions")) assertThat(map(job.get("permissions"))).containsOnly(entry("contents", "read"));
            assertThat(job.toString()).doesNotContain("secrets.", "login-action", "packages=write");
        }
        var target = steps(jobs.get("verify")).stream().filter(step -> "target".equals(step.get("id"))).findFirst().orElseThrow();
        assertThat(map(target.get("env")).get("REQUEST_PUBLISH").toString()).contains("github.event_name == 'push'", "workflow_dispatch", "inputs.publish");
        assertThat(target.get("run").toString()).contains("REQUEST_PUBLISH", "refs/heads/$DEFAULT_BRANCH", "refs/tags/v*");
    }
    @Test void allExternalActionsArePinnedAndCheckoutDoesNotPersistCredentials() throws Exception {
        for (Object job : jobs().values()) for (var step : steps(job)) {
            if (step.containsKey("uses")) {
                String use = step.get("uses").toString();
                assertThat(use).matches("[a-zA-Z0-9_./-]+@[0-9a-f]{40}");
                if (use.startsWith("actions/checkout@")) assertThat(map(step.get("with"))).containsEntry("persist-credentials", false);
            }
        }
    }
    @Test void publicationPolicyAllowsOnlyRequestedDefaultBranchOrVersionTags() throws Exception {
        var workflow = yaml(".github/workflows/container.yml");
        var triggers = map(workflow.get("on"));
        assertThat(map(triggers.get("push"))).containsEntry("branches", List.of("**"))
                .containsEntry("tags", List.of("v*"));
        var inputs = map(map(triggers.get("workflow_dispatch")).get("inputs"));
        assertThat(map(inputs.get("publish"))).containsEntry("default", false);
        assertThat(map(inputs.get("external_integration"))).containsEntry("default", false);
        var target = steps(jobs().get("verify")).stream()
                .filter(step -> "target".equals(step.get("id"))).findFirst().orElseThrow();
        assertThat(map(target.get("env"))).containsEntry("REQUEST_PUBLISH",
                "${{ github.event_name == 'push' || (github.event_name == 'workflow_dispatch' && inputs.publish) }}");
        // Exercise the actual decision fragment; repository lowercasing below it requires Bash 4+.
        String script = target.get("run").toString();
        String decision = script.substring(0, script.indexOf("image_repository=")) + "printf '%s' \"$publish\"\n";
        for (String defaultBranch : List.of("main", "release")) {
            for (String ref : List.of("refs/heads/" + defaultBranch, "refs/tags/v1.2.3",
                    "refs/heads/feature/test", "refs/heads/v1.2.3", "refs/tags/test", "refs/pull/42/merge")) {
                for (boolean requested : List.of(false, true)) {
                    var builder = new ProcessBuilder("bash", "-c", decision).redirectErrorStream(true);
                    builder.environment().putAll(Map.of("DEFAULT_BRANCH", defaultBranch,
                            "GITHUB_REF", ref, "REQUEST_PUBLISH", Boolean.toString(requested)));
                    var process = builder.start();
                    try {
                        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(process.exitValue()).isZero();
                        boolean expected = requested && (ref.equals("refs/heads/" + defaultBranch) || ref.startsWith("refs/tags/v"));
                        assertThat(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                                .as("requested=%s ref=%s default=%s", requested, ref, defaultBranch)
                                .isEqualTo(Boolean.toString(expected));
                    } finally { process.destroyForcibly(); }
                }
            }
        }
    }
    @Test void bothArchitecturesAreLoadedAndCheckedBeforeExport() throws Exception {
        var build = map(jobs().get("build"));
        var matrix = map(map(build.get("strategy")).get("matrix"));
        var include = (List<Map<String, Object>>) matrix.get("include");
        assertThat(include).extracting(row -> row.get("platform")).containsExactlyInAnyOrder("linux/amd64", "linux/arm64");
        assertThat(include.stream().filter(row -> "arm64".equals(row.get("arch"))).findFirst().orElseThrow()).containsEntry("execution", "qemu-on-amd64");
        var steps = steps(build);
        var image = steps.stream().filter(step -> "image".equals(step.get("id"))).findFirst().orElseThrow();
        assertThat(map(image.get("with"))).containsEntry("load", true).containsEntry("push", false);
        String sequence = steps.toString();
        assertThat(sequence.indexOf("image-smoke.sh")).isLessThan(sequence.indexOf("docker save"));
        assertThat(sequence).contains("BUILDER_IMAGE=${{ steps.bases.outputs.builder }}", "RUNTIME_IMAGE=${{ steps.bases.outputs.runtime }}");
    }
    @Test void publishDependsOnAllTestsAndTransfersOnlyTestedImages() throws Exception {
        var jobs = jobs();
        assertThat(map(jobs.get("build")).get("needs")).isEqualTo("verify");
        var publish = map(jobs.get("publish-images"));
        assertThat((List<String>)publish.get("needs")).containsExactlyInAnyOrder("verify", "build");
        var manifest = map(jobs.get("publish-manifest"));
        assertThat((List<String>)manifest.get("needs")).containsExactlyInAnyOrder("verify", "publish-images");
        for (var job : List.of(publish, manifest)) {
            assertThat(job.get("if")).isEqualTo("needs.verify.outputs.publish == 'true'");
            assertThat(map(job.get("permissions"))).containsOnly(entry("contents", "read"), entry("packages", "write"));
            assertThat(steps(job).toString()).contains("secrets.GITHUB_TOKEN").doesNotContain("actions/checkout", "build-push-action", "MODEL_API_KEY", "WECHAT");
        }
        assertThat(steps(publish).toString()).contains("docker load", "sha256sum -c", "docker push");
        assertThat(steps(manifest).toString()).contains("for arch in amd64 arm64", "imagetools create", "manifest.json");
        assertThat(steps(manifest).toString()).contains("$IMAGE:sha-$GITHUB_SHA", "$IMAGE:latest",
                "$IMAGE:$GITHUB_REF_NAME", "External-service and NAS runtime acceptance are separate");
        for (String id : List.of("verify", "build", "publish-images", "publish-manifest")) {
            var job = map(jobs.get(id));
            assertThat(job).doesNotContainKey("continue-on-error");
            for (var step : steps(job)) {
                assertThat(step).doesNotContainKey("continue-on-error");
                // Only evidence uploads may run after failure, never builds, checks or publication.
                if (step.containsKey("if") && step.get("if").toString().contains("always()")) {
                    assertThat(step.get("uses").toString()).startsWith("actions/upload-artifact@");
                }
            }
        }
    }
    @Test void offlineSmokeUsesExplicitDiagnosticsAndCannotClaimRuntimeAcceptance() throws Exception {
        String smoke = Files.readString(Path.of("scripts/ci/image-smoke.sh"));
        assertThat(smoke).contains("--network none", "--read-only", "--mount", "--entrypoint java",
                        "ManagedContainerProbe", "-Dloader.path=/diagnostics.jar", "--artifact /app/assistant.jar",
                        "--materials", "--legacy", "--user", "target=/app/materials,volume-nocopy",
                        "offlineArtifactAndMaterials=PASS", "externalServices=NOT_RUN")
                .doesNotContain("MODEL_API_KEY", "WECHAT_BOT_ID", "config/application.yml", "ContainerStorageProbe", "\r");
        var buildSteps = steps(jobs().get("build"));
        var export = buildSteps.stream().filter(step -> step.getOrDefault("run", "").toString()
                .contains("docker save")).findFirst().orElseThrow();
        assertThat(export.get("if")).isEqualTo("needs.verify.outputs.publish == 'true'");
        assertThat(buildSteps.toString()).contains("diagnostics/talkweave-0.1.0-SNAPSHOT-diagnostics.jar")
                .doesNotContain("Require external-service runtime acceptance before publication", "Publication blocked");
    }
    @Test void composeUsesOnlyExternalServicesAndPrivateMaterials() throws Exception {
        var services = map(yaml("compose.yml").get("services"));
        assertThat(services).containsOnlyKeys("assistant");
        var service = map(services.get("assistant"));
        assertThat(service).doesNotContainKeys("build", "stop_grace_period", "command", "privileged");
        assertThat(service).containsEntry("image", "ghcr.io/wochen5770/talkweave:latest");
        assertThat(service).containsEntry("read_only", true).containsEntry("user", "${ASSISTANT_UID:-0}:${ASSISTANT_GID:-0}");
        assertThat((List<String>) service.get("ports")).containsExactly("${ADMIN_BIND_ADDRESS:-127.0.0.1}:${ADMIN_PORT:-8680}:8680");
        var volumes = (List<Map<String, Object>>) service.get("volumes");
        assertThat(volumes).hasSize(2);
        for (var volume : volumes) {
            assertThat(volume).containsEntry("type", "bind");
            assertThat(map(volume.get("bind"))).containsEntry("create_host_path", false);
        }
        assertThat(volumes.get(0)).containsEntry("source", "${ASSISTANT_HOST_MATERIALS:-./materials}")
                .containsEntry("target", "/app/materials");
        assertThat(volumes.get(1)).containsEntry("source", "${EXTERNAL_SERVICES_CONFIG:-./config/external-services.local.yml}")
                .containsEntry("target", "/app/config/external-services.yml").containsEntry("read_only", true);
        assertThat(service.get("volumes").toString()).doesNotContain("data-multi-user", "application.yml", "docker.sock");
        assertThat(map(service.get("environment")))
                .containsEntry("MANAGED_MATERIALS_DIRECTORY", "/app/materials")
                .containsEntry("EXTERNAL_SERVICES_CONFIG", "/app/config/external-services.yml")
                .doesNotContainKey("MANAGED_DATA_DIR");
    }
    @Test void productionImageKeepsSecretsAndDiagnosticsOutside() throws Exception {
        String ignore = Files.readString(Path.of(".dockerignore"));
        assertThat(ignore).contains("**\n", "!src/**", "!.github/workflows/container.yml")
                .doesNotContain("!config", "!data", "!.env", "!.build-cache");
        String docker = Files.readString(Path.of("Dockerfile"));
        assertThat(docker).contains("COPY --from=build", "FROM ${RUNTIME_IMAGE}");
        assertThat(docker).contains("EXTERNAL_SERVICES_CONFIG=/app/config/external-services.yml", "ADMIN_ADDRESS=0.0.0.0");
        assertThat(docker).contains("EXPOSE 8680");
        assertThat(map(yaml("src/main/resources/application-managed.yml").get("server")))
                .containsEntry("port", "${ADMIN_PORT:8680}");
        assertThat(docker.substring(docker.indexOf("FROM ${RUNTIME_IMAGE}")))
                .doesNotContain("COPY src", "COPY config", "COPY data", "diagnostics.jar", "sqlite");
    }
    @Test void imageDefaultsToRootAndSmokeRetainsNonrootOverrideCoverage() throws Exception {
        String docker = Files.readString(Path.of("Dockerfile"));
        String runtime = docker.substring(docker.indexOf("FROM ${RUNTIME_IMAGE}"));
        assertThat(runtime.lines().filter(line -> line.startsWith("USER ")).toList()).containsExactly("USER 0:0");
        assertThat(runtime).contains("useradd --uid 10001 --gid 10001", "chown 0:0 /app/materials", "chmod 700 /app/materials")
                .doesNotContain("MANAGED_MYSQL_SSLMODE=", "MANAGED_REDIS_TLS=");
        String smoke = Files.readString(Path.of("scripts/ci/image-smoke.sh"));
        assertThat(smoke).contains("test \"$(id -u):$(id -g)\" = \"0:0\"",
                "--user 10001:10001", "test \"$(id -u):$(id -g)\" = \"10001:10001\"",
                "identity-nonroot.txt", "for uid in 0 10001", "if [[ \"$uid\" != 0 ]]; then mounted+=(--user \"$uid:$uid\"); fi");
    }
    @Test void externalIntegrationIsExplicitProtectedAndSeparateFromPublication() throws Exception {
        var external=map(jobs().get("external-integration"));
        for (String id : List.of("verify", "build", "publish-images", "publish-manifest")) {
            assertThat(map(jobs().get(id)).getOrDefault("needs", List.of()).toString())
                    .doesNotContain("external-integration");
        }
        assertThat(external.get("if").toString()).contains("workflow_dispatch","inputs.external_integration","github.event.repository.default_branch");
        assertThat(external.get("environment")).isEqualTo("external-integration");
        assertThat(steps(external).toString()).contains("secrets.EXTERNAL_SERVICES_YAML","APPROVED_MYSQL_SCHEMA","EXPECTED_REDIS_VERSION")
                .doesNotContain("external-services.local.yml","docker run","packages=write");
        String script=Files.readString(Path.of("scripts/ci/external-services.sh"));
        assertThat(script).contains("github-hosted","umask 077","trap cleanup EXIT","ExternalNetworkTargets",
                "iptables -I OUTPUT 1","ip6tables -I OUTPUT 1","-j REJECT","mvn -o", "externalServices=PASS")
                .doesNotContain("FLUSHALL","FLUSHDB","TRUNCATE","docker run");
    }
}

package io.github.personalassistant.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    }
    @Test void smokeCannotCallExternalServicesAndNasOnlyPullsTheImage() throws Exception {
        String smoke = Files.readString(Path.of("scripts/ci/image-smoke.sh"));
        assertThat(smoke).contains("--network none", "--read-only", "--write --verify", "--mount", "--entrypoint java", "ContainerStorageProbe")
                .doesNotContain("MODEL_API_KEY", "WECHAT_BOT_ID", "config/application.yml", "\r");
        var service = map(map(yaml("compose.yml").get("services")).get("assistant"));
        assertThat(service).doesNotContainKeys("ports", "build");
        assertThat(service.get("image").toString()).contains("ASSISTANT_IMAGE:?");
        assertThat(service).containsEntry("read_only", true).containsEntry("stop_grace_period", "25s");
        assertThat(service.get("command").toString()).contains("--assistant.shutdown-grace=20s");
        String ignore = Files.readString(Path.of(".dockerignore"));
        assertThat(ignore).contains("**\n", "!src/**", "!.github/workflows/container.yml")
                .doesNotContain("!config", "!data", "!.env", "!.build-cache");
        String docker = Files.readString(Path.of("Dockerfile"));
        assertThat(docker).contains("USER 10001:10001", "COPY --from=build", "FROM ${RUNTIME_IMAGE}");
        assertThat(docker.substring(docker.indexOf("FROM ${RUNTIME_IMAGE}"))).doesNotContain("COPY src", "COPY config", "COPY data");
    }
}

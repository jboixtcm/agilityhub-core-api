package com.agilityhub.core.configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

/** E101: each main push keeps its run; only superseded pull requests may cancel. */
class CiWorkflowTest {
    @Test void T_12_17_e8t11_round2_point5_mainRunsAreIndependentAndHaveTimeToFinish() throws Exception {
        Map<String, Object> workflow = new Yaml().load(Files.readString(Path.of(".github/workflows/ci.yml")));
        var concurrency = (Map<?, ?>) workflow.get("concurrency");
        assertThat(concurrency.get("cancel-in-progress")).isEqualTo("${{ github.event_name == 'pull_request' }}");
        // A shared main group also cancels all but one *pending* push, even with cancel-in-progress=false.
        assertThat(concurrency.get("group")).isEqualTo("ci-${{ github.workflow }}-${{ github.event_name == 'pull_request' && github.ref || github.run_id }}");
        var tests = (Map<?, ?>) ((Map<?, ?>) workflow.get("jobs")).get("tests");
        assertThat(((Number) tests.get("timeout-minutes")).intValue()).isGreaterThanOrEqualTo(60);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void T_12_17_e8t11_round2_point5_oldRunsPublishOnlyTheirOwnImage(boolean current, @TempDir Path commands) throws Exception {
        Map<String, Object> workflow = new Yaml().load(Files.readString(Path.of(".github/workflows/ci.yml")));
        var publish = (Map<?, ?>) ((Map<?, ?>) workflow.get("jobs")).get("publish-image");
        var steps = (List<Map<String, Object>>) publish.get("steps");
        String script = (String) steps.stream().filter(step -> "Publish only the two scanned image digests".equals(step.get("name")))
                .findFirst().orElseThrow().get("run");
        Path calls = commands.resolve("calls");
        Path docker = commands.resolve("docker"), gh = commands.resolve("gh");
        Files.writeString(docker, "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$CALLS\"\n");
        Files.writeString(gh, "#!/bin/bash\nprintf '%s\\n' \"$LATEST\"\n");
        assertThat(docker.toFile().setExecutable(true)).isTrue();
        assertThat(gh.toFile().setExecutable(true)).isTrue();
        var process = new ProcessBuilder("/bin/bash", "-e", "-c", script);
        var env = process.environment();
        env.put("PATH", commands + ":" + env.get("PATH")); env.put("CALLS", calls.toString());
        env.put("GITHUB_SHA", "fixturecurrent"); env.put("GITHUB_REPOSITORY", "example/core");
        env.put("LATEST", current ? "fixturecurrent" : "fixturenewer");
        assertThat(process.start().waitFor()).isZero();
        var sent = Files.readAllLines(calls);
        assertThat(sent).hasSize(current ? 2 : 1);
        assertThat(sent.getFirst()).contains(":sha-fixture", ":ci-fixturecurrent-amd64", ":ci-fixturecurrent-arm64").doesNotContain(":main");
        if (current) { assertThat(sent.getLast()).contains("--tag ghcr.io/jboixtcm/agilityhub-core-api:main"); }
    }
}

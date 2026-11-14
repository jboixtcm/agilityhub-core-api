package com.agilityhub.core.configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CFG-12 (owner decision of 13 Nov 2026): the image starts on the `prod` profile when no profile is given, so a bare run of it
 * (a manual `docker run`, a new environment) never gets the `local` profile's test clock and DNS routes, provider fakes or IBANs
 * in clear: without the production configuration it refuses to start instead. The jar's own default stays `local`
 * (`application.yml`, ruling E139) for host and IDE runs, and every Compose stack that runs the image names its profile.
 */
class DockerfileProfileTest {
    static final String PROFILE = "SPRING_PROFILES_ACTIVE";

    @Test void CFG_12_theImageStartsOnTheProdProfileWhenNoneIsGiven() throws Exception {
        var lines = Files.readAllLines(Path.of("Dockerfile"));
        int runtime = -1;
        for (int i = 0; i < lines.size(); i++) { if (lines.get(i).startsWith("FROM ")) { runtime = i; } }
        var build = lines.subList(0, runtime);
        var stage = lines.subList(runtime, lines.size());
        assertThat(stage.get(0)).as("the runtime stage").startsWith("FROM eclipse-temurin:21-jre");
        assertThat(stage.stream().filter(line -> line.contains(PROFILE)).toList()).containsExactly("ENV " + PROFILE + "=prod");
        assertThat(stage.indexOf("ENV " + PROFILE + "=prod")).as("set before the entry point")
                .isLessThan(stage.indexOf(stage.stream().filter(line -> line.startsWith("ENTRYPOINT ")).findFirst().orElseThrow()));
        assertThat(build).as("the build stage runs no profile").noneMatch(line -> line.contains(PROFILE));
        // A run of the jar itself (host, IDE, bin/core) keeps the local default.
        assertThat(Files.readString(Path.of("src/main/resources/application.yml"))).contains("  profiles:\n    default: local\n");
    }

    @Test void CFG_12_everyStackThatRunsTheImageNamesItsProfile() throws Exception {
        var expected = new LinkedHashMap<String, Map<String, String>>();
        expected.put("compose.yaml", Map.of("api", "local"));
        expected.put("docker-compose.consumer.yml", Map.of("seed", "local", "core", "local"));
        expected.put("deploy/compose.prod.yaml", Map.of("core", "prod"));
        expected.put("deploy/compose.prod.local.yaml", Map.of("core", "prod", "seed", "local"));
        for (var file : expected.entrySet()) {
            // Compose's own merge tags (`!override`, `!reset`) are not YAML types: read as plain values.
            Map<String, Object> compose = new Yaml().load(Files.readString(Path.of(file.getKey())).replaceAll("!(override|reset)\\b", ""));
            @SuppressWarnings("unchecked") var services = (Map<String, Map<String, Object>>) compose.get("services");
            var profiles = new LinkedHashMap<String, String>();
            services.forEach((name, service) -> {
                // The image of this repository: published, or built from its root Dockerfile (`build: .`; the backup has its own).
                boolean core = String.valueOf(service.get("image")).contains("agilityhub-core-api") || ".".equals(service.get("build"))
                        || file.getKey().endsWith("compose.prod.local.yaml") && List.of("core", "seed").contains(name);
                if (!core) { return; }
                @SuppressWarnings("unchecked") var environment = (Map<String, Object>) service.get("environment");
                profiles.put(name, environment == null ? null : (String) environment.get(PROFILE));
            });
            assertThat(profiles).as(file.getKey()).isEqualTo(file.getValue());
        }
    }
}

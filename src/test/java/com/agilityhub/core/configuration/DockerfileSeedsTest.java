package com.agilityhub.core.configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** INC-40 (E11-T10): the image ships every seed under its own name, so `/app/seeds/club-canic.yaml` carries the club's real hosts. */
class DockerfileSeedsTest {
    private static final Pattern SEED_COPY = Pattern.compile("^COPY\\b.*?\\s(seeds/\\S+)\\s+(seeds/\\S+)\\s*$");

    /** INC-40 (E11-T10): no `COPY` line of the Dockerfile installs a seed under another file's name. */
    @Test void INC_40_theImageCopiesEachSeedUnderItsOwnName() throws Exception {
        var copies = Files.readAllLines(Path.of("Dockerfile")).stream().map(SEED_COPY::matcher).filter(java.util.regex.Matcher::matches).toList();
        assertThat(copies).as("seed COPY lines").hasSizeGreaterThanOrEqualTo(2);
        copies.forEach(copy -> assertThat(copy.group(2)).as(copy.group()).isEqualTo(copy.group(1)));
        assertThat(copies).extracting(copy -> copy.group(1)).contains("seeds/club-canic.yaml", "seeds/club-canic-consumer.yaml");
    }

    /**
     * INC-40 (E11-T10): the local consumer stacks and the image smokes apply the consumer variant (fictional hosts, which
     * deploy/local/hosts.js maps to localhost), never the canonical seed with the club's real domains.
     */
    @Test void INC_40_theConsumerStacksApplyTheConsumerSeed() throws Exception {
        for (String file : java.util.List.of("docker-compose.consumer.yml", "deploy/compose.prod.local.yaml")) {
            String compose = Files.readString(Path.of(file));
            assertThat(compose).as(file).contains("--core.command=club:apply ").contains("seeds/club-canic-consumer.yaml")
                    .doesNotContain("seeds/club-canic.yaml");
        }
        assertThat(Files.readString(Path.of("deploy/local/hosts.js"))).contains("'app.example.test'", "'admin.example.test'");
        var canonical = Pattern.compile("\"club:apply\",\\s*\"seeds/club-canic\\.yaml\"");
        try (var scripts = Files.list(Path.of("bin"))) {
            for (Path script : scripts.filter(Files::isRegularFile).toList()) {
                assertThat(canonical.matcher(Files.readString(script)).find()).as(script + " applies the canonical seed in the image").isFalse();
            }
        }
    }
}

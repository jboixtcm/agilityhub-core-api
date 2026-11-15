package com.agilityhub.core.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** REL-09: the integration tests' Mongo is production's, by digest; a production upgrade must move the tests with it. */
class MongoImagePinTest {
    private static final Pattern MONGO = Pattern.compile("^\\s+image:\\s*mongo:([^@\\s]+)@(sha256:[0-9a-f]{64})\\s*$");

    @Test void REL_09_theIntegrationTestsRunOnProductionsMongoImage() throws Exception {
        var pinned = Files.readAllLines(Path.of("deploy/compose.prod.yaml")).stream()
                .map(MONGO::matcher).filter(java.util.regex.Matcher::matches).toList();
        assertThat(pinned).as("deploy/compose.prod.yaml pins one mongo:<tag>@sha256 image").hasSize(1);
        assertThat(AbstractIntegrationTest.MONGO_IMAGE).isEqualTo("mongo@" + pinned.getFirst().group(2));
    }
}

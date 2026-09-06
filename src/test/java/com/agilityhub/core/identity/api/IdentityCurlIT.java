package com.agilityhub.core.identity.api;

import com.agilityhub.core.clubs.messaging.application.FakeEmailSender;
import com.agilityhub.core.identity.application.MagicLinkService;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP smoke evidence with a test-only mailbox; no credential-bearing diagnostic output. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shared.scheduling.enabled=false", "management.server.port=0"})
class IdentityCurlIT extends IdentityIntegrationSupport {
    @LocalServerPort int port;
    private final StringBuilder evidence = new StringBuilder();
    private final List<String> secrets = new ArrayList<>();

    @Test void T_01_08_T_01_04_curlMagicMailboxGrantMeRotationAndFamilyRevocation() throws Exception {
        try {
            var requested = curl("POST", "/api/v1/auth/magic-link", "application/json",
                    "{\"email\":\"admin@example.test\",\"purpose\":\"LOGIN\",\"client_id\":\"clubs-app\"}", null);
            assertThat(requested.status()).isEqualTo(202);
            awaitMail();
            var email = ((FakeEmailSender) emailSender).lastTo("admin@example.test");
            var matcher = Pattern.compile("[?]t=([A-Za-z0-9_-]{43})").matcher(email.text());
            assertThat(matcher.find()).isTrue();
            String magic = matcher.group(1);
            secrets.add(magic);
            evidence.append("Test mailbox: N-25 to admin@example.test, https://").append(HOST)
                    .append("/activacio?t=").append(redact(magic)).append('\n');
            var granted = curl("POST", "/oauth2/token", "application/x-www-form-urlencoded",
                    "grant_type=" + MagicLinkService.GRANT + "&client_id=clubs-app&token=" + magic, null);
            assertThat(granted.status()).isEqualTo(200);
            String access = granted.json().path("access_token").asText();
            String first = granted.json().path("refresh_token").asText();
            var me = curl("GET", "/api/v1/me", null, null, access);
            assertThat(me.status()).isEqualTo(200);
            assertThat(me.json().at("/account/emailVerifiedAt").asText()).isEqualTo(clock.instant().toString());
            assertThat(me.json().at("/membership/clubId").asText()).isEqualTo("club-a");
            var rotated = curl("POST", "/oauth2/token", "application/x-www-form-urlencoded",
                    "grant_type=refresh_token&client_id=clubs-app&refresh_token=" + first, null);
            assertThat(rotated.status()).isEqualTo(200);
            String next = rotated.json().path("refresh_token").asText();
            assertThat(first.equals(next)).isFalse();
            var reused = curl("POST", "/oauth2/token", "application/x-www-form-urlencoded",
                    "grant_type=refresh_token&client_id=clubs-app&refresh_token=" + first, null);
            // The closed catalog assigns 400 to REFRESH_REUSED (the task's curl line says 401).
            assertThat(reused.status()).isEqualTo(400);
            assertThat(reused.json().path("code").asText()).isEqualTo("REFRESH_REUSED");
            var revoked = curl("POST", "/oauth2/token", "application/x-www-form-urlencoded",
                    "grant_type=refresh_token&client_id=clubs-app&refresh_token=" + next, null);
            assertThat(revoked.status()).isEqualTo(400);
            assertThat(revoked.json().path("code").asText()).isEqualTo("REFRESH_EXPIRED");
            assertThat(mongo.findAll(RefreshToken.class)).hasSize(2).allMatch(t -> t.status() == RefreshToken.Status.REVOKED);
            var security = mongo.findAll(SecurityEvent.class).stream().map(event -> event.type().name()).toList();
            assertThat(security).contains("REFRESH_TOKEN_REUSED");
            evidence.append("Database: both refresh records REVOKED.\nSecurity events: ").append(security).append('\n');
        } finally {
            Files.writeString(Path.of("target/E1-T02-curl-evidence.txt"), redact(evidence.toString()));
        }
    }

    private Response curl(String method, String path, String contentType, String body, String access) throws Exception {
        var command = new ArrayList<>(List.of("curl", "--silent", "--show-error", "--include", "--max-time", "10",
                "--request", method, "--header", "Host: " + HOST));
        if (contentType != null) { command.addAll(List.of("--header", "Content-Type: " + contentType)); }
        if (access != null) { command.addAll(List.of("--header", "Authorization: Bearer " + access)); }
        if (body != null) { command.addAll(List.of("--data-raw", body)); }
        command.add("http://127.0.0.1:" + port + path);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertThat(process.exitValue()).isZero();
            int split = output.indexOf("\n\n");
            JsonNode json = output.substring(split + 2).isBlank() ? mapper.createObjectNode() : mapper.readTree(output.substring(split + 2));
            for (String field : List.of("access_token", "refresh_token", "id_token")) {
                if (json.hasNonNull(field)) { secrets.add(json.path(field).asText()); }
            }
            evidence.append("$ ").append(redact(command.stream().map(value -> "'" + value.replace("'", "'\\''") + "'")
                    .collect(java.util.stream.Collectors.joining(" ")))).append('\n').append(redact(output)).append("\n\n");
            return new Response(Integer.parseInt(output.split(" ", 3)[1]), json);
        } finally { if (process.isAlive()) { process.destroyForcibly(); } }
    }

    private String redact(String value) {
        for (String secret : secrets) { value = value.replace(secret, secret.substring(0, 3) + "…[truncated]"); }
        return value;
    }
    private record Response(int status, JsonNode json) { }
}

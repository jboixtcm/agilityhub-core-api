package com.agilityhub.core.configuration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * PRIV-01 (owner's decision of 13-11): the platform policy every account accepts at its first sign-in (S01 §14, the `PLATFORM`
 * consent) is the page `apps/id` serves at `/privacy` from its versioned content file. Every default of the server — the
 * application, the production compose file and both env examples — carries that file's version (the «Versió» of
 * `docs/legal/POLITICA_PRIVACITAT_AGILITYHUB_ID.md`, the same text) and that route on the identity host.
 */
class LegalPolicyDefaultsTest {
    static final String URL = "https://id.agilitydoghub.com/privacy";

    static String read(String path) throws Exception { return Files.readString(Path.of(path)); }

    /** The policy's version, from its header «**Versió:** N ·». */
    static String documentVersion() throws Exception {
        var header = Pattern.compile("^\\*\\*Versió:\\*\\* (\\S+) ·", Pattern.MULTILINE).matcher(read("docs/legal/POLITICA_PRIVACITAT_AGILITYHUB_ID.md"));
        assertThat(header.find()).as("the policy's «Versió» header").isTrue();
        return header.group(1);
    }

    @Test void PRIV_01_theServerDefaultsAreTheVersionAndTheRouteOfTheServedPolicy() throws Exception {
        String version = documentVersion();
        assertThat(version).isEqualTo("2");
        assertThat(read("src/main/resources/application.yml"))
                .contains("privacyPolicyVersion: ${PLATFORM_PRIVACY_POLICY_VERSION:" + version + "}")
                .contains("privacyPolicyUrl: ${PLATFORM_PRIVACY_POLICY_URL:" + URL + "}")
                .contains("issuer: ${AUTH_ISSUER:https://id.agilitydoghub.com}");
        assertThat(read("deploy/compose.prod.yaml"))
                .contains("PLATFORM_PRIVACY_POLICY_VERSION: ${PLATFORM_PRIVACY_POLICY_VERSION:-" + version + "}")
                .contains("PLATFORM_PRIVACY_POLICY_URL: ${PLATFORM_PRIVACY_POLICY_URL:-" + URL + "}")
                .contains("ID_HOST: ${ID_HOST:-id.agilitydoghub.com}");
        for (String example : new String[] {"deploy/.env.prod.example", ".env.example"}) {
            assertThat(read(example)).as(example).contains("PLATFORM_PRIVACY_POLICY_VERSION=" + version + "\n", "PLATFORM_PRIVACY_POLICY_URL=" + URL + "\n");
        }
        // The route is `/privacy` on the host the identity app is served from (Caddy's ID_HOST, the issuer's host).
        assertThat(URI.create(URL).getPath()).isEqualTo("/privacy");
        assertThat(URI.create(URL).getHost()).isEqualTo(URI.create("https://id.agilitydoghub.com").getHost());
        assertThat(read("deploy/Caddyfile")).contains("{$ID_HOST} {\n    import spa /srv/id");
    }

    @Test void PRIV_01_theLegalCopyIsTheDefinitiveTextWithTheControllerAndProcessorsTheOwnerGave() throws Exception {
        String policy = read("docs/legal/POLITICA_PRIVACITAT_AGILITYHUB_ID.md");
        assertThat(policy).contains("## Català", "## Castellano", "## English")
                .contains("Club Agility Cànic", "G63189617", "Carrer Sant Pere, 10, 08392 Sant Andreu de Llavaneres", "info@agilitycanic.cat")
                .contains("DigitalOcean", "Brevo", "Stripe")
                .contains("No l'ha redactat un advocat");
        // No draft markers, and none of the processors or flows the deployed system does not have.
        assertThat(policy).doesNotContain("[RAÓ", "[RAO", "[NIF", "[DD-MM-AAAA]", "esborrany", "Twilio", "SendGrid", "AWS", "Sentry", "Learn");
    }
}

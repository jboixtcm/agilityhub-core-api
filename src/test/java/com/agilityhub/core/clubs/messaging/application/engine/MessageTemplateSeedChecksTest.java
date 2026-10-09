package com.agilityhub.core.clubs.messaging.application.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T03 step 3: the seed files are checked against the catalog when the application starts, so a broken copy never seeds a
 * club: a missing file or code, a code twice, an extra code, another icon, colour or matrix than the catalog's, an SMS text on
 * a code that sends none or in some languages only, an empty title — each stops the start with its reason. And a club keeps
 * its own languages of the seed (R-11-01).
 */
class MessageTemplateSeedChecksTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, JsonNode> files() throws Exception {
        var files = new HashMap<String, JsonNode>();
        for (String locale : MessageTemplateSeed.LOCALES) {
            files.put(locale, mapper.readTree(Files.readString(Path.of("src/main/resources/seed/message-templates." + locale + ".json"))));
        }
        return files;
    }
    private MessageTemplateSeed load(Map<String, JsonNode> files) {
        return MessageTemplateSeed.load(mapper, path -> {
            String locale = path.substring(path.lastIndexOf('.', path.length() - 6) + 1, path.length() - 5);
            JsonNode file = files.get(locale);
            return file == null ? null : (InputStream) new ByteArrayInputStream(file.toString().getBytes(StandardCharsets.UTF_8));
        });
    }
    private ObjectNode template(Map<String, JsonNode> files, String locale, String code) {
        for (var template : files.get(locale).path("templates")) { if (template.path("code").asText().equals(code)) { return (ObjectNode) template; } }
        throw new AssertionError(code);
    }
    private void fails(Consumer<Map<String, JsonNode>> breaking, String reason) throws Exception {
        var files = files();
        breaking.accept(files);
        assertThatThrownBy(() -> load(files)).as(reason).isInstanceOf(IllegalStateException.class).hasMessageContaining(reason);
    }

    @Test void E7_T03_theSeedFilesAsCommittedLoad() throws Exception { assertThat(load(files()).size()).isEqualTo(52); }

    @Test void E7_T03_aBrokenSeedFileStopsTheStartWithItsReason() throws Exception {
        fails(files -> files.remove("en"), "Missing /seed/message-templates.en.json");
        fails(files -> ((ObjectNode) files.get("es")).put("locale", "ca"), "declares ca");
        fails(files -> ((ArrayNode) files.get("ca").path("templates")).add(files.get("ca").path("templates").get(0).deepCopy()), "Duplicated N-01 in ca");
        fails(files -> files.values().forEach(file -> ((ArrayNode) file.path("templates")).remove(3)), "must hold exactly");
        fails(files -> ((ArrayNode) files.get("ca").path("templates")).remove(3), "N-04 has no ca seed");
        fails(files -> ((ArrayNode) files.get("ca").path("templates")).addObject().put("code", "N-25"), "must hold exactly");
        fails(files -> ((ArrayNode) files.get("es").path("templates")).remove(0), "N-01 has no es seed");
        fails(files -> template(files, "ca", "N-04").put("icon", "bell"), "N-04 ca: icon/colour differ from the catalog");
        fails(files -> template(files, "en", "N-04").put("color", "ERROR"), "N-04 en: icon/colour differ from the catalog");
        fails(files -> template(files, "ca", "N-04").putObject("matrix").putObject("MEMBER").put("APP", true).put("EMAIL", true).put("SMS", false),
                "N-04 ca: the matrix differs from the catalog");
        fails(files -> template(files, "ca", "N-04").put("smsBody", "[[club_name]]"), "N-04 cannot send SMS");
        fails(files -> template(files, "es", "N-08a").remove("smsBody"), "N-08a: smsBody in some locales only");
        fails(files -> template(files, "ca", "N-09").put("title", " "), "N-09 ca: title and body are required");
        fails(files -> template(files, "ca", "N-09").remove("body"), "N-09 ca: title and body are required");
    }

    @Test void R_11_01_aClubStoresItsOwnLanguagesOfTheSeed() {
        var n08a = MessageTemplateSeed.load().of("N-08a").orElseThrow();
        assertThat(n08a.title(List.of("es", "ca"), "es").values()).containsOnlyKeys("ca", "es");
        assertThat(n08a.title(List.of("es", "ca"), "es").defaultLocale()).isEqualTo("es");
        // A club of another language only (no product seed in it) keeps every language, the first one as default.
        var fr = n08a.body(List.of("fr"), "fr");
        assertThat(fr.values()).containsOnlyKeys("ca", "es", "en"); assertThat(fr.defaultLocale()).isEqualTo("ca");
        assertThat(n08a.smsBody(null, "ca").values()).containsOnlyKeys("ca", "es", "en");
        assertThat(MessageTemplateSeed.load().of("N-04").orElseThrow().smsBody(List.of("ca"), "ca")).isNull();
        assertThat(MessageTemplateSeed.load().of("N-25")).isEmpty();
    }
}

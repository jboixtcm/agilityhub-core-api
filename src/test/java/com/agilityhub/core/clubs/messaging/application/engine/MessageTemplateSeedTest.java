package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T03 step 3 (S11 §8, R-11-01): `seed/message-templates.{ca,es,en}.json` holds one template per `NotificationCatalog` code of
 * stage R1 whose category is not SYSTEM, in the three locales, each with the catalog's icon, colour and default matrix, an SMS
 * text only where the code can send one, and `[[var]]` names inside the code's variables; the `ca` texts of S11 §8 are
 * verbatim (N-15's FIFO line, N-13's training line and N-16's S15 branches are the readings the report explains); every
 * seeded text passes the D9 validation it would meet when saved unchanged; no Cànic literal.
 */
class MessageTemplateSeedTest {
    private final MessageTemplateSeed seed = MessageTemplateSeed.load();

    @Test void E7_T03_oneSeededTemplatePerEligibleCatalogCodeInTheThreeLocales() throws Exception {
        var eligible = NotificationCatalog.specs().stream().filter(spec -> spec.stage() == NotificationSpec.Stage.R1 && spec.category() != NotificationCategory.SYSTEM)
                .map(NotificationSpec::code).toList();
        assertThat(seed.all()).extracting(MessageTemplateSeed.Seeded::code).containsExactlyElementsOf(eligible);
        assertThat(seed.size()).isEqualTo(51);
        var withoutTemplate = NotificationCatalog.codes().stream().filter(code -> !eligible.contains(code)).toList();
        assertThat(withoutTemplate).containsExactly("N-25", "N-26", "N-27", "N-39", "N-43", "N-44", "N-45", "N-48", "N-52", "N-53");
        var mapper = new ObjectMapper();
        for (String locale : List.of("ca", "es", "en")) {
            var file = mapper.readTree(Files.readString(Path.of("src/main/resources/seed/message-templates." + locale + ".json")));
            assertThat(file.path("locale").asText()).isEqualTo(locale);
            assertThat(file.path("templates")).hasSize(51);
        }
        System.out.println("E7-T03 seed: " + seed.size() + " templates per locale (ca, es, en); without a template: " + withoutTemplate);
    }

    @Test void E7_T03_iconColourMatrixAndSmsFollowTheCatalogAndTheVariablesAreTheCodes() {
        for (var seeded : seed.all()) {
            var spec = NotificationCatalog.byCode(seeded.code()).orElseThrow();
            assertThat(seeded.icon()).as(seeded.code()).isEqualTo(spec.icon());
            assertThat(seeded.color()).as(seeded.code()).isEqualTo(spec.color());
            assertThat(seeded.matrix()).as(seeded.code()).isEqualTo(spec.defaultMatrix());
            assertThat(seeded.title().keySet()).containsExactlyInAnyOrder("ca", "es", "en");
            assertThat(seeded.body().keySet()).containsExactlyInAnyOrder("ca", "es", "en");
            if (!seeded.smsBody().isEmpty()) {
                assertThat(MessageTemplateSeed.smsCapable(spec)).as(seeded.code() + " sends no SMS").isTrue();
                assertThat(seeded.smsBody().keySet()).containsExactlyInAnyOrder("ca", "es", "en");
            }
            var variables = new HashSet<>(NotificationCatalog.templateVariables(spec));
            for (var texts : List.of(seeded.title(), seeded.body(), seeded.smsBody())) {
                texts.forEach((locale, text) -> {
                    var names = TemplateValidator.syntax(seeded.code(), text);
                    assertThat(variables).as(seeded.code() + " " + locale + " [[var]]").containsAll(names.variables());
                    assertThat(text).as(seeded.code() + " " + locale).doesNotContainIgnoringCase("cànic").doesNotContainIgnoringCase("parella").doesNotContain("{FEMALE");
                });
            }
            // A required variable of the template is in every language (N-08a admin_text; N-02's link is its e-mail's only, E76).
            for (String required : NotificationCatalog.templateRequiredVariables(spec)) {
                seeded.body().forEach((locale, text) -> assertThat(seeded.title().get(locale) + text).as(seeded.code() + " " + locale).contains("[[" + required + "]]"));
            }
        }
        assertThat(seed.all().stream().filter(s -> !s.smsBody().isEmpty()).map(MessageTemplateSeed.Seeded::code))
                .containsExactlyInAnyOrder("N-08a", "N-08b", "N-15", "N-16", "N-32b", "N-32c", "N-32d", "N-36", "N-47");
    }

    /**
     * The ten rows of S11 §8's seed table are the seed's `ca` texts, word for word: the title cell, the first «…» of the body
     * cell and, after «SMS:», the SMS text. Round 2 (E76) amended the rows of N-02 (the APP copy without the link), N-13, N-15
     * and N-16 to the implemented copy, so the whole table is checked now, not a copy of it in this test.
     */
    @Test void E7_T03_theCatalanTextsOfS11Section8AreTheSeedVerbatim() throws Exception {
        var s11 = Files.readString(Path.of("docs/specs/S11-comunicacions.md"));
        var row = java.util.regex.Pattern.compile("^\\| (N-\\d+[a-z]?) \\| `[a-z]+` · `[A-Z]+` \\| (.*?) \\| (.*) \\|$", java.util.regex.Pattern.MULTILINE).matcher(s11);
        var checked = new java.util.ArrayList<String>();
        while (row.find()) {
            String code = row.group(1), body = row.group(3);
            var seeded = seed.of(code).orElseThrow();
            var quoted = quoted(body);
            if (code.equals("N-28")) {
                // E8-T05 adds planned/denied/cancelled variants; legacy events retain the approved S11 copy.
                assertThat(seeded.title().get("ca")).contains("other {" + row.group(2) + "}");
                assertThat(seeded.body().get("ca")).contains("other {" + quoted.getFirst() + "}");
            } else {
                assertThat(row.group(2)).as(code + " title").isEqualTo(seeded.title().get("ca"));
                assertThat(quoted.getFirst()).as(code + " body").isEqualTo(seeded.body().get("ca"));
            }
            if (body.contains("SMS: «")) { assertThat(quoted.get(1)).as(code + " SMS").isEqualTo(seeded.smsBody().get("ca")); }
            checked.add(code);
        }
        assertThat(checked).containsExactly("N-08a", "N-15", "N-16", "N-09", "N-19", "N-06", "N-04", "N-02", "N-28", "N-13");
        // E76: N-02's APP copy has no link (the welcome e-mail carries it); FIFO names the deadline; N-16 names the dog (S15).
        assertThat(seed.of("N-02").orElseThrow().body().values()).allSatisfy(text -> assertThat(text).doesNotContain("[[link]]"));
        assertThat(seed.of("N-15").orElseThrow().smsBody().get("ca")).contains("FIFO {Tens fins a les [[confirm_by]] per confirmar-la a l'app.}");
        assertThat(seed.of("N-16").orElseThrow().body().get("ca")).contains("[[dog_name]]", "{auto_cancel, select, true {");
    }
    /** The top-level «…» texts of a cell, in order (a text may hold its own «…», as N-08a's admin text). */
    static List<String> quoted(String cell) {
        var texts = new java.util.ArrayList<String>(); int depth = 0, start = -1;
        for (int i = 0; i < cell.length(); i++) {
            if (cell.charAt(i) == '«' && depth++ == 0) { start = i + 1; }
            else if (cell.charAt(i) == '»' && --depth == 0) { texts.add(cell.substring(start, i)); }
        }
        return texts;
    }

    /**
     * E7-T06 step 5 (claude review #3, AGENTS rule 2): a seed prints only its row's variables. Every ICU argument of every seed
     * text is either one of the code's template variables or the name of a `select`/`plural` argument (a selector such as
     * `has_upfront` or `mode`, which chooses a branch and prints nothing of its own). Before the fix N-08b's body printed
     * `{class_description}` and N-32c's SMS `{date}`, outside their CATALEG_NOTIFICACIONS rows. Round 2 (P1, ruling E81):
     * N-32c's row gained `date`, and its SMS prints it again as S07 §8 writes it, «([[date]])».
     */
    @Test void E7_T06_everyIcuArgumentOfTheSeedIsARowVariableOrASelector() {
        var outside = new java.util.TreeSet<String>();
        for (var seeded : seed.all()) {
            var variables = new HashSet<>(NotificationCatalog.templateVariables(NotificationCatalog.byCode(seeded.code()).orElseThrow()));
            for (var texts : List.of(seeded.title(), seeded.body(), seeded.smsBody())) {
                texts.forEach((locale, text) -> {
                    var names = TemplateValidator.syntax(seeded.code(), text);
                    names.icu().stream().filter(name -> !variables.contains(name) && (!names.selectors().contains(name) || names.printed().contains(name)))
                            .forEach(name -> outside.add(seeded.code() + " " + locale + " {" + name + "}"));
                });
            }
        }
        assertThat(outside).isEmpty();
        assertThat(seed.of("N-08b").orElseThrow().body().values()).allSatisfy(text -> assertThat(text).doesNotContain("class_description"));
        assertThat(NotificationCatalog.templateVariables(NotificationCatalog.byCode("N-32c").orElseThrow())).containsSubsequence("activity_title", "date", "admin_text");
        assertThat(seed.of("N-32c").orElseThrow().smsBody().values()).allSatisfy(text -> assertThat(text).contains("[[activity_title]] ([[date]])"));
    }

    @Test void E7_T03_everySeedPassesTheValidationOfAnUnchangedSave() throws Exception {
        var messages = new IcuMessageSource();
        var samples = new TemplateSampleData(messages);
        var validator = new TemplateValidator();
        // The longest club name of the fixtures' kind: the SMS fits 160 characters with it (the admin's own text left out).
        var club = new ClubConfig.ClubView("seed-club", "seed", "Club Agility Exemple del Maresme", List.of("ca", "es", "en"), "ca", "Europe/Madrid", "EUR", null, null,
                "ACTIVE", null);
        var config = new ClubConfig(club, Map.of(), Set.of(), null, Map.of());
        for (var seeded : seed.all()) {
            var spec = NotificationCatalog.byCode(seeded.code()).orElseThrow();
            // The save's ICU names besides the variables: the seed's selectors only (E7-T06, `MessageTemplateService.seedArguments`).
            var icu = new HashSet<String>();
            for (var texts : List.of(seeded.title(), seeded.body(), seeded.smsBody())) { texts.values().forEach(t -> icu.addAll(TemplateValidator.syntax(spec.code(), t).selectors())); }
            var caps = new java.util.EnumMap<com.agilityhub.core.clubs.messaging.domain.NotificationAudience, Set<com.agilityhub.core.clubs.messaging.domain.NotificationChannel>>(
                    com.agilityhub.core.clubs.messaging.domain.NotificationAudience.class);
            spec.audiences().stream().filter(a -> a.templated()).forEach(a -> caps.put(a, spec.caps(a)));
            var rules = new TemplateValidator.Rules(NotificationCatalog.templateVariables(spec), icu, NotificationCatalog.templateRequiredVariables(spec), caps,
                    MessageTemplateSeed.smsCapable(spec), spec.mandatory());
            assertThatCode(() -> validator.validate(rules, new TemplateValidator.Draft(seeded.title(), seeded.body(), seeded.smsBody(), seeded.matrix(), true),
                    List.of("ca", "es", "en"), "ca", locale -> samples.values(config, locale), true)).as(seeded.code()).doesNotThrowAnyException();
        }
        assertThat(Locale.forLanguageTag("ca")).isNotNull();
    }
}

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
            // A required variable is in every language (N-02 link, N-08a admin_text).
            for (String required : spec.requiredVariables()) {
                seeded.body().forEach((locale, text) -> assertThat(seeded.title().get(locale) + text).as(seeded.code() + " " + locale).contains("[[" + required + "]]"));
            }
        }
        assertThat(seed.all().stream().filter(s -> !s.smsBody().isEmpty()).map(MessageTemplateSeed.Seeded::code))
                .containsExactlyInAnyOrder("N-08a", "N-08b", "N-15", "N-16", "N-32b", "N-32c", "N-32d", "N-36", "N-47");
    }

    @Test void E7_T03_theCatalanTextsOfS11Section8AreVerbatim() {
        Map<String, List<String>> expected = Map.of(
                "N-08a", List.of("Classe anul·lada pel club", "[[class_date]] · [[class_time]] · [[class_description]], amb [[dog_name]]. «[[admin_text]]» — [[club_name]]. Aquesta sessió no compta al teu còmput.",
                        "[[club_name]]: la classe de [[class_date]] a les [[class_time]] ([[class_description]]) queda anul·lada. [[admin_text]]"),
                "N-09", List.of("[[dog_name_article]] puja de nivell!", "Per la vostra evolució, [[dog_name_article]] ja ha pujat a nivell [[level_name]]. Ja podeu reservar classes en aquest nou nivell; les classes que ja teníeu reservades, encara que no siguin d'aquest nivell, segueixen sent vàlides."),
                "N-19", List.of("T'hem trobat a faltar", "[[class_date]] no vas poder venir a la classe de [[class_description]]. Recorda que pots anul·lar des de l'app fins a última hora: així pot aprofitar la classe algú altre. La sessió compta dins el teu còmput."),
                "N-06", List.of("Reserva confirmada", "Entrenament lliure · [[date]] · [[time]] · [[ring_name]] · amb [[dog_name]]."),
                "N-04", List.of("Reserva confirmada", "Classe [[class_description]] · [[class_date]] · [[class_time]] · [[ring_name]] · amb [[dog_name]]."),
                "N-02", List.of("{gender, select, female {Benvinguda} other {Benvingut}} a [[club_name]], [[member_first_name]]!", "Ja tens accés a l'app del club. Entra-hi amb aquest enllaç: [[link]]."),
                "N-28", List.of("Comunicació de baixa com a associat", "Hola [[member_first_name]], et comuniquem que en data [[effective_date]] s'ha fet efectiva la teva baixa com a associat de [[club_name]]. T'agraïm el temps que hem compartit — les portes sempre seran obertes per a tu i per a [[dog_name]]. Fins aviat!"));
        expected.forEach((code, texts) -> {
            var seeded = seed.of(code).orElseThrow();
            assertThat(seeded.title().get("ca")).as(code).isEqualTo(texts.get(0));
            assertThat(seeded.body().get("ca")).as(code).isEqualTo(texts.get(1));
            if (texts.size() > 2) { assertThat(seeded.smsBody().get("ca")).as(code).isEqualTo(texts.get(2)); }
        });
        var n15 = seed.of("N-15").orElseThrow();
        assertThat(n15.title().get("ca")).isEqualTo("S'ha alliberat una plaça!");
        assertThat(n15.body().get("ca")).startsWith("Classe [[class_description]] · [[class_date]] · [[class_time]]. ")
                .contains("Estàs a la llista d'espera — la plaça és per a qui confirmi primer.", "Tens fins a les [[confirm_by]] per confirmar-la.");
        // §8's SMS is the ALL_AT_ONCE branch; in FIFO it names the deadline (the catalog's `mode` and `confirm_by`, S08 T-08-21).
        assertThat(n15.smsBody().get("ca")).startsWith("[[club_name]]: s'ha alliberat una plaça a la classe de [[class_date]] a les [[class_time]] ([[class_description]]). ")
                .contains("other {Entra a l'app per agafar-la.}", "FIFO {Tens fins a les [[confirm_by]] per confirmar-la a l'app.}");
        var n16 = seed.of("N-16").orElseThrow();
        assertThat(n16.title().get("ca")).isEqualTo("Possible anul·lació de classe");
        assertThat(n16.body().get("ca")).contains("Si ningú més no s'hi apunta abans de les [[review_time]] de [[review_day]], la classe es cancel·larà. Et proposem reservar-ne una altra.");
        var n13 = seed.of("N-13").orElseThrow();
        assertThat(n13.title().get("ca")).isEqualTo("{kind, select, TRAINING {Recordatori d'entrenament} other {Recordatori de classe}}");
        assertThat(n13.body().get("ca")).startsWith("[[date]] a les [[time]] · ").endsWith(" · [[ring_name]] · amb [[dog_name]].").contains("[[class_description]]");
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
            var icu = new HashSet<String>();
            for (var texts : List.of(seeded.title(), seeded.body(), seeded.smsBody())) { texts.values().forEach(t -> icu.addAll(TemplateValidator.syntax(spec.code(), t).icu())); }
            var caps = new java.util.EnumMap<com.agilityhub.core.clubs.messaging.domain.NotificationAudience, Set<com.agilityhub.core.clubs.messaging.domain.NotificationChannel>>(
                    com.agilityhub.core.clubs.messaging.domain.NotificationAudience.class);
            spec.audiences().stream().filter(a -> a.templated()).forEach(a -> caps.put(a, spec.caps(a)));
            var rules = new TemplateValidator.Rules(NotificationCatalog.templateVariables(spec), icu, spec.requiredVariables(), caps, MessageTemplateSeed.smsCapable(spec), spec.mandatory());
            assertThatCode(() -> validator.validate(rules, new TemplateValidator.Draft(seeded.title(), seeded.body(), seeded.smsBody(), seeded.matrix(), true),
                    List.of("ca", "es", "en"), "ca", locale -> samples.values(config, locale), true)).as(seeded.code()).doesNotThrowAnyException();
        }
        assertThat(Locale.forLanguageTag("ca")).isNotNull();
    }
}

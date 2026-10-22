package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.platform.application.ModuleDependencyValidator;
import com.agilityhub.core.shared.domain.ProvisionalText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T18 (A41.1, ruling E153; S17 R-17-08, R-17-13, T-17-14): the template club `club-template-default` carries a generic starter
 * set, in ca/es/en, that D19's «Catàlegs base» clones part by part — six levels, two rings, five plans with their current prices, three
 * FAQ categories with one R-05-21b placeholder each, and every templated notification code of the catalog. No text names a club.
 */
class TemplateClubSeedTest {
    static final Path SEED = Path.of("seeds/club-template-default.yaml");
    static final Path NOTIFICATIONS = Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md");
    static final Set<String> LOCALES = Set.of("ca", "es", "en");
    /** The Cànic's name, host, times and towns (its seed, S05 §12, S06): none may reach the template. */
    static final Pattern CLUB_LITERALS = Pattern.compile("canic|cànic|7:30|agilitycanic|cabrera|llavaneres", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    final ClubDefinitionCodec codec = new ClubDefinitionCodec(new ObjectMapper(), new ModuleDependencyValidator());
    TemplateClubSeedTest() throws Exception { }
    ObjectNode seed() { return codec.read(SEED); }
    static List<String> texts(JsonNode rows, String field, String locale) {
        var values = new ArrayList<String>(); rows.forEach(row -> values.add(row.path(field).path(locale).asText(null))); return values;
    }
    static List<String> strings(JsonNode array) { var values = new ArrayList<String>(); array.forEach(value -> values.add(value.asText())); return values; }

    /** The templated codes of CATALEG_NOTIFICACIONS.md: its rows except the SYSTEM ones (account e-mails) and the ones outside R1. */
    static List<String> templatedCodesOfTheDocument() throws Exception {
        var codes = new ArrayList<String>();
        for (String line : Files.readAllLines(NOTIFICATIONS)) {
            if (!line.startsWith("| N-")) { continue; }
            String[] cells = line.split("\\|", -1);
            String name = cells[2], category = cells[4].replace("**", "").strip();
            if (category.equals("SYSTEM") || name.contains("(R2)") || name.contains("fora de R1")) { continue; }
            codes.add(cells[1].strip());
        }
        return codes;
    }

    /**
     * R-17-06 and rulings E157/E161 (E11-T19 step 2, round 2): the template club's theme is the AgilityHub brand — the web's light
     * «AgilityHub» preset (`packages/ui/src/tokens.css`, pasted by the organizer), Montserrat, 8 px — and the very theme of the product
     * `/branding`, so the console's preset, the template and R-17-06's comparison never drift apart.
     */
    static final Map<String, String> BRAND_COLORS = Map.ofEntries(Map.entry("primary", "#1d1d1f"), Map.entry("onPrimary", "#ffffff"),
            Map.entry("background", "#ffffff"), Map.entry("surface", "#ffffff"), Map.entry("surfaceAlt", "#f1f1f2"), Map.entry("text", "#1d1d1f"),
            Map.entry("textMuted", "#656567"), Map.entry("border", "#d6d6d7"), Map.entry("success", "#4caf7d"), Map.entry("warning", "#e5b93c"),
            Map.entry("danger", "#e05b5b"), Map.entry("info", "#85b8e8"));
    @Test void R_17_06_E157_theTemplateThemeIsTheProductBrandingThemeAndTheBrand() throws Exception {
        var mapper = new ObjectMapper();
        var theme = seed().path("theme");
        assertThat(mapper.convertValue(theme.path("colors"), Map.class)).isEqualTo(BRAND_COLORS);
        assertThat(theme.path("fontFamily").asText()).isEqualTo("Montserrat");
        assertThat(theme.path("radius").asText()).isEqualTo("8px");
        assertThat(theme.path("mode").asText()).isEqualTo("light");
        com.agilityhub.core.platform.domain.ConsoleThemeRules.validate(mapper.treeToValue(theme, com.agilityhub.core.platform.domain.Theme.class));
        var product = mapper.readTree(Path.of("src/main/resources/platform/product-branding.json").toFile()).path("theme");
        for (String field : List.of("colors", "fontFamily", "radius", "ringPalette", "mode")) {
            assertThat(product.path(field)).as(field).isEqualTo(theme.path(field));
        }
    }

    @Test void T_17_14_E11_T18_theTemplateSeedValidatesAndCarriesEveryPartOfTheStarterSet() {
        var definition = seed(); codec.validate(definition);
        var catalogs = definition.path("catalogs");
        // The template's own catalogs need the modules of its plan types and prices (PlanService, PriceService).
        assertThat(strings(definition.path("modules"))).contains("BILLING", "PACKS", "SINGLE_CLASS");
        assertThat(catalogs.path("levels").findValuesAsText("code")).containsExactly("CAD", "INI", "INT", "AVA", "TER", "PENDENT");
        assertThat(texts(catalogs.path("levels"), "name", "ca")).containsExactly("Cadells", "Iniciació", "Intermedi", "Avançat", "Teràpia", "Pendent");
        assertThat(texts(catalogs.path("levels"), "name", "es")).containsExactly("Cachorros", "Iniciación", "Intermedio", "Avanzado", "Terapia", "Pendiente");
        assertThat(texts(catalogs.path("levels"), "name", "en")).containsExactly("Puppies", "Beginners", "Intermediate", "Advanced", "Therapy", "Pending");
        // E29/B32 as the Cànic's rows: Teràpia and Pendent stay outside the progression; the others form it.
        catalogs.path("levels").forEach(level -> assertThat(level.path("progression").asBoolean())
                .as(level.path("code").asText()).isEqualTo(!Set.of("TER", "PENDENT").contains(level.path("code").asText())));
        assertThat(catalogs.path("rings").findValuesAsText("name")).containsExactly("Pista 1", "Pista 2");
        catalogs.path("rings").forEach(ring -> {
            assertThat(ring.path("allowsFreeTraining").asBoolean()).isTrue();
            assertThat(ring.has("geometry")).isFalse();
        });
        assertThat(catalogs.path("plans").findValuesAsText("code")).containsExactly("MONTHLY", "MONTHLY_FAMILY", "PACK10", "SINGLE_CLASS", "THERAPY");
        assertThat(texts(catalogs.path("plans"), "name", "ca")).containsExactly("Abonament mensual", "Abonament mensual · 2 gossos (familiar)",
                "Pack de 10 sessions", "Classe solta", "Teràpia");
        // Ruling E153 (A41): es says «Bono» for the 10-session pack.
        assertThat(texts(catalogs.path("plans"), "name", "es")).containsExactly("Abono mensual", "Abono mensual · 2 perros (familiar)",
                "Bono de 10 sesiones", "Clase suelta", "Terapia");
        assertThat(texts(catalogs.path("plans"), "name", "en")).containsExactly("Monthly membership", "Monthly membership · 2 dogs (family)",
                "10-session pack", "Single class", "Therapy");
        assertThat(catalogs.path("plans").get(4).path("billingMode").asText()).isEqualTo("MAINTENANCE");
        // One price per plan, the Cànic's current amounts (the single class: a tenth of the 10-session pack), EUR, tax 0.
        assertThat(catalogs.path("prices").findValuesAsText("planCode")).containsExactly("MONTHLY", "MONTHLY_FAMILY", "PACK10", "SINGLE_CLASS", "THERAPY");
        assertThat(catalogs.path("prices").findValuesAsText("concept")).containsExactly("MONTHLY_FEE", "MONTHLY_FEE", "PACK", "SINGLE_CLASS", "MAINTENANCE_FEE");
        assertThat(catalogs.path("prices").findValues("amountMinor").stream().map(JsonNode::asInt)).containsExactly(6000, 9000, 18000, 1800, 1000);
        catalogs.path("prices").forEach(price -> {
            assertThat(price.path("amount").path("currency").asText()).isEqualTo("EUR");
            assertThat(price.path("taxPercent").asDouble()).isZero();
            assertThat(price.path("validTo").isNull()).isTrue();
        });
        assertThat(texts(catalogs.path("faq"), "category", "ca")).containsExactly("Convivència al club", "Reserves de classe", "Competicions");
        assertThat(texts(catalogs.path("faq"), "category", "en")).containsExactly("Life at the club", "Class bookings", "Competitions");
        for (String locale : LOCALES) { assertThat(texts(catalogs.path("faq"), "answer", locale)).containsOnly(ProvisionalText.MARKER); }
        // R-05-21b: the placeholder entries are active (the model has no `published`), so the club sees and edits them at D11.
        catalogs.path("faq").forEach(entry -> assertThat(entry.path("active").asBoolean()).isTrue());
    }

    @Test void T_17_14_E11_T18_everyLocalizedTextOfTheTemplateIsCompleteInCaEsEn() {
        var definition = seed();
        assertThat(strings(definition.path("club").path("locales"))).containsExactly("ca", "es", "en");
        var found = new ArrayList<String>();
        walk(definition.path("catalogs"), "catalogs", found);
        // 6 level names, 5 plan names, 2 plan conditions, 1 plan price label and 3 × 3 FAQ texts.
        assertThat(found).hasSize(23);
        var seeds = MessageTemplateSeed.load();
        for (var entry : definition.path("messageTemplates")) {
            var seeded = seeds.of(entry.path("code").asText()).orElseThrow();
            for (var texts : List.of(seeded.name(), seeded.title(), seeded.body())) {
                assertThat(texts.keySet()).as(seeded.code()).isEqualTo(LOCALES);
                assertThat(texts.values()).as(seeded.code()).allSatisfy(text -> assertThat(text).isNotBlank());
            }
            if (!seeded.smsBody().isEmpty()) { assertThat(seeded.smsBody().keySet()).as(seeded.code()).isEqualTo(LOCALES); }
        }
    }
    /** A localized text is an object whose keys are locales; every one of them in the seed has the three, none blank. */
    static void walk(JsonNode node, String path, List<String> found) {
        if (node.isArray()) { for (int i = 0; i < node.size(); i++) { walk(node.get(i), path + "/" + i, found); } return; }
        if (!node.isObject() || node.isEmpty()) { return; }
        var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
        if (LOCALES.containsAll(names)) {
            assertThat(names).as(path).isEqualTo(LOCALES);
            node.forEach(text -> assertThat(text.asText()).as(path).isNotBlank());
            found.add(path); return;
        }
        names.forEach(name -> walk(node.get(name), path + "/" + name, found));
    }

    @Test void T_17_14_E11_T18_theTemplateListsExactlyTheTemplatedCodesOfTheNotificationCatalog() throws Exception {
        var listed = seed().path("messageTemplates").findValuesAsText("code");
        var document = templatedCodesOfTheDocument();
        assertThat(document).hasSize(52).contains("N-02", "N-55").doesNotContain("N-25", "N-26", "N-27", "N-39", "N-43", "N-44", "N-45", "N-48", "N-52", "N-53");
        assertThat(listed).as("parity with CATALEG_NOTIFICACIONS.md, in its order").containsExactlyElementsOf(document);
        assertThat(listed).containsExactlyElementsOf(MessageTemplateSeed.eligible().stream().map(spec -> spec.code()).toList());
        System.out.println("E11-T18 template messageTemplates: " + listed.size() + " codes, parity with CATALEG_NOTIFICACIONS.md: " + listed.equals(document));
    }

    @Test void T_17_14_E11_T18_noTextOfTheTemplateNamesAClubItsRingsItsTimesOrItsTown() throws Exception {
        var files = List.of(SEED, Path.of("src/main/resources/seed/message-templates.ca.json"), Path.of("src/main/resources/seed/message-templates.es.json"),
                Path.of("src/main/resources/seed/message-templates.en.json"));
        for (var file : files) {
            var matches = CLUB_LITERALS.matcher(Files.readString(file)).results().map(match -> match.group()).toList();
            assertThat(matches).as(file.toString()).isEmpty();
            System.out.println("E11-T18 grep -iE 'canic|cànic|7:30|agilitycanic|cabrera|llavaneres' " + file + ": " + matches.size() + " matches");
        }
        // The Cànic's ring names (its seed): the template's rings are «Pista 1» and «Pista 2».
        assertThat(seed().path("catalogs").path("rings").findValuesAsText("name")).doesNotContain("Muntanya", "Central", "Carretera", "Cadells", "Petita");
    }
}

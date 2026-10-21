package com.agilityhub.core.platform.api;

import com.agilityhub.core.clubs.catalogs.application.PlanService;
import com.agilityhub.core.clubs.catalogs.application.PriceResolver;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import com.agilityhub.core.clubs.catalogs.persistence.Plan;
import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.definition.ClubDefinitionCodec;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ProvisionalText;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E11-T18 (A41.1, ruling E153; S17 R-17-08, R-17-13, T-17-07, T-17-14): `seeds/club-template-default.yaml` applies twice without a
 * change, its administrator lists the starter catalogs with every text in ca/es/en and every plan resolves a current price, and D19's
 * «Catàlegs base» clones it part by part into an empty club: only the destination's languages, a second clone `409 CATALOG_NOT_EMPTY`,
 * a club in another currency `422 CURRENCY_MISMATCH` for the plans.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TemplateClubCatalogsIT extends AbstractIntegrationTest {
    static final String BASE = "/api/v1/platform/clubs";
    static final List<String> PARTS = List.of("LEVELS", "RINGS", "PLANS", "FAQ", "MESSAGE_TEMPLATES");
    static final Map<String, Integer> SIZES = Map.of("LEVELS", 6, "RINGS", 2, "PLANS", 5, "FAQ", 3, "MESSAGE_TEMPLATES", 52);
    /** The starter prices (amountMinor, EUR): the Cànic's current amounts; the single class is a tenth of the 10-session pack. */
    static final Map<String, Integer> PRICES = Map.of("MONTHLY", 6000, "MONTHLY_FAMILY", 9000, "PACK10", 18000, "SINGLE_CLASS", 1800, "THERAPY", 1000);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubDefinitions definitions;
    @Autowired ClubDefinitionCodec codec;
    @Autowired HostTenantResolver hosts;
    @Autowired PlanService plans;
    @Autowired PriceResolver resolver;
    @Autowired ClubClock clubClock;
    @Autowired MessageTemplateRepository templates;

    @BeforeEach void setup() {
        wipeDatabaseKeepingBootstrap(); hosts.invalidate(); clock.setInstant(Instant.parse("2026-10-21T10:00:00Z"));
    }
    ObjectNode seed() { return codec.read(Path.of("seeds/club-template-default.yaml")); }
    JsonNode call(MockHttpServletRequestBuilder request, Object body, int status, String role, String clubId) throws Exception {
        request.header("Host", "unknown.example.test").with(jwt().jwt(token -> { token.subject("template-test"); if (clubId != null) { token.claim("clubId", clubId); } })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        if (body != null) { request.contentType("application/json").content(mapper.writeValueAsString(body)); }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    JsonNode admin(String clubId, String path) throws Exception { return call(get("/api/v1" + path), null, 200, "ADMIN", clubId).path("items"); }
    JsonNode console(MockHttpServletRequestBuilder request, Object body, int status) throws Exception { return call(request, body, status, "AGILITYHUB_ADMIN", null); }
    static Set<String> keys(JsonNode object) { var names = new TreeSet<String>(); object.fieldNames().forEachRemaining(names::add); return names; }
    static PriceConcept concept(Plan plan) {
        return switch (plan.type()) {
            case MONTHLY -> plan.billingMode() == com.agilityhub.core.clubs.catalogs.domain.OfferTerms.BillingMode.MAINTENANCE ? PriceConcept.MAINTENANCE_FEE : PriceConcept.MONTHLY_FEE;
            case PACK -> PriceConcept.PACK;
            case SINGLE_CLASS -> PriceConcept.SINGLE_CLASS;
        };
    }
    /** Every plan of the current club resolves its concept's current price on the club's today (PriceResolver, R-05-15). */
    Map<String, Integer> currentPrices(String clubId) {
        try (var tenant = TenantContext.open(clubId)) {
            var today = clubClock.today(clubId); var amounts = new LinkedHashMap<String, Integer>();
            for (var plan : plans.list(true)) {
                var price = resolver.current(plan.id(), concept(plan), today);
                assertThat(price).as(plan.code() + " " + concept(plan) + " on " + today).isPresent();
                assertThat(price.orElseThrow().amount().currency()).isEqualTo("EUR");
                assertThat(price.orElseThrow().taxPercent().signum()).isZero();
                amounts.put(plan.code(), (int) price.orElseThrow().amount().amountMinor());
            }
            return amounts;
        }
    }

    @Test void T_17_14_R_17_13_theTemplateSeedAppliesTwiceAndItsAdminListsTheStarterCatalogsInCaEsEn() throws Exception {
        var first = definitions.apply(seed(), false); String template = first.id();
        assertThat(first.changes()).isPositive();
        assertThat(definitions.apply(seed(), false).changes()).as("second club:apply").isZero();
        assertThat(definitions.apply(definitions.export("club-template-default"), false).changes()).as("export round trip").isZero();
        var levels = admin(template, "/levels");
        assertThat(levels.findValuesAsText("code")).containsExactly("CAD", "INI", "INT", "AVA", "TER", "PENDENT");
        levels.forEach(level -> assertThat(keys(level.path("nameI18n"))).as(level.path("code").asText()).containsExactly("ca", "en", "es"));
        assertThat(levels.findValuesAsText("name")).as("the template's default locale").containsExactly("Puppies", "Beginners", "Intermediate", "Advanced", "Therapy", "Pending");
        var rings = admin(template, "/rings");
        assertThat(rings.findValuesAsText("name")).containsExactly("Pista 1", "Pista 2");
        rings.forEach(ring -> assertThat(ring.path("allowsFreeTraining").asBoolean()).isTrue());
        var offered = admin(template, "/plans");
        assertThat(offered.findValuesAsText("code")).containsExactly("MONTHLY", "MONTHLY_FAMILY", "PACK10", "SINGLE_CLASS", "THERAPY");
        offered.forEach(plan -> assertThat(keys(plan.path("nameI18n"))).as(plan.path("code").asText()).containsExactly("ca", "en", "es"));
        assertThat(currentPrices(template)).isEqualTo(PRICES);
        var faq = admin(template, "/faq-entries");
        assertThat(faq).hasSize(3);
        assertThat(new TreeSet<>(faq.findValuesAsText("category"))).hasSize(3);
        faq.forEach(entry -> {
            for (String field : List.of("categoryI18n", "questionI18n", "answerI18n")) { assertThat(keys(entry.path(field))).as(field).containsExactly("ca", "en", "es"); }
            entry.path("answerI18n").forEach(answer -> assertThat(answer.asText()).isEqualTo(ProvisionalText.MARKER));
        });
        try (var tenant = TenantContext.open(template)) {
            var stored = templates.findAll();
            assertThat(stored).extracting(t -> t.code()).containsExactlyInAnyOrderElementsOf(MessageTemplateSeed.eligible().stream().map(spec -> spec.code()).toList());
            stored.forEach(t -> {
                assertThat(t.title().values().keySet()).as(t.code()).containsExactlyInAnyOrder("ca", "es", "en");
                assertThat(t.body().values().keySet()).as(t.code()).containsExactlyInAnyOrder("ca", "es", "en");
            });
        }
        System.out.println("E11-T18 template club: levels " + levels.size() + ", rings " + rings.size() + ", plans " + offered.size()
                + " with current prices " + currentPrices(template) + ", FAQ " + faq.size() + ", message templates 52; second apply and export round trip: 0 changes");
    }

    @Test void T_17_07_R_17_08_theTemplateClonesPartByPartIntoAnEmptyClubAndOnlyOnce() throws Exception {
        String template = definitions.apply(seed(), false).id();
        clubs.save(PlatformFixtures.club("template-target", "target.example.test"));
        long version = clubs.findById("template-target").orElseThrow().version();
        console(patch(BASE + "/template-target"), Map.of("version", version, "locales", List.of("ca", "es"), "defaultLocale", "ca"), 200);
        // The template's plans are of the three types: the destination has the modules of a 10-session pack and a single class.
        var modules = new ArrayList<>(Module.defaults(Module.Preset.CANIC).stream().map(Enum::name).toList()); modules.add("SINGLE_CLASS");
        console(put(BASE + "/template-target/modules"), Map.of("version", clubs.findById("template-target").orElseThrow().version(), "modules", modules), 200);
        for (String part : PARTS) {
            var result = console(post(BASE + "/template-target/catalog-clone"), Map.of("fromClubId", template, "parts", List.of(part)), 200);
            assertThat(result.path("copied")).as(part).isEqualTo(mapper.valueToTree(Map.of(part, SIZES.get(part))));
            // Only the destination's languages: the template's `en` texts are left out, one coded warning per part (a ring's name is
            // a plain text, S05 §3, so the rings have none).
            var omitted = part.equals("RINGS") ? List.of() : List.of(Map.of("code", "LOCALE_OMITTED", "part", part, "from", "en"));
            assertThat(result.path("warnings")).as(part).isEqualTo(mapper.valueToTree(omitted));
        }
        assertThat(admin("template-target", "/levels").findValuesAsText("code")).containsExactly("CAD", "INI", "INT", "AVA", "TER", "PENDENT");
        admin("template-target", "/levels").forEach(level -> assertThat(keys(level.path("nameI18n"))).containsExactly("ca", "es"));
        assertThat(admin("template-target", "/levels").findValuesAsText("name")).containsExactly("Cadells", "Iniciació", "Intermedi", "Avançat", "Teràpia", "Pendent");
        assertThat(admin("template-target", "/rings").findValuesAsText("name")).containsExactly("Pista 1", "Pista 2");
        assertThat(currentPrices("template-target")).isEqualTo(PRICES);
        assertThat(admin("template-target", "/faq-entries").findValuesAsText("category")).containsExactly("Convivència al club", "Reserves de classe", "Competicions");
        try (var tenant = TenantContext.open("template-target")) {
            assertThat(templates.findAll()).hasSize(52).allSatisfy(t -> assertThat(t.body().values().keySet()).as(t.code()).containsExactlyInAnyOrder("ca", "es"));
        }
        for (String part : PARTS) {
            var refused = console(post(BASE + "/template-target/catalog-clone"), Map.of("fromClubId", template, "parts", List.of(part)), 409);
            assertThat(refused.path("code").asText()).isEqualTo("CATALOG_NOT_EMPTY");
            assertThat(refused.path("details").path("part").asText()).isEqualTo(part);
        }
        clubs.save(PlatformFixtures.club("template-pounds", "pounds.example.test"));
        console(patch(BASE + "/template-pounds"), Map.of("version", clubs.findById("template-pounds").orElseThrow().version(), "currency", "GBP"), 200);
        assertThat(console(post(BASE + "/template-pounds/catalog-clone"), Map.of("fromClubId", template, "parts", List.of("PLANS")), 422).path("code").asText())
                .isEqualTo("CURRENCY_MISMATCH");
        try (var tenant = TenantContext.open("template-pounds")) { assertThat(plans.list(true)).isEmpty(); }
    }

    /**
     * seeds/README.md: the template's plans need `PACKS` and `SINGLE_CLASS` in the destination. A club on the CANIC preset (no
     * `SINGLE_CLASS`) gets S05 T-05-17's refusal of the single class, and the clone writes nothing: no plan, no price, no version.
     */
    @Test void T_17_07_R_17_08_T_05_17_plansNeedTheModulesOfTheirTypesAndARefusedCloneWritesNothing() throws Exception {
        String template = definitions.apply(seed(), false).id();
        clubs.save(PlatformFixtures.club("template-canic-preset", "preset.example.test"));
        console(put(BASE + "/template-canic-preset/modules"), Map.of("version", clubs.findById("template-canic-preset").orElseThrow().version(), "preset", "CANIC"), 200);
        long version = clubs.findById("template-canic-preset").orElseThrow().version();
        var refused = console(post(BASE + "/template-canic-preset/catalog-clone"), Map.of("fromClubId", template, "parts", List.of("PLANS")), 400);
        assertThat(refused.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(refused.path("details").path("fieldErrors")).isEqualTo(mapper.valueToTree(List.of(Map.of("field", "type", "code", "MODULE_DISABLED"))));
        assertThat(clubs.findById("template-canic-preset").orElseThrow().version()).isEqualTo(version);
        try (var tenant = TenantContext.open("template-canic-preset")) { assertThat(plans.list(true)).isEmpty(); }
        assertThat(console(post(BASE + "/template-canic-preset/catalog-clone"), Map.of("fromClubId", template, "parts", List.of("LEVELS")), 200)
                .path("copied").path("LEVELS").asInt()).isEqualTo(6);
    }
}

package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.platform.application.ClubAdminProvisioner;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class ClubDefinitionsIT extends AbstractIntegrationTest {
    @Autowired ClubDefinitions definitions;
    @Autowired ClubDefinitionCodec codec;
    @Autowired ClubDefinitionWriter writer;
    @Autowired ClubDefinitionMapper mapping;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired ClubRepository clubs;
    @Autowired ParameterRepository parameters;
    @Autowired AccountRepository accounts;
    @Autowired MembershipRepository memberships;
    @Autowired ClubAdminProvisioner admins;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuditRepository audits;

    @BeforeEach void emptyDatabase() {
        reset(audits);
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "domain_events", "audit_entries", "club_pages")) {
            mongo.remove(new Query(), collection);
        }
        hosts.invalidate();
    }
    ObjectNode seed(String name) { return codec.read(Path.of("seeds/club-" + name + ".yaml")); }
    long count(String collection) { return mongo.count(new Query(), collection); }
    void failure(ObjectNode definition, ErrorCode code) {
        assertThatThrownBy(() -> definitions.apply(definition, false)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(code));
        assertThat(TenantContext.current()).isNull();
    }
    @Test @AuditCovers(AuditAction.CLUB_UPDATED)
    void T_02_12_canicMatchesBrandingAndParametersFixturesAndSecondApplyDoesNotWrite() throws Exception {
        var definition = seed("canic");
        var preview = definitions.apply(Path.of("seeds/club-canic.yaml"), true);
        assertThat(preview.changes()).isPositive(); assertThat(preview.render(true)).contains("+ club", "+ domains", "+ theme", "+ admins", "dry run");
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "domain_events", "audit_entries", "club_pages")) { assertThat(count(collection)).isZero(); }
        var first = definitions.apply(definition, false);
        var original = clubs.findBySlug("canic").orElseThrow();
        var response = mvc.perform(get("/api/v1/branding").header("Host", "app.agilitycanic.cat")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(response.getContentAsString())).isEqualTo(mapper.readTree(Path.of("src/test/resources/fixtures/branding-canic.json").toFile()));
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(configs.get(first.id()).parameters())).isEqualTo(mapper.readTree(Path.of("src/test/resources/fixtures/parameters-canic.json").toFile()));
        // The Cànic seed: one event and one audit entry per catalog row, COMPETICIO_1 and its price included (B34).
        assertThat(count("domain_events")).isEqualTo(73); assertThat(count("audit_entries")).isEqualTo(38);
        assertThat(mongo.findAll(AuditEntry.class).stream().filter(a -> a.action() == AuditAction.CLUB_UPDATED).findFirst().orElseThrow().action()).isEqualTo(AuditAction.CLUB_UPDATED);
        assertThat(mongo.findAll(AuditEntry.class).stream().filter(a -> a.action() == AuditAction.CLUB_UPDATED).findFirst().orElseThrow().clubId()).isEqualTo(first.id());
        assertThat(mongo.findAll(AuditEntry.class).stream().filter(a -> a.action() == AuditAction.CLUB_UPDATED).findFirst().orElseThrow().reason()).isEqualTo("source: APPLY");
        assertThat(mongo.findAll(Account.class).getFirst().passwordHash()).startsWith("$argon2id$");
        var second = definitions.apply(definition, false);
        assertThat(second.changes()).isZero(); assertThat(second.render(false)).contains("0 changes");
        assertThat(clubs.findBySlug("canic").orElseThrow()).isEqualTo(original);
        assertThat(count("domain_events")).isEqualTo(73); assertThat(count("audit_entries")).isEqualTo(38);
        assertThat(count("accounts")).isEqualTo(15); assertThat(count("memberships")).isEqualTo(15);
        var exported = definitions.export("canic"); codec.validate(exported);
        assertThat(definitions.apply(exported, false).changes()).isZero();
        assertThat(TenantContext.current()).isNull();
    }
    /**
     * E3-T14 (S04 R-04-10): the Cànic collects by direct debit and cash, so its seed enables `SEPA_XML` and `MANUAL`. It has
     * no creditor data, so `SEPA_XML` is not `configured`. E5-T23: `MANUAL` is, by its instructions. `GET /club` and
     * `GET /signup` show the same methods.
     */
    @Test void T_02_12_canicSeedEnablesDirectDebitAndCashWithoutConfiguringThem() throws Exception {
        var first = definitions.apply(seed("canic"), false);
        var stored = clubs.findById(first.id()).orElseThrow().paymentProviders();
        assertThat(stored.keySet()).containsExactly("SEPA_XML", "MANUAL");
        assertThat(stored).isEqualTo(Map.of("SEPA_XML", Map.of("enabled", true), "MANUAL", Map.of("enabled", true, "instructions", CASH_INSTRUCTIONS)));
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject("seed-admin").claim("clubId", first.id()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        var flags = mapper.readTree(mvc.perform(get("/api/v1/club").header("Host", "admin.agilitycanic.cat").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("paymentProviders");
        assertThat(flags).isEqualTo(mapper.readTree("{\"SEPA_XML\":{\"configured\":false,\"enabled\":true},\"MANUAL\":{\"configured\":true,\"enabled\":true}}"));
        // Activation is fixture setup (the seed opens the club in ONBOARDING); the offer then follows the same flags.
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(first.id())),
                new org.springframework.data.mongodb.core.query.Update().set("status", "ACTIVE"), Club.class);
        configs.invalidate(first.id()); hosts.invalidate();
        var signup = mapper.readTree(mvc.perform(get("/api/v1/signup").header("Host", "app.agilitycanic.cat"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(signup.path("paymentMethods").findValuesAsText("type")).containsExactly("SEPA_DD", "MANUAL");
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(first.id())),
                new org.springframework.data.mongodb.core.query.Update().set("status", "ONBOARDING"), Club.class);
        assertThat(definitions.apply(seed("canic"), false).changes()).isZero();
        var exported = definitions.export("canic");
        assertThat(exported.path("paymentProviders")).isEqualTo(mapper.valueToTree(Map.of("SEPA_XML", Map.of("enabled", true),
                "MANUAL", Map.of("enabled", true, "instructions", CASH_INSTRUCTIONS))));
        codec.validate(exported); assertThat(definitions.apply(exported, false).changes()).isZero();
    }
    /** The Cànic's cash payment instructions, word for word as the product owner confirmed them from the approved mockup 19 (26-09). */
    static final Map<String, String> CASH_INSTRUCTIONS = Map.of(
            "ca", "El pagament de l'entrada i el mes en curs (o el proper) és necessari per a la validació de l'alta. Pot fer-se per transferència"
                    + " al compte ES08 2100 0416 5702 0016 4955 (Club d'Agility Cànic) o per Bizum al 607 475 945.",
            "es", "El pago de la entrada y del mes en curso (o del siguiente) es necesario para la validación del alta. Puede hacerse por transferencia"
                    + " a la cuenta ES08 2100 0416 5702 0016 4955 (Club d'Agility Cànic) o por Bizum al 607 475 945.");
    /**
     * E5-T23 step 1 (S04 §2 row 19, R-04-10; S12 `MANUAL {instructions}`; Jordi, 26-09): the Cànic's seed carries its cash
     * payment instructions. A club applied from the previous seed gets them as one `paymentProviders` change, then none.
     * `GET /signup` sends them in `paymentMethods[MANUAL].instructions`, in ca and in es. A definition without them keeps
     * them, the export round-trips them, and they must have the club's default locale (S17 R-17-05).
     */
    @Test void T_04_14_T_02_12_canicSeedCarriesItsCashPaymentInstructions() throws Exception {
        var previous = activeCanic(); previous.withObject("paymentProviders").withObject("MANUAL").remove("instructions");
        var id = definitions.apply(previous, false).id();
        assertThat(clubs.findById(id).orElseThrow().paymentProviders().get("MANUAL")).isEqualTo(Map.of("enabled", true));
        var preview = definitions.apply(activeCanic(), true);
        assertThat(preview.changes()).isEqualTo(1);
        assertThat(preview.render(true)).contains("~ paymentProviders changed",
                "paymentProviders.MANUAL: {\"enabled\":true} -> {\"enabled\":true,\"instructions\":{\"ca\":\"El pagament de l'entrada");
        assertThat(definitions.apply(activeCanic(), false).changes()).isEqualTo(1);
        assertThat(clubs.findById(id).orElseThrow().paymentProviders().get("MANUAL")).isEqualTo(Map.of("enabled", true, "instructions", CASH_INSTRUCTIONS));
        var second = definitions.apply(activeCanic(), false);
        assertThat(second.changes()).isZero(); assertThat(second.render(false)).contains("= paymentProviders unchanged", "0 changes");
        for (var language : List.of("ca", "es")) {
            var methods = mapper.readTree(mvc.perform(get("/api/v1/signup").header("Host", "app.agilitycanic.cat").header("Accept-Language", language))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("paymentMethods");
            var manual = java.util.stream.StreamSupport.stream(methods.spliterator(), false).filter(method -> "MANUAL".equals(method.path("type").asText())).findFirst().orElseThrow();
            assertThat(manual.path("instructions").asText()).as(language).isEqualTo(CASH_INSTRUCTIONS.get(language));
        }
        assertThat(definitions.apply(previous, false).changes()).as("a definition without them keeps them").isZero();
        var exported = definitions.export("canic"); codec.validate(exported);
        assertThat(exported.at("/paymentProviders/MANUAL/instructions")).isEqualTo(mapper.valueToTree(CASH_INSTRUCTIONS));
        assertThat(definitions.apply(exported, false).changes()).isZero();
        var withoutDefault = activeCanic(); withoutDefault.withObject("paymentProviders").withObject("MANUAL").putObject("instructions").put("es", CASH_INSTRUCTIONS.get("es"));
        assertThatThrownBy(() -> definitions.apply(withoutDefault, false)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(error.details()).containsEntry("fieldErrors", List.of(Map.of("field", "paymentProviders.MANUAL.instructions", "code", "REQUIRED")));
        });
        assertThat(clubs.findById(id).orElseThrow().paymentProviders().get("MANUAL")).isEqualTo(Map.of("enabled", true, "instructions", CASH_INSTRUCTIONS));
    }
    /**
     * E3-T16 step 3 (Jordi, 25-09; S02 §3, R-02-02; S17 §3): the Cànic's seed carries its legal identity and registered office,
     * and the town it shows with its name (`displayCity`), for the public footer «Club Agility Cànic · G63189617 · Cabrera de
     * Mar» over «Carrer Sant Pere, 10 · 08392 Sant Andreu de Llavaneres». A club applied from the previous seed gets them as one
     * `club` change, then none; the export round-trips them.
     */
    @Test void T_02_07_T_02_12_canicSeedCarriesItsLegalIdentityAndRegisteredOfficeForThePublicFooter() throws Exception {
        var previous = seed("canic"); var identity = previous.withObject("club");
        identity.remove(List.of("legalName", "taxId", "displayCity"));
        identity.set("address", mapper.readTree("{\"city\":\"Cabrera de Mar\",\"country\":\"ES\"}"));
        definitions.apply(previous, false);
        var preview = definitions.apply(Path.of("seeds/club-canic.yaml"), true);
        assertThat(preview.changes()).isEqualTo(1);
        assertThat(preview.render(true)).contains("~ club changed", "club.displayCity: null -> \"Cabrera de Mar\"",
                "club.legalName: null -> \"Club Agility Cànic\"", "club.taxId: null -> \"G63189617\"",
                "club.address: {\"city\":\"Cabrera de Mar\",\"country\":\"ES\"} -> {\"street\":\"Carrer Sant Pere, 10\",\"postalCode\":\"08392\",\"city\":\"Sant Andreu de Llavaneres\",\"region\":\"Barcelona\",\"country\":\"ES\"}");
        assertThat(definitions.apply(seed("canic"), false).changes()).isEqualTo(1);
        var club = mapper.readTree(mvc.perform(get("/api/v1/branding").header("Host", "app.agilitycanic.cat")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("club");
        assertThat(club.path("city").asText()).isEqualTo("Cabrera de Mar");
        assertThat(club.path("legalName").asText()).isEqualTo("Club Agility Cànic");
        assertThat(club.path("taxId").asText()).isEqualTo("G63189617");
        assertThat(club.path("legalAddress")).isEqualTo(mapper.readTree("{\"street\":\"Carrer Sant Pere, 10\",\"postalCode\":\"08392\",\"city\":\"Sant Andreu de Llavaneres\"}"));
        assertThat(definitions.apply(seed("canic"), false).changes()).isZero();
        var exported = definitions.export("canic");
        assertThat(exported.at("/club/displayCity").asText()).isEqualTo("Cabrera de Mar");
        assertThat(exported.at("/club/address/street").asText()).isEqualTo("Carrer Sant Pere, 10");
        codec.validate(exported); assertThat(definitions.apply(exported, false).changes()).isZero();
        // The Spanish country profile checks the CIF's control character (S02 §3): a wrong one is refused and nothing is written.
        var wrong = seed("canic"); wrong.withObject("club").put("taxId", "G63189618");
        failure(wrong, ErrorCode.VALIDATION_ERROR);
        assertThat(clubs.findBySlug("canic").orElseThrow().taxId()).isEqualTo("G63189617");
        // A blank town (the club settings accept any text) still exports to a valid definition that applies with no change.
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("slug").is("canic")),
                new org.springframework.data.mongodb.core.query.Update().set("displayCity", ""), Club.class);
        var blank = definitions.export("canic"); codec.validate(blank);
        assertThat(blank.at("/club/displayCity").asText()).isEmpty(); assertThat(definitions.apply(blank, false).changes()).isZero();
    }
    /**
     * E3-T16 round 2 (review #2; S02 R-02-06): switching a club's country profile does not re-check its stored data. A
     * `GENERIC` club keeps its unvalidated tax id when a definition moves it to `ES`; a tax id the definition changes is
     * checked by the new profile. (E5-T16 step 3: named by the rule it asserts.)
     */
    @Test void R_02_06_aProfileSwitchKeepsTheStoredTaxIdAndOnlyAChangedOneIsChecked() {
        var generic = seed("minim"); generic.withObject("club").put("taxId", "PT501234567");
        definitions.apply(generic, false);
        var spanish = generic.deepCopy(); spanish.withObject("club").put("countryProfile", "ES");
        var switched = definitions.apply(spanish, false);
        assertThat(switched.changes()).isEqualTo(1); assertThat(switched.render(false)).contains("club.countryProfile: \"GENERIC\" -> \"ES\"");
        var club = clubs.findBySlug("minim").orElseThrow();
        assertThat(club.countryProfile()).isEqualTo("ES"); assertThat(club.taxId()).isEqualTo("PT501234567");
        assertThat(definitions.apply(spanish, false).changes()).isZero();
        var wrong = spanish.deepCopy(); wrong.withObject("club").put("taxId", "G63189618");
        assertThatThrownBy(() -> definitions.apply(wrong, false)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR); assertThat(error.details()).containsEntry("field", "club.taxId");
        });
        assertThat(clubs.findBySlug("minim").orElseThrow().taxId()).isEqualTo("PT501234567");
        var corrected = spanish.deepCopy(); corrected.withObject("club").put("taxId", "B12345674");
        assertThat(definitions.apply(corrected, false).changes()).isEqualTo(1);
        assertThat(clubs.findBySlug("minim").orElseThrow().taxId()).isEqualTo("B12345674");
        assertThat(TenantContext.current()).isNull();
    }
    /**
     * E5-T16 step 2 (review E3-T16 #3; S02 §3, R-02-06): a definition's tax id is stored normalized (upper case, without
     * spaces or separators), so `/branding` publishes that form in the public footer. The same id written with other
     * spacing, case or separators is no change.
     */
    @Test void R_02_06_clubApplyStoresTheTaxIdNormalizedAndOtherSpacingIsNoChange() throws Exception {
        var typed = seed("canic"); typed.withObject("club").put("taxId", "g-6318 9617");
        var id = definitions.apply(typed, false).id();
        assertThat(mongo.getCollection("clubs").find(new org.bson.Document("_id", id)).first().getString("taxId")).isEqualTo("G63189617");
        var club = mapper.readTree(mvc.perform(get("/api/v1/branding").header("Host", "app.agilitycanic.cat")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("club");
        assertThat(club.path("taxId").asText()).isEqualTo("G63189617");
        assertThat(definitions.apply(seed("canic"), true).changes()).isZero();
        var respaced = seed("canic"); respaced.withObject("club").put("taxId", "G 6318.9617");
        var again = definitions.apply(respaced, false);
        assertThat(again.changes()).isZero(); assertThat(again.render(false)).contains("= club unchanged");
        assertThat(definitions.export("canic").at("/club/taxId").asText()).isEqualTo("G63189617");
    }
    /**
     * E5-T17 (review E5-T16 #1; S02 §3 amended 26-09, R-02-06): a definition whose tax id is made only of separators is
     * refused (`VALIDATION_ERROR` on `club.taxId`, in the dry run too) and the stored one stays; `""` clears it.
     */
    @Test void R_02_06_clubApplyRefusesATaxIdOfSeparatorsOnlyAndOnlyBlankClearsIt() {
        var id = definitions.apply(seed("canic"), false).id();
        for (String separators : List.of("-", " / ", ".")) {
            var definition = seed("canic"); definition.withObject("club").put("taxId", separators);
            for (boolean dryRun : List.of(true, false)) {
                assertThatThrownBy(() -> definitions.apply(definition, dryRun)).as(separators).isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(error.details()).containsEntry("field", "club.taxId")
                            .containsEntry("fieldErrors", List.of(Map.of("field", "club.taxId", "code", "INVALID_VALUE")));
                });
            }
        }
        assertThat(clubs.findById(id).orElseThrow().taxId()).isEqualTo("G63189617");
        var cleared = seed("canic"); cleared.withObject("club").put("taxId", "");
        assertThat(definitions.apply(cleared, false).changes()).isEqualTo(1);
        assertThat(clubs.findById(id).orElseThrow().taxId()).isNull();
        assertThat(TenantContext.current()).isNull();
    }
    /**
     * E5-T18 step 5 (review E5-T17 #5; S02 §3 amended 26-09, R-02-06): in a definition, a tax id of spaces only is blank,
     * not «separators only»: it clears the stored one (in the dry run too, as one change), like `""`. A definition without
     * `club.taxId` keeps the stored one, as every omitted club field (the merge starts from the stored club).
     */
    @Test void R_02_06_clubApplyClearsTheTaxIdWithSpacesOnlyLikeEmpty() {
        var id = definitions.apply(seed("canic"), false).id();
        for (String blank : List.of("   ", " \t ", "")) {
            var definition = seed("canic"); definition.withObject("club").put("taxId", blank);
            assertThat(clubs.findById(id).orElseThrow().taxId()).as("[%s]", blank).isEqualTo("G63189617");
            assertThat(definitions.apply(definition, true).changes()).as("[%s] dry run", blank).isEqualTo(1);
            assertThat(clubs.findById(id).orElseThrow().taxId()).as("[%s] dry run writes nothing", blank).isEqualTo("G63189617");
            assertThat(definitions.apply(definition, false).changes()).as("[%s]", blank).isEqualTo(1);
            assertThat(clubs.findById(id).orElseThrow().taxId()).as("[%s] clears it", blank).isNull();
            assertThat(definitions.apply(definition, true).changes()).as("[%s] again", blank).isZero();
            assertThat(definitions.apply(seed("canic"), false).changes()).isEqualTo(1);
        }
        var absent = seed("canic"); absent.withObject("club").remove("taxId");
        assertThat(definitions.apply(absent, false).changes()).isZero();
        assertThat(clubs.findById(id).orElseThrow().taxId()).as("an omitted tax id is kept").isEqualTo("G63189617");
    }
    /**
     * E5-T21 step 5 (review E5-T18 #3; S02 §3 amended 26-09, R-02-06): a definition clears the tax id with `""`; its schema
     * types `club.taxId` as a string, so `taxId: null` is refused (`VALIDATION_ERROR`, in the dry run too), as a YAML file
     * written by hand and as a definition node, and the stored tax id stays. Only `PUT /club` clears it with `null`.
     */
    @Test void R_02_06_aClubDefinitionRefusesANullTaxId(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var id = definitions.apply(seed("canic"), false).id();
        var yaml = directory.resolve("club-null-tax-id.yaml");
        java.nio.file.Files.writeString(yaml, java.nio.file.Files.readString(Path.of("seeds/club-minim.yaml")).replaceFirst("(?m)^club:\\n", "club:\n  taxId: null\n"));
        assertThat(java.nio.file.Files.readString(yaml)).contains("club:\n  taxId: null\n  slug: \"minim\"");
        assertThatThrownBy(() -> codec.read(yaml)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(error.details()).containsEntry("fields", List.of("$.club.taxId: type"));
        });
        var definition = seed("canic"); definition.withObject("club").putNull("taxId");
        for (boolean dryRun : List.of(true, false)) {
            assertThatThrownBy(() -> definitions.apply(definition, dryRun)).as("dry run %s", dryRun).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                assertThat(error.details()).containsEntry("fields", List.of("$.club.taxId: type"));
            });
        }
        assertThat(clubs.findById(id).orElseThrow().taxId()).isEqualTo("G63189617");
        assertThat(TenantContext.current()).isNull();
    }
    /**
     * E5-T17 (review E5-T16 #4, optional; S02 §3, R-02-06): under `ES` a definition's 7-digit DNI is stored padded, the form
     * the check reads, so the same id with or without the leading zero is no change.
     */
    @Test void R_02_06_clubApplyStoresASevenDigitDniPaddedUnderEs() {
        var typed = seed("canic"); typed.withObject("club").put("taxId", "1234567-l");
        var id = definitions.apply(typed, false).id();
        assertThat(clubs.findById(id).orElseThrow().taxId()).isEqualTo("01234567L");
        for (String same : List.of("01234567L", "1234567L", "1234567 l")) {
            var again = seed("canic"); again.withObject("club").put("taxId", same);
            assertThat(definitions.apply(again, true).changes()).as(same).isZero();
        }
        assertThat(clubs.findById(id).orElseThrow().taxId()).isEqualTo("01234567L");
    }
    /**
     * E3-T14: a club applied before (provider names only, so no `enabled` flag) gets one change from the new seed, then none.
     * The seed only switches the providers on: configuration stored outside it stays, and the names-only form keeps the flags.
     */
    @Test void T_17_01_enablingSeededProvidersIsOneChangeAndKeepsTheirStoredConfiguration() {
        var legacy = seed("canic"); legacy.putArray("paymentProviders").add("SEPA_XML").add("MANUAL");
        var id = definitions.apply(legacy, false).id();
        assertThat(clubs.findById(id).orElseThrow().paymentProviders()).isEqualTo(Map.of("SEPA_XML", Map.of(), "MANUAL", Map.of()));
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().set("paymentProviders.SEPA_XML.creditorName", "Fictional Creditor"), Club.class);
        var preview = definitions.apply(Path.of("seeds/club-canic.yaml"), true);
        assertThat(preview.changes()).isEqualTo(1);
        assertThat(preview.render(true)).contains("~ paymentProviders changed", "paymentProviders.SEPA_XML: {\"enabled\":false} -> {\"enabled\":true}",
                "paymentProviders.MANUAL: {\"enabled\":false} -> {\"enabled\":true,\"instructions\":");
        assertThat(clubs.findById(id).orElseThrow().paymentProviders()).isEqualTo(Map.of("SEPA_XML", Map.of("creditorName", "Fictional Creditor"), "MANUAL", Map.of()));
        assertThat(definitions.apply(seed("canic"), false).changes()).isEqualTo(1);
        assertThat(clubs.findById(id).orElseThrow().paymentProviders()).isEqualTo(Map.of("SEPA_XML", Map.of("creditorName", "Fictional Creditor", "enabled", true),
                "MANUAL", Map.of("enabled", true, "instructions", CASH_INSTRUCTIONS)));
        assertThat(definitions.apply(seed("canic"), false).changes()).isZero();
        assertThat(definitions.apply(legacy, false).changes()).isZero();
        var disabled = seed("canic"); disabled.withObject("paymentProviders").withObject("MANUAL").put("enabled", false);
        var off = definitions.apply(disabled, false);
        assertThat(off.changes()).isEqualTo(1);
        assertThat(clubs.findById(id).orElseThrow().paymentProviders().get("MANUAL")).isEqualTo(Map.of("enabled", false, "instructions", CASH_INSTRUCTIONS));
    }
    /** The methods the anonymous `GET /signup` of the Cànic offers, read as a fresh request would (no cache eviction here). */
    List<String> offered() throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/signup").header("Host", "app.agilitycanic.cat")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("paymentMethods").findValuesAsText("type");
    }
    /** The Cànic seed, opened (`ACTIVE`) so that its public form answers; the activation goes through `club:apply` too. */
    ObjectNode activeCanic() { var definition = seed("canic"); definition.withObject("club").put("status", "ACTIVE"); return definition; }
    /**
     * Round 2 (review #3, R-04-10): `club:apply` publishes `ClubConfigChanged` (catalog type `ClubUpdated`), and the
     * `GET /signup` configuration cache is evicted right after its commit. This test never evicts that cache by hand.
     * (E5-T16 step 3: R-04-10, the organizer's E3-T14 note.)
     */
    @Test void R_04_10_clubApplyRefreshesTheSignupFormsPaymentMethods() throws Exception {
        var definition = activeCanic();
        definitions.apply(definition, false);
        assertThat(offered()).containsExactly("SEPA_DD", "MANUAL");
        definition.withObject("paymentProviders").withObject("MANUAL").put("enabled", false);
        assertThat(definitions.apply(definition, false).changes()).isEqualTo(1);
        assertThat(offered()).containsExactly("SEPA_DD");
        definition.withObject("paymentProviders").withObject("MANUAL").put("enabled", true);
        definition.withObject("paymentProviders").putObject("STRIPE").put("enabled", true);
        assertThat(definitions.apply(definition, false).changes()).isEqualTo(1);
        assertThat(offered()).containsExactly("SEPA_DD", "MANUAL", "CARD");
    }
    /**
     * Round 2 (review #4, R-04-10): the offer follows the configured order, so reordering `paymentProviders` in a definition
     * is a change. `club:apply` reports it (the dry run writes nothing), stores the new order, and the offer follows it.
     * E5-T17 (review E5-T16 #3): T-17-01 is the Cànic created on an empty database, which this test does not assert.
     */
    @Test void R_04_10_reorderingThePaymentProvidersIsAChangeAndTheOfferFollowsIt() throws Exception {
        var definition = activeCanic();
        var id = definitions.apply(definition, false).id();
        var reordered = definition.deepCopy(); var providers = reordered.putObject("paymentProviders");
        providers.putObject("MANUAL").put("enabled", true); providers.putObject("SEPA_XML").put("enabled", true);
        var preview = definitions.apply(reordered, true);
        assertThat(preview.changes()).isEqualTo(1);
        assertThat(preview.render(true)).contains("~ paymentProviders changed", "paymentProviders order: [SEPA_XML, MANUAL] -> [MANUAL, SEPA_XML]");
        assertThat(clubs.findById(id).orElseThrow().paymentProviders().keySet()).containsExactly("SEPA_XML", "MANUAL");
        assertThat(offered()).containsExactly("SEPA_DD", "MANUAL");
        assertThat(definitions.apply(reordered, false).changes()).isEqualTo(1);
        assertThat(clubs.findById(id).orElseThrow().paymentProviders().keySet()).containsExactly("MANUAL", "SEPA_XML");
        assertThat(offered()).containsExactly("MANUAL", "SEPA_DD");
        assertThat(definitions.apply(reordered, false).changes()).isZero();
        var exported = definitions.export("canic");
        assertThat(exported.path("paymentProviders").fieldNames()).toIterable().containsExactly("MANUAL", "SEPA_XML");
        assertThat(definitions.apply(exported, false).changes()).isZero();
        // The list of names orders them too.
        var names = definition.deepCopy(); names.putArray("paymentProviders").add("SEPA_XML").add("MANUAL");
        assertThat(definitions.apply(names, false).changes()).isEqualTo(1);
        assertThat(offered()).containsExactly("SEPA_DD", "MANUAL");
        assertThat(definitions.apply(names, false).changes()).isZero();
    }
    @Test void T_17_01_parameterUpdatesPreserveUnlistedAndScopedOverridesAndRecordEventsHistory() {
        var definition = seed("canic"); definition.withObject("parameters").put("bookings.maxCurrentWeek", 3).put("signup.enabled", false);
        var first = definitions.apply(definition, false);
        try (var scope = TenantContext.open(first.id())) {
            parameters.insert(new Parameter("scoped", first.id(), "training.capacityPerRingSlot", 2, "int", "ring", "ring-a", List.of(), null, clock.instant()));
        }
        definition.withObject("parameters").remove("signup.enabled"); definition.withObject("parameters").put("bookings.maxCurrentWeek", 4);
        definition.withObject("club").put("name", "Updated club");
        definition.withObject("theme").withObject("colors").put("primary", "#123456");
        var preview = definitions.apply(definition, true);
        assertThat(preview.render(true)).contains("~ club", "club.name", "~ theme", "parameters.bookings.maxCurrentWeek", "3 -> 4");
        assertThat(configs.get(first.id()).get("bookings.maxCurrentWeek", Integer.class)).isEqualTo(3);
        var changed = definitions.apply(definition, false); assertThat(changed.changes()).isPositive();
        assertThat(configs.get(first.id()).get("bookings.maxCurrentWeek", Integer.class)).isEqualTo(4);
        assertThat(configs.get(first.id()).get("signup.enabled", Boolean.class)).isFalse();
        try (var scope = TenantContext.open(first.id())) {
            assertThat(parameters.findById("scoped")).isPresent();
            var saved = parameters.findAll().stream().filter(p -> p.key().equals("bookings.maxCurrentWeek")).findFirst().orElseThrow();
            assertThat(saved.history()).hasSize(2); assertThat(saved.history().getLast().value()).isEqualTo(3);
        }
        assertThat(mongo.findAll(org.bson.Document.class, "domain_events").stream().map(event -> event.getString("type")))
                .containsOnly("ClubUpdated", "ParameterChanged", "AccountCreated", "MembershipChanged", "ClubPageChanged", "LevelChanged", "RingChanged", "PlanChanged", "PriceChanged", "FaqChanged");
        long events = count("domain_events"), auditCount = count("audit_entries");
        assertThat(definitions.apply(definition, false).changes()).isZero();
        assertThat(count("domain_events")).isEqualTo(events); assertThat(count("audit_entries")).isEqualTo(auditCount);
        var invalid = definition.deepCopy(); invalid.withObject("parameters").put("invented.key", true); failure(invalid, ErrorCode.UNKNOWN_PARAMETER);
        invalid = definition.deepCopy(); invalid.withObject("parameters").put("bookings.maxCurrentWeek", "wrong"); failure(invalid, ErrorCode.PARAMETER_INVALID);
    }
    @Test void T_17_02_hostConflictIsRejectedForVerifiedAndPendingHostsAndTenantsHaveDifferentThemes() throws Exception {
        var first = definitions.apply(seed("canic"), false);
        var conflict = seed("canic"); conflict.withObject("club").put("slug", "another"); failure(conflict, ErrorCode.HOST_ALREADY_USED);
        assertThat(count("clubs")).isEqualTo(1);
        var second = definitions.apply(seed("minim"), false);
        assertThat(hosts.resolve("minim.example.test")).contains(second.id());
        assertThat(configs.get(first.id()).club().theme()).isNotEqualTo(configs.get(second.id()).club().theme());
        mvc.perform(get("/api/v1/branding").header("Host", "minim.example.test")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.club.city").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/branding").header("Host", "absent.example.test")).andExpect(status().isNotFound());
        try (var scope = TenantContext.open(first.id())) { assertThat(parameters.findAll()).extracting(Parameter::key).containsExactlyInAnyOrderElementsOf(seed("canic").path("parameters").properties().stream().map(Map.Entry::getKey).toList()); }
        try (var scope = TenantContext.open(second.id())) { assertThat(parameters.findAll()).hasSize(seed("minim").path("parameters").size()); }
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(first.id())),
                new org.springframework.data.mongodb.core.query.Update().set("domains.0.status", "PENDING"), Club.class);
        failure(conflict, ErrorCode.HOST_ALREADY_USED);
    }
    @Test void T_17_01_auditFailureRollsBackClubParametersAdminsAndOutboxTogether() {
        var definition = seed("minim"); doThrow(new IllegalStateException("injected audit failure")).when(audits).append(any());
        assertThatThrownBy(() -> definitions.apply(definition, false)).isInstanceOf(IllegalStateException.class);
        for (String collection : List.of("clubs", "parameters", "accounts", "memberships", "domain_events", "audit_entries", "club_pages")) { assertThat(count(collection)).as(collection).isZero(); }
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_17_01_templatePartialDefinitionAndExistingAdminPreserveIdentity() {
        var template = definitions.apply(seed("template-default"), false);
        var club = clubs.findBySlug("club-template-default").orElseThrow(); assertThat(club.template()).isTrue(); assertThat(club.domains()).isEmpty();
        assertThat(definitions.apply(definitions.export("club-template-default"), false).changes()).isZero();
        var definition = seed("canic"); definition.remove("accounts");
        definition.putArray("admins").addObject().put("email", "canic.admin@example.test").put("name", "Laia Example").put("locale", "ca");
        var result = definitions.apply(definition, false);
        var account = accounts.findByEmail("canic.admin@example.test").orElseThrow();
        try (var scope = TenantContext.open(result.id())) {
            var membership = memberships.findByAccountId(account.id()).orElseThrow();
            memberships.replace(new Membership(membership.id(), account.id(), result.id(), "member-a", Set.of(Role.MEMBER), Membership.Status.ACTIVE, Role.MEMBER));
        }
        ((ObjectNode) definition.path("admins").get(0)).put("name", "Ignored rename");
        definitions.apply(definition, false);
        assertThat(accounts.findById(account.id()).orElseThrow()).isEqualTo(account);
        try (var scope = TenantContext.open(result.id())) {
            assertThat(memberships.findByAccountId(account.id()).orElseThrow().roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.ADMIN);
            admins.provision(new ClubAdminProvisioner.Admin(account.email(), account.name(), account.locale()));
            assertThat(admins.list()).hasSize(1);
        }
        definition.remove(List.of("domains", "paymentProviders", "legal", "admins"));
        assertThat(definitions.apply(definition, false).changes()).isZero();
        assertThat(clubs.findBySlug("canic").orElseThrow().domains()).hasSize(2);
        assertThatThrownBy(() -> definitions.export("missing")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
        try (var scope = TenantContext.open("wrong")) {
            assertThatThrownBy(() -> writer.preview(definition)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ErrorCode.STALE_VERSION));
        }
        var deployed = mapping.merge(seed("minim"), null, "pending", clock.instant(), false);
        assertThat(deployed.domains().getFirst().status()).isEqualTo(Club.DomainStatus.PENDING);
        assertThat(deployed.domains().getFirst().verifiedAt()).isNull();
        var merged = mapping.merge(seed("minim"), deployed, "pending", clock.instant(), false);
        assertThat(merged.domains()).isEqualTo(deployed.domains());
    }
    @Test void T_17_01_cliExportsYamlAndExitsWithoutOpeningTheConfiguredHttpPort(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        definitions.apply(seed("canic"), false);
        try (var occupied = new java.net.ServerSocket(0)) {
            var output = temporary.resolve("export.yaml");
            var process = new ProcessBuilder("bin/core", "club:export", "canic");
            process.environment().put("SPRING_PROFILES_ACTIVE", "test");
            process.environment().put("MONGODB_TEST_URI", MONGO.getReplicaSetUrl("agilityhub_test"));
            process.environment().put("SERVER_PORT", Integer.toString(occupied.getLocalPort()));
            process.redirectOutput(output.toFile()); process.redirectError(temporary.resolve("stderr.txt").toFile());
            var child = process.start();
            try {
                assertThat(child.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).as(java.nio.file.Files.readString(temporary.resolve("stderr.txt"))).isZero();
                assertThat(codec.read(output)).isEqualTo(codec.validate(definitions.export("canic")));
                assertThat(java.nio.file.Files.readString(output)).doesNotContain("Tomcat", "Spring Boot");
            } finally { if (child.isAlive()) { child.destroyForcibly(); } }
        }
    }

}

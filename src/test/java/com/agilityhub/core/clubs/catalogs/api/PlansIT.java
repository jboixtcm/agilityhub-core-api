package com.agilityhub.core.clubs.catalogs.api;

import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.DomainEventRecord;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "shared.scheduling.enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PlansIT extends AbstractIntegrationTest {
    static final String CLUB = "canic", OTHER = "offer-b";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired PlanService plans;
    @Autowired PriceService prices;
    @Autowired PriceResolver resolver;
    @Autowired OfferUsage usage;
    @Autowired MongoTransactionManager transactions;
    @LocalServerPort int port;
    String publicKey;

    @BeforeEach void seed() {
        TenantContext.clear(); clock.setInstant(Instant.parse("2026-01-15T12:00:00Z"));
        for (String collection : List.of("clubs", "parameters", "plans", "prices", "members", "pack_balances", "invoice_lines", "invoices", "collections",
                "audit_entries", "domain_events", "catalog_write_locks", "memberships", "accounts", "impersonation_grants")) { mongo.remove(new Query(), collection); }
        publicKey = UUID.randomUUID().toString();
        for (String id : List.of(CLUB, OTHER)) {
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id + ".example.test"));
            tree.set("modules", mapper.valueToTree(List.of("BILLING", "PACKS", "SINGLE_CLASS")));
            tree.put("publicApiKeyHash", PublicClubAccess.digest(id.equals(CLUB) ? publicKey : UUID.randomUUID().toString()));
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
        }
        hosts.invalidate();
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").with(jwt().jwt(j -> j.subject("offer-admin")
                .claim("name", "Example Offer Admin").claim("clubId", club)).authorities(() -> "ROLE_" + role)));
    }
    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return call(request, CLUB, "ADMIN"); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(body));
    }
    JsonNode json(ResultActions response, int status) throws Exception {
        return mapper.readTree(response.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    Map<String, Object> planInput(String code, String type) {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("code", code, "type", type, "name", Map.of("ca", "Abonat " + code, "es", "Abonado " + code, "en", "Member " + code)));
        if (type.equals("PACK")) { data.put("pack", Map.of("sessions", 10, "validityMonths", 3)); }
        if (type.equals("SINGLE_CLASS")) { data.put("singleClass", Map.of("chargeMode", "PAY_TO_BOOK")); }
        return data;
    }
    String plan(String code, String type) throws Exception { return json(admin(body(post("/api/v1/plans"), planInput(code, type))), 201).path("id").asText(); }
    Map<String, Object> priceInput(String plan, long amount, String from, String to) {
        var data = new LinkedHashMap<String, Object>(Map.of("planId", plan, "concept", "MONTHLY_FEE", "amount", Map.of("amountMinor", amount, "currency", "EUR"), "taxPercent", 0, "validFrom", from));
        if (to != null) { data.put("validTo", to); } return data;
    }
    JsonNode price(String plan, long amount, String from, String to) throws Exception {
        return json(admin(body(post("/api/v1/prices"), priceInput(plan, amount, from, to))), 201);
    }
    JsonNode updatePlan(String id, Object patch) throws Exception { return json(admin(body(patch("/api/v1/plans/" + id), patch)), 200); }
    void modules(Set<Module> modules) {
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
    }
    void error(ResultActions result, int status, String code) throws Exception { result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code)); }

    @Test @AuditCovers(AuditAction.CATALOG_CHANGED)
    void T_05_02_supersessionResolvesBothBoundariesAndEmitsTwoAtomicChanges() throws Exception {
        String plan = plan("MONTHLY", "MONTHLY");
        String old = price(plan, 6000, "2026-01-01", null).at("/price/id").asText();
        var created = price(plan, 6500, "2027-01-01", null); String next = created.at("/price/id").asText();
        assertThat(created.path("closedPriceId").asText()).isEqualTo(old);
        try (var scope = TenantContext.open(CLUB)) {
            assertThat(prices.get(old).validTo()).isEqualTo(LocalDate.parse("2026-12-31"));
            assertThat(resolver.current(plan, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-12-31")).orElseThrow().amount().amountMinor()).isEqualTo(6000);
            assertThat(resolver.current(plan, PriceConcept.MONTHLY_FEE, LocalDate.parse("2027-01-01")).orElseThrow().id()).isEqualTo(next);
            assertThat(resolver.current(plan, PriceConcept.PACK, LocalDate.parse("2027-01-01"))).isEmpty();
        }
        var stored = mongo.findById(old, Document.class, "prices"); assertThat(stored.get("validFrom")).isEqualTo("2026-01-01");
        error(admin(body(post("/api/v1/prices"), priceInput(plan, 6400, "2026-06-01", "2026-12-31"))), 409, "PRICE_OVERLAP");
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(4).allMatch(entry -> entry.action() == AuditAction.CATALOG_CHANGED);
        assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(4);
        var events = mongo.find(Query.query(Criteria.where("type").is("PriceChanged")), Document.class, "domain_events");
        assertThat(events.stream().map(event -> ((Document) event.get("payload")).getString("action"))).containsExactlyInAnyOrder("CREATED", "CLOSED", "CREATED");
        var detail = json(admin(get("/api/v1/plans/" + plan)), 200);
        assertThat(detail.path("prices")).hasSize(2); assertThat(detail.path("currentPrices")).hasSize(1);
        assertThat(detail.path("billingMode").asText()).isEqualTo("MONTHLY_FEE");
        assertThat(json(admin(get("/api/v1/prices").param("planId", plan).param("concept", "MAINTENANCE_FEE")), 200).path("items")).isEmpty();
    }
    @Test void T_05_03_invoicedTermsCannotBeRepricedOrClosedBeforeTheLastBilledPeriod() throws Exception {
        String plan = plan("LOCKED", "MONTHLY"), id = price(plan, 6000, "2026-01-01", null).at("/price/id").asText();
        clock.setInstant(Instant.parse("2026-09-15T12:00:00Z"));
        var invoice = new Document("_id", "invoice-example").append("clubId", CLUB).append("period", "2026-09")
                .append("lines", List.of(new Document("priceId", id).append("amount", new Document("amountMinor", 6000))));
        mongo.insert(invoice, "invoices");
        for (var patch : List.of(Map.of("amount", Map.of("amountMinor", 6500, "currency", "EUR"), "version", 0),
                Map.of("taxPercent", 21, "version", 0), Map.of("validFrom", "2026-09-01", "version", 0), Map.of("concept", "PACK", "version", 0),
                Map.of("validTo", "2026-09-29", "version", 0), Map.of("validTo", "2026-09-14", "version", 0))) {
            error(admin(body(patch("/api/v1/prices/" + id), patch)), 409, "PRICE_LOCKED");
        }
        error(admin(body(post("/api/v1/prices"), priceInput(plan, 6500, "2026-08-01", null))), 409, "PRICE_LOCKED");
        error(admin(body(post("/api/v1/prices"), priceInput(plan, 6500, "2026-09-30", null))), 409, "PRICE_LOCKED");
        var closed = json(admin(body(patch("/api/v1/prices/" + id), Map.of("validTo", "2026-10-31", "version", 0))), 200);
        assertThat(closed.path("validTo").asText()).isEqualTo("2026-10-31");
        var reopen = new LinkedHashMap<String, Object>(); reopen.put("validTo", null); reopen.put("version", 1);
        assertThat(json(admin(body(patch("/api/v1/prices/" + id), reopen)), 200).has("validTo")).isFalse();
        error(admin(delete("/api/v1/prices/" + id)), 409, "PRICE_LOCKED");
        assertThat(mongo.findById("invoice-example", Document.class, "invoices")).isEqualTo(invoice);
        try (var scope = TenantContext.open(CLUB)) { assertThat(usage.price(id).lastBilled()).isEqualTo(LocalDate.parse("2026-09-30")); }
    }
    @Test void T_05_03_scheduledPricesCanBeCorrectedDeletedAndNeverOverlap() throws Exception {
        String plan = plan("SCHEDULED", "MONTHLY");
        String id = price(plan, 6000, "2026-02-01", "2026-03-31").at("/price/id").asText();
        var corrected = json(admin(body(patch("/api/v1/prices/" + id), Map.of("amount", Map.of("amountMinor", 6100, "currency", "EUR"), "taxPercent", 5.25, "validFrom", "2026-02-02", "version", 0))), 200);
        assertThat(corrected.at("/amount/amountMinor").asInt()).isEqualTo(6100); assertThat(corrected.path("locked").asBoolean()).isFalse();
        price(plan, 6200, "2026-04-01", null);
        error(admin(body(patch("/api/v1/prices/" + id), Map.of("validTo", "2026-04-01", "version", 1))), 409, "PRICE_OVERLAP");
        error(admin(body(patch("/api/v1/prices/" + id), Map.of("validTo", "2026-03-01", "version", 0))), 409, "STALE_VERSION");
        admin(delete("/api/v1/prices/" + id)).andExpect(status().isNoContent());
        assertThat(json(admin(get("/api/v1/prices").param("planId", plan)), 200).path("items")).hasSize(1);
    }
    @Test void T_05_04_planTermsEntryFeesAndConfiguredProposals() throws Exception {
        String id = plan("THERAPY", "MONTHLY"), pack = plan("PACK", "PACK");
        var therapy = updatePlan(id, Map.of("billingMode", "MAINTENANCE", "entryFee", Map.of("mode", "PERCENT", "percent", 50), "version", 0,
                "texts", Map.of("description", Map.of("ca", "Example description"), "priceLabel", Map.of("ca", "Cost segons cada cas", "es", "Coste según cada caso"))));
        assertThat(therapy.path("billingMode").asText()).isEqualTo("MAINTENANCE"); assertThat(therapy.at("/entryFeeAmount/amountMinor").asInt()).isEqualTo(5000);
        var partial = updatePlan(id, Map.of("texts", Map.of("offerLabel", Map.of("ca", "Example offer")), "version", 1));
        assertThat(partial.at("/texts/description").asText()).isEqualTo("Example description");
        var maintenance = priceInput(id, 1000, "2026-01-01", null); maintenance.put("concept", "MAINTENANCE_FEE");
        json(admin(body(post("/api/v1/prices"), maintenance)), 201);
        assertThat(json(admin(get("/api/v1/plans/" + id)), 200).path("priceLine").asText()).isEqualTo("Cost segons cada cas");
        try (var scope = TenantContext.open(CLUB)) {
            assertThat(plans.familySuggestion(new Money(6000, "EUR"), 2).amountMinor()).isEqualTo(9000);
            assertThat(plans.forPlanChange(plans.get(pack), plans.get(id), 10, false).amount().amountMinor()).isEqualTo(3000);
            assertThat(plans.forPlanChange(plans.get(pack), plans.get(id), 10, true).reason()).isNull();
        }
        var amount = updatePlan(id, Map.of("entryFee", Map.of("mode", "AMOUNT", "amount", Map.of("amountMinor", 2500, "currency", "EUR")), "version", 2));
        assertThat(amount.at("/entryFeeAmount/amountMinor").asInt()).isEqualTo(2500);
        assertThat(updatePlan(id, Map.of("entryFee", Map.of("mode", "NONE"), "version", 3)).at("/entryFeeAmount/amountMinor").asInt()).isZero();
    }
    @Test void T_05_17_moduleBranchesKeepExistingPlansAndHidePrices() throws Exception {
        String pack = plan("PACK", "PACK"), monthly = plan("MONTHLY", "MONTHLY"), single = plan("SINGLE", "SINGLE_CLASS");
        assertThat(json(admin(get("/api/v1/plans/" + single)), 200).at("/singleClass/cancelPolicy").asText()).isEqualTo("REFUND");
        price(monthly, 6000, "2026-01-01", null);
        modules(Set.of(Module.BILLING));
        for (String type : List.of("PACK", "SINGLE_CLASS")) {
            error(admin(body(post("/api/v1/plans"), planInput("NEW", type))), 400, "VALIDATION_ERROR");
            admin(body(post("/api/v1/plans"), planInput("NEW", type))).andExpect(jsonPath("$.details.fieldErrors[0].code").value("MODULE_DISABLED"));
        }
        updatePlan(pack, Map.of("conditions", Map.of("ca", "Existing pack terms"), "version", 0));
        assertThat(json(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", publicKey)), 200).path("plans")).hasSize(1);
        modules(Set.of());
        for (var request : List.of(get("/api/v1/prices").param("planId", monthly), body(post("/api/v1/prices"), priceInput(monthly, 7000, "2027-01-01", null)),
                body(patch("/api/v1/prices/missing"), Map.of("version", 0)), delete("/api/v1/prices/missing"))) {
            error(admin(request), 404, "MODULE_DISABLED");
        }
        var listed = json(admin(get("/api/v1/plans")), 200); assertThat(listed.path("items")).hasSize(3);
        assertThat(listed.toString()).doesNotContain("currentPrices", "entryFee", "priceLine");
        var publicPlans = json(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", publicKey)), 200);
        assertThat(publicPlans.toString()).doesNotContain("currentPrices", "entryFee", "priceLine");
        try (var scope = TenantContext.open(CLUB)) { assertThat(plans.entryFee(plans.get(monthly))).isNull(); }
    }
    @Test void T_05_18_publicKeyLocaleProjectionVisibilityAndCurlFixture() throws Exception {
        String publicPlan = plan("MONTHLY", "MONTHLY"), hidden = plan("HIDDEN", "MONTHLY"), inactive = plan("INACTIVE", "MONTHLY");
        price(publicPlan, 6000, "2026-01-01", null); price(publicPlan, 6500, "2027-01-01", null);
        updatePlan(hidden, Map.of("showOnWeb", false, "version", 0)); updatePlan(inactive, Map.of("active", false, "version", 0));
        updatePlan(publicPlan, Map.of("showOnSignup", false, "conditions", Map.of("ca", "Condicions", "es", "Condiciones"),
                "texts", Map.of("description", Map.of("ca", "Descripció", "es", "Descripción")), "version", 0));
        for (String key : List.of("", "wrong-key")) { error(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", key)), 403, "INVALID_API_KEY"); }
        error(mvc.perform(get("/api/v1/public/canic/plans")), 403, "INVALID_API_KEY");
        error(mvc.perform(get("/api/v1/public/unknown/plans").header("X-Api-Key", publicKey)), 404, "CLUB_NOT_FOUND");
        error(mvc.perform(get("/api/v1/public/offer-b/plans").header("X-Api-Key", publicKey)), 403, "INVALID_API_KEY");
        var response = mvc.perform(get("/api/v1/public/canic/plans").header("Host", "unrelated.example.test").header("X-Api-Key", publicKey).header("Accept-Language", "es-ES,ca;q=0.5"))
                .andExpect(header().string("Cache-Control", "public, max-age=300")).andExpect(header().string("Content-Language", "es"));
        var result = json(response, 200); assertThat(result.path("plans")).hasSize(1);
        assertThat(result.at("/plans/0/name").asText()).isEqualTo("Abonado MONTHLY");
        assertThat(result.at("/plans/0/nameI18n/ca").asText()).isEqualTo("Abonat MONTHLY");
        assertThat(result.at("/plans/0/texts/descriptionI18n/es").asText()).isEqualTo("Descripción");
        assertThat(result.at("/plans/0/currentPrices/0/amount/amountMinor").asInt()).isEqualTo(6000);
        assertThat(result.toString()).doesNotContain("memberId", "accountId", "usage", "lastChange", "Hash", "version");
        var club = (ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); club.set("locales", mapper.valueToTree(List.of("ca", "es")));
        clubs.save(mapper.convertValue(club, Club.class)); configs.invalidate(CLUB);
        for (String language : List.of("en", "!invalid", "*;q=0.5")) {
            assertThat(json(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", publicKey).header("Accept-Language", language)), 200).at("/plans/0/name").asText()).isEqualTo("Abonat MONTHLY");
        }
        Process curl = new ProcessBuilder("curl", "-fsS", "-H", "X-Api-Key: " + publicKey, "-H", "Accept-Language: es", "http://localhost:" + port + "/api/v1/public/canic/plans").redirectErrorStream(true).start();
        String output = new String(curl.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(curl.waitFor()).isZero(); assertThat(mapper.readTree(output).at("/plans/0/name").asText()).isEqualTo("Abonado MONTHLY");
        System.out.println("E2-T05 curl /api/v1/public/canic/plans (fictional fixture, key omitted):\n" + output);
        club.put("status", "SUSPENDED"); club.put("version", clubs.findById(CLUB).orElseThrow().version()); clubs.save(mapper.convertValue(club, Club.class));
        error(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", publicKey)), 403, "CLUB_SUSPENDED");
    }
    @Test void T_05_20_concurrentPriceCreationAndPlanEditsHaveOneWinner() throws Exception {
        String plan = plan("CONCURRENT", "MONTHLY"); price(plan, 6000, "2026-01-01", null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2); var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) { futures.add(executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS);
                var response = admin(body(post("/api/v1/prices"), priceInput(plan, 6500, "2027-01-01", null))).andReturn().getResponse();
                if (response.getStatus() == 409) { assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("PRICE_OVERLAP"); }
                return response.getStatus(); })); }
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
            futures.clear();
            for (int dogs : List.of(2, 3)) { futures.add(executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS);
                return admin(body(patch("/api/v1/plans/" + plan), Map.of("dogsIncluded", dogs, "version", 0))).andReturn().getResponse().getStatus(); })); }
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(5); assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(5);
        try (var scope = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                prices.create(priceInput(plan, 7000, "2028-01-01", null)); throw new IllegalStateException("Rollback fixture");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(prices.list(plan, null)).hasSize(2); assertThat(prices.list(plan, null).getLast().validTo()).isNull();
        }
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(5); assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(5);
    }
    @Test void T_05_17_planUsageBlocksTypeChangesAndDeletionButAllowsDeactivation() throws Exception {
        String id = plan("USED", "MONTHLY"); String price = price(id, 6000, "2026-01-01", null).at("/price/id").asText();
        for (String collection : List.of("members", "pack_balances", "invoice_lines", "collections")) {
            mongo.insert(new Document("_id", "other-ref").append("clubId", OTHER).append("planId", id).append("priceId", price), collection);
        }
        try (var scope = TenantContext.open(CLUB)) { assertThat(usage.plan(id).values()).allMatch(count -> count == 0); }
        for (String collection : List.of("members", "pack_balances", "invoice_lines", "collections")) {
            mongo.insert(new Document("_id", "own-ref").append("clubId", CLUB).append("priceId", price), collection);
            error(admin(delete("/api/v1/plans/" + id)), 409, "PLAN_IN_USE");
            error(admin(body(patch("/api/v1/plans/" + id), Map.of("type", "PACK", "pack", Map.of("sessions", 10, "validityMonths", 3), "version", 0))), 409, "PLAN_IN_USE");
            mongo.remove(Query.query(Criteria.where("_id").is("own-ref")), collection);
        }
        mongo.insert(new Document("_id", "member-own").append("clubId", CLUB).append("planId", id).append("status", "LEFT"), "members");
        var disabled = updatePlan(id, Map.of("active", false, "version", 0));
        assertThat(disabled.at("/warnings/members").asInt()).isEqualTo(1);
        updatePlan(id, Map.of("active", true, "version", 1));
        assertThat(mongo.findById("member-own", Document.class, "members").getString("planId")).isEqualTo(id);
    }
    @Test void T_05_06_planOrderCodeUniquenessTypeChangesAndUnusedDeletion() throws Exception {
        String first = plan("FIRST", "MONTHLY"), second = plan("SECOND", "PACK");
        error(admin(body(post("/api/v1/plans"), planInput("FIRST", "MONTHLY"))), 409, "DUPLICATE_NAME");
        error(admin(body(patch("/api/v1/plans/" + second), Map.of("code", "FIRST", "version", 0))), 409, "DUPLICATE_NAME");
        for (List<String> ids : List.of(List.of(first), List.of(first, first), List.of(first, "foreign"))) {
            error(admin(body(put("/api/v1/plans/order"), Map.of("planIds", ids))), 422, "ORDER_INCOMPLETE");
        }
        var ordered = json(admin(body(put("/api/v1/plans/order"), Map.of("planIds", List.of(second, first)))), 200);
        assertThat(ordered.path("items").findValuesAsText("id")).containsExactly(second, first);
        assertThat(ordered.at("/items/1/order").asInt()).isEqualTo(10);
        admin(body(put("/api/v1/plans/order"), Map.of("planIds", List.of(second, first)))).andExpect(status().isOk());
        var changed = updatePlan(second, Map.of("type", "MONTHLY", "billingMode", "MAINTENANCE", "version", 1));
        assertThat(changed.has("pack")).isFalse();
        assertThat(changed.path("billingMode").asText()).isEqualTo("MAINTENANCE");
        var single = updatePlan(second, Map.of("type", "SINGLE_CLASS", "singleClass", Map.of("chargeMode", "CHARGE_ON_ATTENDANCE", "cancelPolicy", "CREDIT"), "version", 2));
        assertThat(single.has("billingMode")).isFalse();
        updatePlan(second, Map.of("type", "PACK", "pack", Map.of("sessions", 6, "validityMonths", 2), "version", 3));
        assertThat(mongo.findById(second, Document.class, "plans").containsKey("singleClass")).isFalse();
        price(first, 6000, "2027-01-01", null);
        admin(delete("/api/v1/plans/" + first)).andExpect(status().isNoContent());
        assertThat(mongo.findAll(Price.class)).isEmpty();
        error(admin(get("/api/v1/plans/" + first)), 404, "NOT_FOUND");
    }
    @Test void T_05_19_everyPlanAndPriceEndpointEnforcesTenantRolesAndInactiveScope() throws Exception {
        String plan = plan("ROLE", "MONTHLY"), price = price(plan, 6000, "2027-01-01", null).at("/price/id").asText();
        var requests = List.of(body(post("/api/v1/plans"), planInput("NEW", "MONTHLY")), get("/api/v1/plans/" + plan),
                body(patch("/api/v1/plans/" + plan), Map.of("version", 0)), delete("/api/v1/plans/" + plan),
                body(put("/api/v1/plans/order"), Map.of("planIds", List.of(plan))), get("/api/v1/prices").param("planId", plan),
                body(post("/api/v1/prices"), priceInput(plan, 6500, "2028-01-01", null)), body(patch("/api/v1/prices/" + price), Map.of("version", 0)), delete("/api/v1/prices/" + price));
        for (var request : requests) {
            mvc.perform(request.header("Host", CLUB + ".example.test")).andExpect(status().isUnauthorized());
            for (String role : List.of("MEMBER", "INSTRUCTOR", "AGILITYHUB_ADMIN")) { call(request, CLUB, role).andExpect(status().isForbidden()); }
            error(mvc.perform(request.header("Host", CLUB + ".example.test").with(jwt().jwt(j -> j.claim("clubId", OTHER)).authorities(() -> "ROLE_ADMIN"))), 403, "TENANT_MISMATCH");
        }
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            var list = json(call(get("/api/v1/plans"), CLUB, role), 200);
            assertThat(list.toString()).doesNotContain("I18n", "usage", "lastChange", "actorName");
            call(get("/api/v1/plans").param("includeInactive", "true"), CLUB, role).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/plans")).andExpect(status().isUnauthorized());
        call(get("/api/v1/plans"), CLUB, "AGILITYHUB_ADMIN").andExpect(status().isForbidden());
        assertThat(json(call(get("/api/v1/plans"), OTHER, "ADMIN"), 200).path("items")).isEmpty();
        for (var request : List.of(get("/api/v1/plans/" + plan), body(patch("/api/v1/plans/" + plan), Map.of("version", 0)), delete("/api/v1/plans/" + plan),
                get("/api/v1/prices").param("planId", plan), body(post("/api/v1/prices"), priceInput(plan, 6500, "2028-01-01", null)),
                body(patch("/api/v1/prices/" + price), Map.of("version", 0)), delete("/api/v1/prices/" + price))) {
            error(call(request, OTHER, "ADMIN"), 404, "NOT_FOUND");
        }
        error(call(body(put("/api/v1/plans/order"), Map.of("planIds", List.of(plan))), OTHER, "ADMIN"), 422, "ORDER_INCOMPLETE");
        call(body(post("/api/v1/plans"), planInput("ROLE", "MONTHLY")), OTHER, "ADMIN").andExpect(status().isCreated());
        try (var scope = TenantContext.open(OTHER)) { assertThat(resolver.current(plan, PriceConcept.MONTHLY_FEE, LocalDate.parse("2027-01-01"))).isEmpty(); }
        // A persisted impersonation grant exercises the actual ADMIN guards, not an invalid token shortcut.
        for (String account : List.of("actor", "target")) {
            mongo.save(new com.agilityhub.core.identity.persistence.Account(account, account + "@example.test", "Example Person", "ca", null,
                    Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,
                    new com.agilityhub.core.identity.persistence.Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
            var role = account.equals("actor") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(account + "-membership", account, CLUB, account + "-member", Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
        }
        mongo.save(new Document("_id", "target-member").append("clubId", CLUB).append("accountId", "target").append("status", "ACTIVE"), "members");
        mongo.save(new com.agilityhub.core.identity.persistence.ImpersonationGrant("grant", CLUB, "actor", "target-member", "target", "Example support request", clock.instant().plusSeconds(300), null));
        for (var request : requests) {
            mvc.perform(request.header("Host", CLUB + ".example.test").with(jwt().jwt(j -> j.subject("target").claim("clubId", CLUB).claim("imp", true)
                    .claim("jti", "grant").claim("actorAccountId", "actor").claim("impersonatedMemberId", "target-member")).authorities(() -> "ROLE_MEMBER")))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void T_05_08_planValidationRejectsInvalidNestedTermsAndLocalesWithoutWriting() throws Exception {
        String id = plan("VALIDATION", "MONTHLY");
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("name", Map.of("es", "No default")), Map.<String, Object>of("name", Map.of("ca", "Default", "fr", "Disabled")),
                Map.<String, Object>of("name", Map.of("ca", " ")), Map.<String, Object>of("name", Map.of("ca", "a".repeat(61))),
                Map.<String, Object>of("entryFee", Map.of()), Map.<String, Object>of("entryFee", Map.of("mode", "PERCENT", "percent", 0)),
                Map.<String, Object>of("entryFee", Map.of("mode", "PERCENT", "percent", 101)), Map.<String, Object>of("entryFee", Map.of("mode", "PERCENT")),
                Map.<String, Object>of("entryFee", Map.of("mode", "AMOUNT")), Map.<String, Object>of("entryFee", Map.of("mode", "AMOUNT", "amount", Map.of("amountMinor", -1, "currency", "EUR"))),
                Map.<String, Object>of("entryFee", Map.of("mode", "NONE", "percent", 50)), Map.<String, Object>of("pack", Map.of("sessions", 10, "validityMonths", 3)),
                Map.<String, Object>of("singleClass", Map.of("chargeMode", "PAY_TO_BOOK")), Map.<String, Object>of("conditions", Map.of("ca", "a".repeat(201))),
                Map.<String, Object>of("texts", Map.of("description", Map.of("ca", "a".repeat(1001)))), Map.<String, Object>of("dogsIncluded", 0), Map.<String, Object>of("order", -1))) {
            var patch = new LinkedHashMap<>(invalid); patch.put("version", 0);
            error(admin(body(patch("/api/v1/plans/" + id), patch)), 400, "VALIDATION_ERROR");
        }
        error(admin(body(patch("/api/v1/plans/" + id), Map.of("entryFee", Map.of("mode", "AMOUNT", "amount", Map.of("amountMinor", 1, "currency", "USD")), "version", 0))), 422, "CURRENCY_MISMATCH");
        for (Map<String, Object> settings : List.of(Map.<String, Object>of(), Map.<String, Object>of("sessions", 0, "validityMonths", 1), Map.<String, Object>of("sessions", 100, "validityMonths", 1), Map.<String, Object>of("sessions", 6, "validityMonths", 0), Map.<String, Object>of("sessions", 6, "validityMonths", 25))) {
            var input = planInput("BAD_PACK", "PACK"); input.put("pack", settings);
            error(admin(body(post("/api/v1/plans"), input)), 400, "VALIDATION_ERROR");
        }
        var input = planInput("BAD_SINGLE", "SINGLE_CLASS"); input.put("singleClass", Map.of());
        error(admin(body(post("/api/v1/plans"), input)), 400, "VALIDATION_ERROR");
        input = planInput("BAD_PACK", "PACK"); input.put("billingMode", "MAINTENANCE");
        error(admin(body(post("/api/v1/plans"), input)), 400, "VALIDATION_ERROR");
        admin(body(post("/api/v1/plans"), Map.of())).andExpect(status().isBadRequest());
        admin(body(patch("/api/v1/plans/" + id), Map.of())).andExpect(status().isBadRequest());
        admin(body(put("/api/v1/plans/order"), Map.of())).andExpect(status().isBadRequest());
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(1); assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(1);
    }
    @Test void T_05_03_priceValidationAndUnknownBilledPeriodsFailWithoutMutations() throws Exception {
        String plan = plan("VALIDATION", "MONTHLY");
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("taxPercent", 0.001), Map.<String, Object>of("taxPercent", 101),
                Map.<String, Object>of("amount", Map.of("amountMinor", -1, "currency", "EUR")), Map.<String, Object>of("validTo", "2026-01-01"), Map.<String, Object>of("concept", "PACK"))) {
            var input = priceInput(plan, 6000, "2026-02-01", null); input.putAll(invalid);
            error(admin(body(post("/api/v1/prices"), input)), 400, "VALIDATION_ERROR");
        }
        var wrongCurrency = priceInput(plan, 6000, "2026-02-01", null); wrongCurrency.put("amount", Map.of("amountMinor", 6000, "currency", "USD"));
        error(admin(body(post("/api/v1/prices"), wrongCurrency)), 422, "CURRENCY_MISMATCH");
        admin(get("/api/v1/prices")).andExpect(status().isBadRequest()); admin(body(post("/api/v1/prices"), Map.of())).andExpect(status().isBadRequest());
        String id = price(plan, 6000, "2026-02-01", null).at("/price/id").asText();
        for (Object to : List.of(12, "not-a-date", "2026-01-01")) {
            error(admin(body(patch("/api/v1/prices/" + id), Map.of("validTo", to, "version", 0))), 400, "VALIDATION_ERROR");
        }
        admin(body(patch("/api/v1/prices/" + id), Map.of())).andExpect(status().isBadRequest());
        mongo.insert(new Document("_id", "line-unknown").append("clubId", CLUB).append("priceId", id), "invoice_lines");
        error(admin(body(post("/api/v1/prices"), priceInput(plan, 6500, "2027-01-01", null))), 409, "PRICE_LOCKED");
        error(admin(delete("/api/v1/prices/" + id)), 409, "PRICE_LOCKED");
        assertThat(mongo.findAll(AuditEntry.class)).hasSize(2); assertThat(mongo.findAll(DomainEventRecord.class)).hasSize(2);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("line-unknown")), new Update().set("periodTo", "2026-03-31"), "invoice_lines");
        try (var scope = TenantContext.open(CLUB)) { assertThat(usage.price(id).lastBilled()).isEqualTo(LocalDate.parse("2026-03-31")); }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("line-unknown")), new Update().set("periodTo", "invalid"), "invoice_lines");
        try (var scope = TenantContext.open(CLUB)) { assertThat(usage.price(id).periodKnown()).isFalse(); }
    }
    @Test void T_05_03_priceTodayUsesClubTimezoneAtMidnight() throws Exception {
        String plan = plan("MIDNIGHT", "MONTHLY"), id = price(plan, 6000, "2026-02-01", null).at("/price/id").asText();
        clock.setInstant(Instant.parse("2026-01-31T23:30:00Z"));
        var item = json(admin(get("/api/v1/prices").param("planId", plan)), 200).at("/items/0");
        assertThat(item.path("status").asText()).isEqualTo("CURRENT"); assertThat(item.path("locked").asBoolean()).isTrue();
        error(admin(body(post("/api/v1/prices"), priceInput(plan, 6500, "2026-01-31", "2026-01-31"))), 409, "PRICE_LOCKED");
        assertThat(item.path("id").asText()).isEqualTo(id);
    }
    @Test void T_05_07_packAndSingleClassPricesExposeOneOffTermsAndPublicLines() throws Exception {
        for (String type : List.of("PACK", "SINGLE_CLASS")) {
            String plan = plan(type, type); var input = priceInput(plan, 5000, "2026-01-01", null); input.put("concept", type);
            var created = json(admin(body(post("/api/v1/prices"), input)), 201);
            assertThat(created.at("/price/periodicity").asText()).isEqualTo("ONE_OFF");
            String id = created.at("/price/id").asText();
            // Repeating the current values is allowed, including numerically equal tax scales.
            var same = new LinkedHashMap<String, Object>(Map.of("amount", Map.of("amountMinor", 5000, "currency", "EUR"),
                    "concept", type, "validFrom", "2026-01-01", "taxPercent", new java.math.BigDecimal("0.00"), "version", 0));
            admin(body(patch("/api/v1/prices/" + id), same)).andExpect(status().isOk());
        }
        var result = json(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", publicKey).header("Accept-Language", "es;q=0,en;q=1")), 200);
        assertThat(result.at("/plans/0/priceLine").asText()).endsWith(" · 3 months");
        assertThat(result.at("/plans/1/priceLine").asText()).endsWith("/class");
        assertThat(result.at("/plans/0/pack/sessions").asInt()).isEqualTo(10);
        assertThat(result.at("/plans/1/singleClass/chargeMode").asText()).isEqualTo("PAY_TO_BOOK");
        var club = (ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); club.remove("publicApiKeyHash"); clubs.save(mapper.convertValue(club, Club.class));
        error(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", publicKey)), 403, "INVALID_API_KEY");
    }

    @Test void T_05_17_unusedTypeChangesKeepPriceHistoryWithoutOfferingIncompatibleConcepts() throws Exception {
        String plan = plan("CHANGE", "MONTHLY"); String price = price(plan, 6000, "2026-01-01", null).at("/price/id").asText();
        var changed = updatePlan(plan, Map.of("type", "PACK", "pack", Map.of("sessions", 6, "validityMonths", 3), "version", 0));
        assertThat(changed.path("currentPrices")).isEmpty(); assertThat(changed.path("prices")).hasSize(1);
        admin(body(patch("/api/v1/prices/" + price), Map.of("validTo", "2026-01-31", "version", 0))).andExpect(status().isOk());
        var result = json(mvc.perform(get("/api/v1/public/canic/plans").header("X-Api-Key", publicKey)), 200);
        assertThat(result.at("/plans/0/currentPrices")).isEmpty();
        var wrong = priceInput(plan, 6500, "2026-02-01", null);
        error(admin(body(post("/api/v1/prices"), wrong)), 400, "VALIDATION_ERROR");
    }

}

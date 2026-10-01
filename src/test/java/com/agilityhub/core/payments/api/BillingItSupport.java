package com.agilityhub.core.payments.api;

import com.agilityhub.core.clubs.catalogs.domain.OfferTerms;
import com.agilityhub.core.clubs.catalogs.persistence.Plan;
import com.agilityhub.core.clubs.catalogs.persistence.Price;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E8-T02's fictional census for the S12 monthly cycle (fictional people, `@example.test`, `ES00` test IBANs), as of Tuesday
 * 25-08-2026 10:00 in Madrid: the club `bill-a` (SEPA_XML with a fictional creditor + MANUAL; STRIPE off), the plans of the
 * Cànic's kinds and their prices, and members ordered by last name — one per incident code of R-12-07, a family group, a
 * cash member by half-years, a pack, the maintenance plan and single classes. `bill-b` is the other tenant.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
abstract class BillingItSupport extends AbstractIntegrationTest {
    static final String CLUB = "bill-a", OTHER = "bill-b", HOST = "bill-a.example.test", OTHER_HOST = "bill-b.example.test";
    static final String IBAN = "ES0000000000000000004321";
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");
    static final List<String> DATA = List.of("members", "family_groups", "dogs", "plans", "prices", "parameters", "invoices", "collections", "remittances",
            "billing_runs", "billing_simulations", "billing_locks", "pending_charges", "audit_entries", "idempotency_records", "bookings", "notifications",
            "memberships", "class_sessions", "attendances", "domain_events");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired OutboxDispatcher outbox;

    @BeforeEach void billingCensus() {
        clock.setInstant(NOW);
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(Query.query(Criteria.where("_id").regex("^bill-")), "accounts");
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), providers(true, true, false));
        club(OTHER, OTHER_HOST, "Europe/Madrid", List.of(Module.values()), providers(true, true, false));
        hosts.invalidate();
        plan("abonat", OfferTerms.PlanType.MONTHLY, null, "Abonat", 1); price("abonat", OfferTerms.PriceConcept.MONTHLY_FEE, 6000, "EUR");
        plan("abonat2", OfferTerms.PlanType.MONTHLY, null, "Abonat 2 gossos", 2); price("abonat2", OfferTerms.PriceConcept.MONTHLY_FEE, 9000, "EUR");
        plan("terapia", OfferTerms.PlanType.MONTHLY, OfferTerms.BillingMode.MAINTENANCE, "Teràpia", 1);
        price("terapia", OfferTerms.PriceConcept.MAINTENANCE_FEE, 3000, "EUR");
        plan("pack10", OfferTerms.PlanType.PACK, null, "Pack 10", 1);
        plan("single", OfferTerms.PlanType.SINGLE_CLASS, null, "Classe individual", 1); price("single", OfferTerms.PriceConcept.SINGLE_CLASS, 1200, "EUR");
        plan("unpriced", OfferTerms.PlanType.MONTHLY, null, "Sense tarifa", 1);
        plan("dollars", OfferTerms.PlanType.MONTHLY, null, "Dòlars", 1); price("dollars", OfferTerms.PriceConcept.MONTHLY_FEE, 6000, "USD");
        // R-12-07's six incidents (Bosch, Camps, Font, Riera, Sala, Soler), then the billed members.
        member("bosch", 201, "Bet", "Bosch", sepa(null), "abonat");
        member("camps", 202, "Carles", "Camps", sepa(IBAN), null);
        member("font", 203, "Fina", "Font", sepa(IBAN), "dollars");
        member("riera", 204, "Pau", "Riera", sepa(IBAN), "unpriced");
        member("sala", 205, "Sara", "Sala", card(false), "abonat");
        member("soler", 206, "Pere", "Soler", card(true), "abonat");
        member("mas", 207, "Mia", "Mas", sepa(IBAN), "single");
        member("puig", 208, "Eva", "Puig", sepa(IBAN), "abonat");
        member("roca", 209, "Marc", "Roca", sepa(IBAN), "pack10");
        member("serra-joan", 210, "Joan Antoni", "Serra", sepa(IBAN), "abonat");
        member("serra-laura", 211, "Laura", "Serra", sepa(IBAN), "abonat2");
        member("torres", 212, "Teresa", "Torres", sepa(IBAN), "terapia");
        member("vidal", 213, "Rosa", "Vidal", cash(), "abonat");
        member("vila", 214, "Joan", "Vila", cash(), "abonat");
        member("vives", 215, "Pep", "Vives", cash(), "abonat");
        member("xirau", 216, "Ona", "Xirau", cash(), "abonat");
        status("nuria", 217, "Núria", "Abad", "PENDING");
        status("pere", 218, "Pere", "Abril", "LEFT");
        mongo.save(new Document("_id", "bill-family").append("clubId", CLUB).append("holderMemberId", "serra-laura")
                .append("memberIds", List.of("serra-laura", "serra-joan")).append("status", "ACTIVE").append("version", 0)
                .append("createdAt", Date.from(NOW.minusSeconds(86_400))).append("updatedAt", Date.from(NOW.minusSeconds(86_400))), "family_groups");
        mongo.updateMulti(Query.query(Criteria.where("_id").in("serra-laura", "serra-joan")), new org.springframework.data.mongodb.core.query.Update()
                .set("familyGroupId", "bill-family"), "members");
        // Mia's two single classes of August, charged by attendance (R-12-25).
        charge("bill-charge-1", "mas", "bill-booking-1", "Classe 04/08 — Duna");
        charge("bill-charge-2", "mas", "bill-booking-2", "Classe 11/08 — Duna");
        // The receipts counter imported from Playoff (S18): the first invoice of 2026 is 2026-0912 (T-12-04).
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new org.springframework.data.mongodb.core.query.Update()
                .set("billing", new Document("invoiceSeriesPattern", "{YYYY}").append("nextNumber", 912L).append("resetYearly", true)), Club.class);
        configs.invalidate(CLUB); configs.invalidate(OTHER);
    }

    void club(String id, String host, String zone, List<Module> modules, Map<String, Object> providers) {
        mongo.remove(Query.query(Criteria.where("_id").is(id)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, host, zone));
        tree.set("modules", mapper.valueToTree(modules));
        tree.set("paymentProviders", mapper.valueToTree(providers));
        tree.put("name", id.equals(CLUB) ? "Club Agility Facturació" : "Club Agility Altre");
        // R-12-07: the club's configuration predates the census (the shared fixture's is in 2030, after every simulation here).
        tree.set("updatedAt", mapper.valueToTree(NOW.minusSeconds(86_400)));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
    }
    static Map<String, Object> providers(boolean sepa, boolean manual, boolean stripe) {
        var providers = new LinkedHashMap<String, Object>();
        if (sepa) { providers.put("SEPA_XML", Map.of("enabled", true, "creditorName", "Club Agility Facturació", "creditorId", "ES00ZZZB00000000", "iban", IBAN.replace("4321", "9876"))); }
        if (manual) { providers.put("MANUAL", Map.of("enabled", true)); }
        if (stripe) { providers.put("STRIPE", Map.of("enabled", true)); }
        return providers;
    }
    void modules(String clubId, List<Module> modules) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId)), new org.springframework.data.mongodb.core.query.Update().set("modules", modules), Club.class);
        configs.invalidate(clubId);
    }
    void plan(String code, OfferTerms.PlanType type, OfferTerms.BillingMode mode, String name, int dogs) {
        var updated = NOW.minusSeconds(86_400 * 30L);
        mongo.insert(new Plan("bill-plan-" + code, CLUB, code, new LocalizedText(Map.of("ca", name), "ca"), type, mode == null ? OfferTerms.BillingMode.MONTHLY_FEE : mode,
                dogs, null, type == OfferTerms.PlanType.PACK ? new OfferTerms.Pack(10, 5) : null,
                type == OfferTerms.PlanType.SINGLE_CLASS ? new OfferTerms.SingleClass(OfferTerms.ChargeMode.CHARGE_ON_ATTENDANCE, OfferTerms.CancelPolicy.NONE) : null,
                null, null, true, true, 1, true, 0, updated, updated, "bill-admin", "bill-admin"));
    }
    void price(String plan, OfferTerms.PriceConcept concept, long cents, String currency) {
        var updated = NOW.minusSeconds(86_400 * 30L);
        mongo.insert(new Price("bill-price-" + plan + "-" + concept.name().toLowerCase(Locale.ROOT), CLUB, "bill-plan-" + plan, concept, new Money(cents, currency),
                BigDecimal.ZERO, LocalDate.of(2026, 1, 1), null, 0, updated, updated, "bill-admin", "bill-admin"));
    }
    static Map<String, Object> sepa(String iban) {
        var method = new LinkedHashMap<String, Object>(); method.put("type", "SEPA_DD"); method.put("holderName", "Titular Example");
        if (iban != null) { method.put("iban", iban); method.put("mandateRef", "bill-a-mandate"); }
        return method;
    }
    static Map<String, Object> card(boolean invalid) {
        return Map.of("type", "CARD", "card", Map.of("stripeCustomerId", "cus_fixture", "last4", "4242", "invalid", invalid));
    }
    static Map<String, Object> cash() { return Map.of("type", "MANUAL", "channel", "cash"); }
    void member(String id, int number, String first, String last, Map<String, Object> payment, String plan) {
        if (payment != null && payment.containsKey("mandateRef")) {
            // E8-T03: each SEPA member has its own mandate `{clubSlug}-{memberNumber}-1`, signed on 15-06 (R-12-12 `MndtId`, `DtOfSgntr`).
            payment = new LinkedHashMap<>(payment);
            payment.put("mandateRef", CLUB + "-" + number + "-1"); payment.put("mandateSignedAt", Date.from(Instant.parse("2026-06-15T10:00:00Z")));
        }
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("status", "ACTIVE").append("memberNumber", number).append("firstName", first)
                .append("lastName1", last).append("planId", plan == null ? null : "bill-plan-" + plan).append("nextInvoiceDate", "2026-09-01")
                .append("paymentMethod", payment == null ? null : new Document(payment)).append("accountId", "bill-account-" + id)
                .append("signup", new Document("locale", "ca")).append("bookingBlock", new Document("active", false)).append("version", 0)
                .append("createdAt", Date.from(NOW.minusSeconds(86_400))).append("updatedAt", Date.from(NOW.minusSeconds(86_400))), "members");
    }
    void status(String id, int number, String first, String last, String status) {
        member(id, number, first, last, sepa(IBAN), "abonat");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new org.springframework.data.mongodb.core.query.Update().set("status", status), "members");
    }
    void charge(String id, String member, String booking, String description) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("memberId", member).append("dogId", "bill-dog-" + member).append("bookingId", booking)
                .append("priceId", "bill-price-single-single_class").append("amount", new Document("amountMinor", 1200L).append("currency", "EUR"))
                .append("description", description).append("createdAt", Date.from(NOW.minusSeconds(86_400))), "pending_charges");
    }
    void parameter(String clubId, String key, Object value) {
        mongo.remove(Query.query(Criteria.where("clubId").is(clubId).and("key").is(key)), Parameter.class);
        mongo.insert(new Parameter(UUID.randomUUID().toString(), clubId, key, value, "unknown", "club", null, List.of(), 1L, clock.instant()));
        configs.invalidate(clubId);
    }
    void admin(String accountId, String clubId) {
        mongo.save(new com.agilityhub.core.identity.persistence.Account(accountId, accountId + "@example.test", "Admin " + accountId, "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, NOW));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(accountId, accountId, clubId, null, Set.of(com.agilityhub.core.identity.domain.Role.ADMIN),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.ADMIN));
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String clubId, String role, String memberId) {
        return request.header("Host", clubId.equals(CLUB) ? HOST : OTHER_HOST).with(jwt().jwt(j -> j.subject("bill-" + role.toLowerCase(Locale.ROOT))
                .claim("clubId", clubId).claim("memberId", memberId == null ? "none" : memberId)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return as(request, CLUB, "ADMIN", null); }
    MockHttpServletRequestBuilder keyed(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(body));
    }
    MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    JsonNode json(MockHttpServletResponse response) throws Exception { return mapper.readTree(response.getContentAsString()); }
    JsonNode ok(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = call(request);
        org.assertj.core.api.Assertions.assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentLength() == 0 && response.getContentAsByteArray().length == 0 ? null : json(response);
    }
    void error(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        var response = call(request);
        org.assertj.core.api.Assertions.assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        org.assertj.core.api.Assertions.assertThat(json(response).path("code").asText()).isEqualTo(code);
    }
    JsonNode simulate(String period) throws Exception {
        return ok(admin(post("/api/v1/billing/simulations").contentType("application/json").content("{\"period\":\"" + period + "\"}")), 201);
    }
    JsonNode run(String period, String simulationId) throws Exception {
        return ok(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", period, "simulationId", simulationId))), 201);
    }
    String member(String id, String field) { return Objects.toString(mongo.findById(id, Document.class, "members").get(field), null); }
    List<Document> invoices() { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB)).with(org.springframework.data.domain.Sort.by("number")), Document.class, "invoices"); }
    List<Document> events(String type) { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type)), Document.class, "domain_events"); }
}

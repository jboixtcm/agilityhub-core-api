package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriod;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequest;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.SignupCapabilities;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E8-T01 contract (S12 WP-12-A, S13 WP-13-A): every S12/S13 §6 route answers 501 behind its role, tenant, impersonation,
 * module and resource guards — T-12-21 (another club's invoice, run, remittance → 404; MEMBER on the admin routes → 403;
 * INSTRUCTOR → 403; impersonation on the admin routes → 403), T-12-22 (`BILLING`, `PACKS`, `SINGLE_CLASS` off → 404),
 * T-13-24 (another club's period or request → 404; another member's, also of the caller's family group → 404; the
 * impersonation token on the `/me/*` routes only) and T-13-25 (`INACTIVITY` off → 404 on every `*inactivity*` route while
 * the leave routes answer) — the stubs write nothing, and the snapshot publishes every operation with its forms. E8-T02 serves
 * 18 of the routes and E8-T03 the four `/remittances*` ones (`served` in `e8-routes.json`): the same guards run first, and past
 * them a served route answers its success or a business error, never 501.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E8ContractIT extends AbstractIntegrationTest {
    static final String CLUB = "e8-club-a", OTHER = "e8-club-b", HOST = "e8-a.example.test", OTHER_HOST = "e8-b.example.test";
    static final List<String> ROLES = List.of("ANON", "MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN");
    static final List<String> DATA = List.of("invoices", "collections", "remittances", "billing_runs", "billing_simulations", "upfront_payments", "pack_balances",
            "pending_charges", "stripe_events", "billing_locks", "inactivity_periods", "leave_requests", "checkout_sessions", "members", "memberships", "dogs",
            "family_groups", "domain_events", "audit_entries", "idempotency_records", "bookings");
    /** The fixture webhook signing secret of club A (never a real key), stored encrypted with the ITs' BILLING_SECRETS_KEY. */
    static final String WEBHOOK_SECRET = "whsec_fake_fake_fake";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired MongoTemplate mongo;
    @Autowired SignupCapabilities capabilities;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    @Autowired com.agilityhub.core.payments.application.ProviderSecretVault secrets;
    String signupToken;

    /** `served`: E8-T02 or E8-T03 serves the route (its guards still run first); every other route still answers 501 after them. */
    record Route(String method, String path, List<String> roles, JsonNode body, Map<String, String> params, boolean idempotency, int success,
                 String module, String scope, boolean resource, boolean impersonation, boolean served) {
        boolean club() { return scope.equals("CLUB"); }
        boolean webhook() { return scope.equals("WEBHOOK"); }
        boolean me() { return path.startsWith("/api/v1/me/"); }
        String label() { return method + " " + path; }
    }
    static Stream<Route> routes() throws Exception {
        try (var input = E8ContractIT.class.getResourceAsStream("/fixtures/contracts/e8-routes.json")) {
            return Arrays.stream(new ObjectMapper().readValue(input, Route[].class));
        }
    }
    static Stream<Route> clubRoutes() throws Exception { return routes().filter(Route::club); }
    /** E8-T02 serves 18 routes and E8-T03 four; the rest of the contract stays a stub. */
    static Stream<Route> stubRoutes() throws Exception { return routes().filter(route -> !route.served()); }
    /** A served route past its guards: its success or a business answer, never the guards' 401/403 nor the stub's 501. */
    private void served(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn();
        String label = response.getRequest().getMethod() + " " + response.getRequest().getRequestURI();
        assertThat(response.getResponse().getStatus()).as(label + " " + response.getResponse().getContentAsString()).isNotIn(401, 403, 501);
    }

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-09-24T08:00:00Z"));
        for (String clubId : List.of(CLUB, OTHER)) { club(clubId, List.of(Module.values())); }
        hosts.invalidate();
        for (String collection : DATA) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        Instant now = clock.instant();
        member("e8-member-a", 214, "e8-MEMBER", "e8-family", null);
        member("e8-member-b", 215, "e8-other-member", "e8-family", null);
        member("e8-member-erased", 216, null, null, now);
        mongo.save(new Document("_id", "e8-member-other").append("clubId", OTHER).append("status", "ACTIVE").append("firstName", "Pau").append("lastName1", "Example")
                .append("memberNumber", 301).append("version", 0), "members");
        mongo.save(new Document("_id", "e8-member-membership").append("clubId", CLUB).append("accountId", "e8-MEMBER").append("memberId", "e8-member-a")
                .append("status", "ACTIVE"), "memberships");
        mongo.save(new Document("_id", "e8-family").append("clubId", CLUB).append("holderMemberId", "e8-member-a").append("memberIds", List.of("e8-member-a", "e8-member-b"))
                .append("status", "ACTIVE").append("version", 0), "family_groups");
        for (var dog : List.of(Map.entry("e8-dog-a", "e8-member-a"), Map.entry("e8-dog-b", "e8-member-b"))) {
            mongo.save(new Document("_id", dog.getKey()).append("clubId", CLUB).append("memberId", dog.getValue()).append("name", "Duna").append("sex", "FEMALE")
                    .append("status", "ACTIVE").append("version", 0), "dogs");
        }
        // E8-T01 round 2: the bookings a checkout may name (S08 R-08-18): member A's and member B's in club A, and one in club B.
        for (var booking : List.of(List.of("e8-booking-a", CLUB, "e8-member-a"), List.of("e8-booking-b", CLUB, "e8-member-b"), List.of("e8-booking-other", OTHER, "e8-member-a"))) {
            mongo.save(new Document("_id", booking.get(0)).append("clubId", booking.get(1)).append("memberId", booking.get(2)).append("dogId", "e8-dog-a")
                    .append("state", "PAYMENT_PENDING").append("version", 0), "bookings");
        }
        Money fee = new Money(9000, "EUR"), zero = new Money(0, "EUR");
        for (var owner : List.of(Map.entry("e8-invoice-a", "e8-member-a"), Map.entry("e8-invoice-b", "e8-member-b"))) {
            mongo.insert(new Invoice(owner.getKey(), CLUB, "2026", owner.getKey().endsWith("a") ? 912 : 913, "2026-0912", "2026-08-25", "2026-09", owner.getValue(),
                    new Invoice.MemberSnapshot(214, "Laura Example", null), List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, null, null,
                    "Quota Abonat — Setembre 2026", fee, java.math.BigDecimal.ZERO, zero, fee)), fee, zero, fee,
                    new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "···· ···· ···· ···· 1234", "Laura Example", "e8-club-a-214-1", null, null),
                    InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC, "e8-run-a", "e8-remittance-a", false, null, null, null, null, null, null, zero, null,
                    0L, now, "e8-ADMIN", now, "e8-ADMIN"));
        }
        mongo.insert(new BillingSimulation("e8-simulation-a", CLUB, "2026-09", now, List.of(), List.of(), List.of(),
                new BillingSimulation.Kpis(1, fee, new BillingSimulation.ByProvider(new BillingSimulation.Totals(1, fee), null, null), 0,
                        new BillingSimulation.InactivityFees(0, new Money(2000, "EUR"), new Money(1000, "EUR"))), "e8-ADMIN"));
        mongo.insert(new BillingRun("e8-run-a", CLUB, "2026-09", BillingRunStatus.GENERATED, "e8-simulation-a", List.of("e8-invoice-a"),
                new BillingRun.ByProvider(new BillingRun.Totals(1, fee, "e8-remittance-a", null, null), null, null), "2026-09-01", now, now, List.of(),
                List.of(new BillingRun.PreviousDate("e8-member-a", "2026-09-01")), 912, "e8-ADMIN", null, null, 0L, now));
        mongo.insert(new Remittance("e8-remittance-a", CLUB, "e8-run-a", "2026-09", "e8-club-a-2026-09-1", now, "2026-09-01",
                new Remittance.Creditor("Club Example", "ES00ZZZB00000000", "ES0000000000000000000000", null), List.of("e8-collection-a"), 1, fee,
                new Remittance.SequenceBreakdown(0, 1), "remittances/e8-club-a/2026-09-1.xml", now, null, RemittanceStatus.GENERATED, null, null, 0L, now, "e8-ADMIN"));
        mongo.insert(new UpfrontPayment("e8-upfront-a", CLUB, "e8-member-a", "e8-dog-a", "PACK", null, new Money(12000, "EUR"), new Money(12000, "EUR"), "PAID",
                "STRIPE", "e8-checkout-a", now, now, null, null, null));
        mongo.insert(new UpfrontPayment("e8-upfront-b", CLUB, "e8-member-b", "e8-dog-b", "PACK", null, new Money(12000, "EUR"), new Money(0, "EUR"), "DUE",
                null, null, now, null, null, null, null));
        mongo.insert(new UpfrontPayment("e8-upfront-other", OTHER, "e8-member-a", "e8-dog-a", "PACK", null, new Money(12000, "EUR"), new Money(0, "EUR"), "DUE",
                null, null, now, null, null, null, null));
        mongo.insert(new PackBalance("e8-pack-a", CLUB, "e8-member-a", "e8-dog-a", "e8-plan-a", "e8-upfront-a", 10, 0, 10, "2026-06-12", "2026-11-11",
                PackBalanceState.ACTIVE, List.of(new PackBalance.Movement("e8-movement-a", PackMovementType.OPEN, 10, null, null, null, now)), null, null, null, null, 0L, now, "e8-ADMIN"));
        mongo.insert(new SignupCheckoutSession("e8-checkout-a", CLUB, "e8-member-a", "PENDING", "payment", List.of("e8-upfront-a"), now.plusSeconds(1800), null));
        for (var owner : List.of(Map.entry("a", "e8-member-a"), Map.entry("b", "e8-member-b"))) {
            mongo.insert(new InactivityPeriod("e8-period-" + owner.getKey(), CLUB, owner.getValue(), "2026-11", "2026-12", "Descans de la Duna", InactivityState.REQUESTED,
                    LifecycleOrigin.APP, now, new LifecycleParts.Requester("e8-MEMBER", null), null, null, null, null, null, null, null, null, List.of(),
                    List.of(new InactivityPeriod.HistoryEntry(now, "e8-MEMBER", "2026-11", "2026-12", ChangeSource.MEMBER)), 0L, now, now));
            mongo.insert(new LeaveRequest("e8-leave-" + owner.getKey(), CLUB, owner.getValue(), LeaveSource.MEMBER, LifecycleOrigin.APP, now,
                    new LifecycleParts.Requester("e8-MEMBER", null), "2026-10-31", "EXTERNAL", 8, null, LeaveRequestState.PENDING, null, null, null, null, null,
                    List.of(), null, 0L, now, now));
        }
        try (var tenant = TenantContext.open(CLUB)) { signupToken = capabilities.issue("e8-member-a"); }
    }
    private void member(String id, int number, String accountId, String familyGroupId, Instant erasedAt) {
        mongo.save(new Document("_id", id).append("clubId", CLUB).append("accountId", accountId).append("status", "ACTIVE").append("firstName", "Laura")
                .append("lastName1", "Example").append("familyGroupId", familyGroupId).append("erasedAt", erasedAt).append("memberNumber", number)
                .append("bookingBlock", Map.of("active", false)).append("version", 0), "members");
    }
    private void club(String clubId, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
        tree.set("modules", mapper.valueToTree(modules));
        // R-12-21: club A has a webhook secret (encrypted as S12 stores it); club B has none.
        if (clubId.equals(CLUB)) { ((ObjectNode) tree.path("paymentProviders").path("STRIPE")).put("webhookSecretEnc", secrets.encrypt(WEBHOOK_SECRET, CLUB, "STRIPE", "webhookSecretEnc")); }
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }
    /** Stripe's `Stripe-Signature` (t, v1 = hex HMAC-SHA256 of "{t}.{body}") at {@code at}, computed here independently of the api. */
    static String signature(String secret, Instant at, String body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signed = at.getEpochSecond() + "." + body;
        return "t=" + at.getEpochSecond() + ",v1=" + java.util.HexFormat.of().formatHex(mac.doFinal(signed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private String signature(String body) throws Exception { return signature(WEBHOOK_SECRET, clock.instant(), body); }
    private static List<Module> without(Module... off) {
        var modules = new ArrayList<>(List.of(Module.values())); modules.removeAll(List.of(off)); return modules;
    }

    private static String id(Route r) {
        String path = r.path();
        if (path.contains("/billing/runs/")) { return "e8-run-a"; }
        if (path.contains("/remittances/")) { return "e8-remittance-a"; }
        if (path.contains("/invoices/")) { return "e8-invoice-a"; }
        if (path.contains("/upfront-payments/")) { return "e8-upfront-a"; }
        if (path.contains("/pack-balances/")) { return "e8-pack-a"; }
        if (path.contains("/checkout-sessions/")) { return "e8-checkout-a"; }
        if (path.contains("inactivity-periods/")) { return "e8-period-a"; }
        if (path.contains("leave-requests/")) { return "e8-leave-a"; }
        return "e8-member-a";
    }
    private static String path(Route r, String clubId) {
        return r.path().replace("{id}", id(r)).replace("{period}", "2026-09").replace("{clubId}", clubId);
    }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role) throws Exception { return call(r, clubId, role, "e8-member-a"); }
    private MockHttpServletRequestBuilder call(Route r, String clubId, String role, String memberId) throws Exception {
        var request = request(HttpMethod.valueOf(r.method()), path(r, clubId)).header("Host", clubId.equals(CLUB) ? HOST : OTHER_HOST);
        if (r.body() != null && !r.body().isNull()) { request.contentType("application/json").content(mapper.writeValueAsString(r.body())); }
        r.params().forEach(request::param);
        if (r.idempotency()) { request.header("Idempotency-Key", UUID.randomUUID().toString()); }
        if (r.webhook()) { request.header("Stripe-Signature", signature(mapper.writeValueAsString(r.body()))); }
        if (role.equals("ANON") && r.path().startsWith("/api/v1/checkout-sessions/")) { request.header("X-Signup-Token", signupToken); }
        if (!role.equals("ANON")) {
            request.with(jwt().jwt(j -> j.subject("e8-" + role).claim("clubId", clubId).claim("memberId", memberId)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return request;
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.header("Host", HOST).with(jwt().jwt(j -> j.subject("e8-" + roles[0]).claim("clubId", CLUB).claim("memberId", "e8-member-a"))
                .authorities(Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toArray(SimpleGrantedAuthority[]::new)));
    }
    private ResultActions error(MockHttpServletRequestBuilder request, int status, String... codes) throws Exception {
        var result = mvc.perform(request);
        String label = result.andReturn().getRequest().getMethod() + " " + result.andReturn().getRequest().getRequestURI();
        assertThat(result.andReturn().getResponse().getStatus()).as(label + " " + result.andReturn().getResponse().getContentAsString()).isEqualTo(status);
        assertThat(mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("code").asText()).as(label).isIn((Object[]) codes);
        return result.andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @ParameterizedTest @MethodSource("clubRoutes")
    void T_12_21_T_13_24_everyRouteEnforcesRolesTenantAndResourceIsolationBeforeItsAnswerOrItsStub(Route route) throws Exception {
        for (String role : ROLES) {
            boolean allowed = route.roles().contains(role);
            if (allowed && route.served()) { served(call(route, CLUB, role)); continue; }
            error(call(route, CLUB, role), allowed ? 501 : role.equals("ANON") ? 401 : 403,
                    allowed ? "NOT_IMPLEMENTED" : role.equals("ANON") ? "UNAUTHENTICATED" : "FORBIDDEN");
        }
        String role = route.roles().stream().filter(r -> !r.equals("ANON")).findFirst().orElseThrow();
        error(call(route, CLUB, role).with(req -> { req.removeHeader("Host"); req.addHeader("Host", OTHER_HOST); return req; }), 403, "TENANT_MISMATCH");
        error(call(route, CLUB, role).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))), 403, "NO_MEMBERSHIP");
        // T-12-21, T-13-24: nothing of club A is visible or changeable from club B.
        if (route.resource()) { error(call(route, OTHER, role), 404, "NOT_FOUND"); }
        assertThat(TenantContext.current()).isNull();
    }

    @Test void T_12_21_T_13_24_impersonationReachesTheMemberRoutesOnly() throws Exception {
        for (String kind : List.of("admin", "member")) {
            String id = "e8-imp-" + kind;
            var role = kind.equals("admin") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + kind, "en", null,
                    Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, CLUB, "e8-member-a", Set.of(role),
                    com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is("e8-member-a")), new org.springframework.data.mongodb.core.query.Update().set("accountId", "e8-imp-member"), "members");
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var scope = TenantContext.open(CLUB)) { issued = impersonations.create("e8-imp-admin", "e8-member-a", "Contract authorization test"); }
        for (Route route : clubRoutes().toList()) {
            var request = call(route, CLUB, "MEMBER").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"));
            if (route.impersonation() && route.served()) { served(request); }
            else if (route.impersonation()) { error(request, 501, "NOT_IMPLEMENTED"); }
            else { error(request, 403, "IMPERSONATION_DENIED", "FORBIDDEN"); }
        }
        assertThat(clubRoutes().filter(Route::impersonation).map(Route::path).allMatch(path -> path.startsWith("/api/v1/me/") || path.startsWith("/api/v1/checkout-sessions/"))).isTrue();
        assertThat(clubRoutes().filter(Route::me).allMatch(Route::impersonation)).as("every /me/* route accepts the impersonation token (R-12-27, R-13-18)").isTrue();
        // T-12-21: the impersonation token on /billing/runs is refused by name, before the role check.
        error(post("/api/v1/billing/runs").header("Host", HOST).header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"period\":\"2026-09\",\"simulationId\":\"e8-simulation-a\"}").with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER")), 403, "IMPERSONATION_DENIED");
        // R-13-18: under impersonation the /me/* routes act for the impersonated member (another member's period stays 404).
        error(post("/api/v1/me/inactivity-periods/e8-period-b/cancellation").header("Host", HOST).with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER")), 404, "NOT_FOUND");
    }

    /**
     * T-13-24 (R-13-18) and R-12-27: a member reaches only their own period, request, invoice and checkout session; one of
     * another member, also of their family group, is 404. S01 R-01-07 (E41): a member route serves every token with the MEMBER
     * role, also MEMBER plus ADMIN, and refuses an ADMIN- or INSTRUCTOR-only token (403).
     */
    @Test void T_13_24_aMemberReachesOnlyTheirOwnResourcesAndStaffOnlyTokensAreRefused() throws Exception {
        for (Route route : clubRoutes().filter(r -> r.resource() && r.roles().contains("MEMBER") && (r.me() || r.path().startsWith("/api/v1/checkout-sessions/"))).toList()) {
            // R-12-27 (E8-T02): the family holder's invoice is the group's; a member outside the group still gets 404.
            boolean holdersInvoice = route.path().startsWith("/api/v1/me/invoices/");
            error(call(route, CLUB, "MEMBER", holdersInvoice ? "e8-member-erased" : "e8-member-b"), 404, "NOT_FOUND");
            if (holdersInvoice) { mvc.perform(call(route, CLUB, "MEMBER", "e8-member-b")).andExpect(status().isOk()); }
        }
        error(as(get("/api/v1/me/invoices/e8-invoice-b"), "MEMBER"), 404, "NOT_FOUND");
        error(as(patch("/api/v1/me/inactivity-periods/e8-period-b").contentType("application/json").content("{\"version\":0}"), "MEMBER"), 404, "NOT_FOUND");
        error(as(post("/api/v1/me/leave-requests/e8-leave-b/cancellation"), "MEMBER"), 404, "NOT_FOUND");
        for (Route route : clubRoutes().filter(Route::me).toList()) {
            for (String staff : List.of("ADMIN", "INSTRUCTOR")) {
                error(call(route, CLUB, "MEMBER").with(jwt().jwt(j -> j.claim("clubId", CLUB).claim("memberId", "e8-member-a")).authorities(() -> "ROLE_" + staff)), 403, "FORBIDDEN");
                var both = call(route, CLUB, "MEMBER").with(jwt().jwt(j -> j.claim("clubId", CLUB).claim("memberId", "e8-member-a"))
                        .authorities(() -> "ROLE_MEMBER", () -> "ROLE_" + staff));
                if (route.served()) { served(both); } else { error(both, 501, "NOT_IMPLEMENTED"); }
            }
        }
        // The checkout's return screen: the session's member, an admin of the club or the anonymous signup capability only.
        error(get("/api/v1/checkout-sessions/e8-checkout-a").header("Host", HOST), 401, "UNAUTHENTICATED");
        error(get("/api/v1/checkout-sessions/e8-checkout-a").header("Host", HOST).header("X-Signup-Token", "not-a-capability"), 401, "UNAUTHENTICATED");
        String otherToken;
        try (var tenant = TenantContext.open(CLUB)) { otherToken = capabilities.issue("e8-member-b"); }
        error(get("/api/v1/checkout-sessions/e8-checkout-a").header("Host", HOST).header("X-Signup-Token", otherToken), 401, "UNAUTHENTICATED");
        error(get("/api/v1/checkout-sessions/e8-missing").header("Host", HOST).header("X-Signup-Token", signupToken), 404, "NOT_FOUND");
        error(get("/api/v1/checkout-sessions/e8-checkout-a").header("Host", OTHER_HOST).header("X-Signup-Token", signupToken), 404, "NOT_FOUND");
    }

    @Test void T_12_22_billingOffHidesEveryS12RouteAndTheLeaveRoutesStillAnswer() throws Exception {
        club(CLUB, without(Module.BILLING, Module.PACKS, Module.SINGLE_CLASS));
        var billing = new HashSet<>(Arrays.asList("BILLING", "PACKS", "SINGLE_CLASS"));
        for (Route route : clubRoutes().filter(r -> billing.contains(r.module())).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 404, "MODULE_DISABLED"); }
        }
        assertThat(clubRoutes().filter(r -> billing.contains(r.module())).count()).isEqualTo(35);
        error(as(get("/api/v1/me/invoices"), "MEMBER"), 404, "MODULE_DISABLED");
        // A delivery signed by the club's secret reaches the module guard; an unsigned one never does (R-12-21).
        String event = "{\"id\":\"evt_e8\",\"type\":\"payment_intent.succeeded\"}";
        error(webhook(CLUB, event, signature(event)), 404, "MODULE_DISABLED");
        error(webhook(CLUB, event, "t=1,v1=x"), 401, "WEBHOOK_SIGNATURE_INVALID");
        for (Route route : clubRoutes().filter(r -> r.module() == null || r.module().equals("INACTIVITY")).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 501, "NOT_IMPLEMENTED"); }
        }
    }

    @Test void T_12_22_packsAndSingleClassOffHideTheirRoutesOnly() throws Exception {
        club(CLUB, without(Module.PACKS));
        for (Route route : clubRoutes().filter(r -> "PACKS".equals(r.module())).toList()) { error(call(route, CLUB, route.roles().getFirst()), 404, "MODULE_DISABLED"); }
        assertThat(clubRoutes().filter(r -> "PACKS".equals(r.module())).map(Route::label)).containsExactly("GET /api/v1/pack-balances", "GET /api/v1/me/pack-balances",
                "POST /api/v1/pack-balances", "POST /api/v1/pack-balances/{id}/adjustments");
        mvc.perform(as(get("/api/v1/invoices"), "ADMIN")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/members/e8-member-a/pending-charges"), "ADMIN")).andExpect(status().isOk());
        club(CLUB, without(Module.SINGLE_CLASS));
        error(as(get("/api/v1/members/e8-member-a/pending-charges"), "ADMIN"), 404, "MODULE_DISABLED");
        error(as(get("/api/v1/pack-balances").param("memberId", "e8-member-a"), "ADMIN"), 501, "NOT_IMPLEMENTED");
    }

    @Test void T_13_25_inactivityOffHidesEveryInactivityRouteWhileTheLeaveRoutesAnswer() throws Exception {
        club(CLUB, without(Module.INACTIVITY));
        for (Route route : clubRoutes().filter(r -> "INACTIVITY".equals(r.module())).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 404, "MODULE_DISABLED"); }
        }
        assertThat(clubRoutes().filter(r -> "INACTIVITY".equals(r.module())).allMatch(r -> r.path().contains("inactivity"))).isTrue();
        assertThat(clubRoutes().filter(r -> r.path().contains("inactivity")).count()).isEqualTo(12);
        for (Route route : clubRoutes().filter(r -> r.module() == null).toList()) {
            for (String role : route.roles()) { error(call(route, CLUB, role), 501, "NOT_IMPLEMENTED"); }
        }
        assertThat(clubRoutes().filter(r -> r.module() == null).count()).isEqualTo(9);
    }

    /**
     * R-12-21 (round 2, review #2): the webhook names its club in the path (no bearer, no host) and authenticates the body before
     * anything else: Stripe's HMAC-SHA256 of "{t}.{body}" under the club's webhook secret, with a 5-minute tolerance. A missing,
     * made-up, wrong, stale or another body's signature, an unknown club, a club without a secret and one whose stored secret
     * does not decrypt → 401 WEBHOOK_SIGNATURE_INVALID with a SecurityEvent each and nothing else stored. A valid signature
     * reaches the club guards (STRIPE disabled → 404) and then 501 until E8-T04; a bearer of another club changes nothing.
     */
    @Test void T_12_15_theStripeWebhookAuthenticatesTheBodyBeforeAnythingElse() throws Exception {
        String body = "{\"id\":\"evt_e8_fixture\",\"type\":\"payment_intent.succeeded\",\"created\":1790000000}";
        String tampered = body.replace("payment_intent.succeeded", "charge.refunded");
        var before = database();
        error(webhook(CLUB, body, signature(body)), 400, "VALIDATION_ERROR");
        // Stripe may send several v1 (a rolled secret) and a v0: one valid v1 is enough, a v0 never counts.
        String rolled = signature(body).replace(",v1=", ",v1=" + "0".repeat(64) + ",v0=" + "1".repeat(64) + ",v1=");
        error(webhook(CLUB, body, rolled), 400, "VALIDATION_ERROR");
        error(webhook(CLUB, body, signature(body)).with(jwt().jwt(j -> j.claim("clubId", OTHER)).authorities(() -> "ROLE_ADMIN")), 400, "VALIDATION_ERROR");
        long events = securityEvents();
        Map<String, MockHttpServletRequestBuilder> refused = new LinkedHashMap<>();
        refused.put("missing", webhook(CLUB, body, null));
        refused.put("made up", webhook(CLUB, body, "t=" + clock.instant().getEpochSecond() + ",v1=e8"));
        refused.put("another secret", webhook(CLUB, body, signature("whsec_fake_other_fake", clock.instant(), body)));
        refused.put("tampered body", webhook(CLUB, tampered, signature(body)));
        refused.put("only v0", webhook(CLUB, body, signature(body).replace("v1=", "v0=")));
        refused.put("stale", webhook(CLUB, body, signature(WEBHOOK_SECRET, clock.instant().minusSeconds(301), body)));
        refused.put("future", webhook(CLUB, body, signature(WEBHOOK_SECRET, clock.instant().plusSeconds(301), body)));
        refused.put("unknown club", webhook("e8-unknown-club", body, signature(body)));
        refused.put("club without a secret", webhook(OTHER, body, signature(body)));
        for (var entry : refused.entrySet()) {
            var response = mvc.perform(entry.getValue()).andReturn().getResponse();
            assertThat(response.getStatus()).as(entry.getKey()).isEqualTo(401);
            assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).as(entry.getKey()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");
        }
        assertThat(securityEvents()).as("one SecurityEvent per refused delivery").isEqualTo(events + refused.size());
        for (var request : refused.values()) { error(request, 401, "WEBHOOK_SIGNATURE_INVALID"); }
        // Inside the tolerance on both sides.
        error(webhook(CLUB, body, signature(WEBHOOK_SECRET, clock.instant().minusSeconds(300), body)), 400, "VALIDATION_ERROR");
        error(webhook(CLUB, body, signature(WEBHOOK_SECRET, clock.instant().plusSeconds(300), body)), 400, "VALIDATION_ERROR");
        // A stored secret that does not decrypt (club A's ciphertext: another associated data) is no secret.
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(OTHER).orElseThrow());
        ((ObjectNode) tree.path("paymentProviders").path("STRIPE")).put("webhookSecretEnc", secrets.encrypt(WEBHOOK_SECRET, CLUB, "STRIPE", "webhookSecretEnc"));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(OTHER);
        error(webhook(OTHER, body, signature(body)), 401, "WEBHOOK_SIGNATURE_INVALID");
        // Signed with the club's own secret but STRIPE disabled → 404 («club sense Stripe»), after the signature.
        tree = (ObjectNode) mapper.valueToTree(clubs.findById(OTHER).orElseThrow());
        ((ObjectNode) tree.path("paymentProviders").path("STRIPE")).put("webhookSecretEnc", secrets.encrypt(WEBHOOK_SECRET, OTHER, "STRIPE", "webhookSecretEnc"))
                .put("enabled", false);
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(OTHER);
        error(webhook(OTHER, body, signature(body)), 404, "NOT_FOUND");
        error(webhook(OTHER, body, null), 401, "WEBHOOK_SIGNATURE_INVALID");
        var after = database(); after.remove("clubs"); before.remove("clubs");
        assertThat(after).as("nothing but the security events is stored").isEqualTo(before);
        assertThat(mongo.getCollection("stripe_events").countDocuments()).isZero();
    }
    private MockHttpServletRequestBuilder webhook(String clubId, String body, String signature) {
        var request = post("/webhooks/stripe/" + clubId).contentType("application/json").content(body);
        return signature == null ? request : request.header("Stripe-Signature", signature);
    }
    private long securityEvents() {
        return mongo.getCollection("security_events").countDocuments(new Document("type", "WEBHOOK_SIGNATURE_INVALID"));
    }

    /** The bodies, months and list queries are validated before the stub; an issued invoice has no PATCH (T-12-12); MEMBER_ERASED. */
    @Test void T_12_12_T_13_02_inputsAreValidatedBeforeTheStubAndAnInvoiceHasNoPatch() throws Exception {
        error(as(get("/api/v1/billing/periods/2026-13"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/billing/simulations").contentType("application/json").content("{\"period\":\"september\"}"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(get("/api/v1/billing/exports").param("period", "2026-09").param("format", "pdf"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(get("/api/v1/pack-balances"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(get("/api/v1/me/invoices").param("size", "0"), "MEMBER"), 400, "VALIDATION_ERROR");
        error(as(get("/api/v1/me/inactivity-periods/preview").param("fromMonth", "2026-1"), "MEMBER"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/me/inactivity-periods").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"fromMonth\":\"2026-10\",\"toMonth\":\"2026-1x\"}"), "MEMBER"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/me/leave-requests").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"requestedDate\":\"2026-10-31\",\"reasonKey\":\"EXTERNAL\",\"nps\":11}"), "MEMBER"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/invoices/e8-invoice-a/payment").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"paidAt\":\"2026-09-03\",\"channel\":\"CHEQUE\",\"version\":0}"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/billing/runs").contentType("application/json").content("{\"period\":\"2026-09\",\"simulationId\":\"e8-simulation-a\"}"), "ADMIN"), 400, "VALIDATION_ERROR");
        error(as(post("/api/v1/billing/runs").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"period\":\"2026-09\",\"simulationId\":\"e8-missing\"}"), "ADMIN"), 404, "NOT_FOUND");
        for (var entry : List.of(Map.entry("/api/v1/invoices", "filter=note:eq:x"), Map.entry("/api/v1/invoices", "sort=paidAt,asc"),
                Map.entry("/api/v1/invoices", "fields=memberSnapshot"), Map.entry("/api/v1/remittances", "q=canic"), Map.entry("/api/v1/remittances", "size=7"),
                Map.entry("/api/v1/inactivity-periods", "filter=comments:eq:x"), Map.entry("/api/v1/leave-requests", "sort=nps,asc"))) {
            var parameter = entry.getValue().split("=", 2);
            error(as(get(entry.getKey()).param(parameter[0], parameter[1]), "ADMIN"), 400, "INVALID_FILTER");
        }
        mvc.perform(as(get("/api/v1/invoices").param("q", "0912").param("filter", "total:gte:5000").param("fields", "displayNumber,member"), "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].displayNumber").value("2026-0912"));
        // T-12-12 (contract half): an issued invoice is immutable, there is no PATCH.
        error(as(patch("/api/v1/invoices/e8-invoice-a").contentType("application/json").content("{\"total\":{\"amountMinor\":6000,\"currency\":\"EUR\"}}"), "ADMIN"),
                405, "METHOD_NOT_ALLOWED");
        // S14 §5: a write about an erased member is 409 MEMBER_ERASED.
        error(as(post("/api/v1/members/e8-member-erased/leave").contentType("application/json").content("{\"effectiveDate\":\"2026-12-31\"}"), "ADMIN"), 409, "MEMBER_ERASED");
        error(as(post("/api/v1/members/e8-member-erased/card-setup-link").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"successUrl\":\"https://e8-a.example.test/ok\",\"cancelUrl\":\"https://e8-a.example.test/ko\"}"), "ADMIN"), 409, "MEMBER_ERASED");
        mvc.perform(as(get("/api/v1/members/e8-member-erased/pending-charges"), "ADMIN")).andExpect(status().isOk());
        // The optional references: a dog's packs, a payment without a dog, an open preview; an unknown dog is 404.
        error(as(get("/api/v1/pack-balances").param("dogId", "e8-dog-a"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/pack-balances").param("dogId", "e8-missing"), "ADMIN"), 404, "NOT_FOUND");
        error(as(post("/api/v1/upfront-payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"memberId\":\"e8-member-a\",\"concept\":\"ENTRY_FEE\",\"amountDue\":{\"amountMinor\":10000,\"currency\":\"EUR\"},"
                        + "\"amountPaid\":{\"amountMinor\":10000,\"currency\":\"EUR\"},\"channel\":\"CASH\",\"paidAt\":\"2026-09-24\"}"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/me/inactivity-periods/preview").param("fromMonth", "2026-11"), "MEMBER"), 501, "NOT_IMPLEMENTED");
        // A member token that names no member reaches no member's resource.
        var anonymousMember = jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_MEMBER");
        error(get("/api/v1/me/invoices/e8-invoice-a").header("Host", HOST).with(anonymousMember), 404, "NOT_FOUND");
        error(post("/api/v1/me/leave-requests/e8-leave-a/cancellation").header("Host", HOST).with(anonymousMember), 404, "NOT_FOUND");
        error(get("/api/v1/checkout-sessions/e8-checkout-a").header("Host", HOST).with(anonymousMember), 404, "NOT_FOUND");
    }

    /**
     * S12 §6 (E8-T04): `POST /checkout-sessions` keeps the E3-T03 signup checkout and serves `bookingId` and
     * `upfrontPaymentIds`; a request without payable rows is refused without writing an idempotency record.
     */
    @Test void T_12_16_theCheckoutExtensionsRejectPaidOrMissingRowsWithoutWrites() throws Exception {
        var before = database();
        for (String extension : List.of("\"bookingId\":\"e8-booking-a\"", "\"upfrontPaymentIds\":[\"e8-upfront-a\"]")) {
            for (String role : List.of("MEMBER", "ADMIN")) {
                error(as(checkout("e8-member-a", extension), role), 409, "INVALID_STATE");
            }
            error(checkout("e8-member-a", extension).header("Host", HOST), 401, "UNAUTHENTICATED");
            error(checkout("e8-member-a", extension).header("Host", HOST).content(withToken("e8-member-a", extension, signupToken)), 409, "INVALID_STATE");
        }
        var after = database();
        assertThat(after).isEqualTo(before);
    }

    /**
     * Round 2 (review #1, AGENTS rule 4): the checkout extensions run the signup checkout's role, member and club checks, then
     * check that the booking and every upfront payment named are that member's in the club, before the stub. A MEMBER naming
     * another member's booking or payment (also of their family group), an ADMIN naming another club's, or an unknown one → 404;
     * nothing of the census or of payments is written.
     */
    @Test void T_12_21_T_13_24_theCheckoutExtensionsAuthorizeAndCheckTheirReferencesBeforeTheStub() throws Exception {
        var before = database();
        for (String role : List.of("MEMBER", "ADMIN")) {
            for (String extension : List.of("\"bookingId\":\"e8-booking-b\"", "\"bookingId\":\"e8-booking-other\"", "\"bookingId\":\"e8-missing\"",
                    "\"upfrontPaymentIds\":[\"e8-upfront-b\"]", "\"upfrontPaymentIds\":[\"e8-upfront-a\",\"e8-upfront-other\"]",
                    "\"upfrontPaymentIds\":[\"e8-missing\"]", "\"bookingId\":\"e8-booking-a\",\"upfrontPaymentIds\":[\"e8-upfront-b\"]")) {
                error(as(checkout("e8-member-a", extension), role), 404, "NOT_FOUND");
            }
        }
        // The anonymous signup screen: the capability names member A, so member B's booking is not reachable either.
        error(checkout("e8-member-a", "").header("Host", HOST).content(withToken("e8-member-a", "\"bookingId\":\"e8-booking-b\"", signupToken)), 404, "NOT_FOUND");
        // The signup checkout's own guards come first: a MEMBER naming another member, another club's member, an erased one.
        error(as(checkout("e8-member-b", "\"bookingId\":\"e8-booking-b\""), "MEMBER"), 403, "FORBIDDEN");
        error(as(checkout("e8-member-other", "\"bookingId\":\"e8-booking-other\""), "ADMIN"), 404, "NOT_FOUND");
        error(as(checkout("e8-member-erased", "\"upfrontPaymentIds\":[\"e8-upfront-a\"]"), "ADMIN"), 409, "MEMBER_ERASED");
        error(checkout("e8-member-a", "\"bookingId\":\"e8-booking-a\"").header("Host", HOST).content(withToken("e8-member-a", "\"bookingId\":\"e8-booking-a\"", "not-a-capability")),
                401, "UNAUTHENTICATED");
        assertThat(database()).as("guards write nothing, including idempotency records").isEqualTo(before);
        mvc.perform(as(checkout("e8-member-b", "\"upfrontPaymentIds\":[\"e8-upfront-b\"]"), "ADMIN")).andExpect(status().isCreated());
    }
    private MockHttpServletRequestBuilder checkout(String memberId, String extension) {
        return post("/api/v1/checkout-sessions").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"memberId\":\"" + memberId + "\",\"successUrl\":\"https://e8-a.example.test/ok\",\"cancelUrl\":\"https://e8-a.example.test/ko\""
                        + (extension.isEmpty() ? "" : "," + extension) + "}");
    }
    private static String withToken(String memberId, String extension, String token) {
        return "{\"memberId\":\"" + memberId + "\",\"signupToken\":\"" + token + "\",\"successUrl\":\"https://e8-a.example.test/ok\","
                + "\"cancelUrl\":\"https://e8-a.example.test/ko\"," + extension + "}";
    }

    /**
     * Round 2 (review #1): every other stub resolves its caller and its references before answering 501. A pack or an upfront
     * payment names a dog of its member (another member's dog → 404); a `/me/*` stub needs the caller's member in the club
     * (a member token that names none → 404; an erased member's write → 409 MEMBER_ERASED).
     */
    @Test void T_12_21_T_13_24_everyStubResolvesItsCallerAndItsReferencesBeforeTheStub() throws Exception {
        var before = database();
        error(as(post("/api/v1/pack-balances").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"memberId\":\"e8-member-a\",\"dogId\":\"e8-dog-b\",\"planId\":\"e8-plan-a\",\"openedOn\":\"2026-06-12\",\"reason\":\"Pack regalat\"}"), "ADMIN"), 404, "NOT_FOUND");
        error(as(post("/api/v1/pack-balances").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"memberId\":\"e8-member-a\",\"dogId\":\"e8-dog-a\",\"planId\":\"e8-plan-a\",\"openedOn\":\"2026-06-12\",\"reason\":\"Pack regalat\"}"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        String payment = "\"concept\":\"ENTRY_FEE\",\"amountDue\":{\"amountMinor\":10000,\"currency\":\"EUR\"},"
                + "\"amountPaid\":{\"amountMinor\":10000,\"currency\":\"EUR\"},\"channel\":\"CASH\",\"paidAt\":\"2026-09-24\"";
        error(as(post("/api/v1/upfront-payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"memberId\":\"e8-member-a\",\"dogId\":\"e8-dog-b\"," + payment + "}"), "ADMIN"), 404, "NOT_FOUND");
        error(as(post("/api/v1/upfront-payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"memberId\":\"e8-member-a\",\"dogId\":\"e8-dog-a\"," + payment + "}"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        var noMember = jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_MEMBER");
        var erased = jwt().jwt(j -> j.claim("clubId", CLUB).claim("memberId", "e8-member-erased")).authorities(() -> "ROLE_MEMBER");
        var foreign = jwt().jwt(j -> j.claim("clubId", CLUB).claim("memberId", "e8-member-other")).authorities(() -> "ROLE_MEMBER");
        Map<String, MockHttpServletRequestBuilder> reads = new LinkedHashMap<>();
        reads.put("GET /me/pack-balances", get("/api/v1/me/pack-balances"));
        reads.put("GET /me/inactivity-periods", get("/api/v1/me/inactivity-periods"));
        reads.put("GET /me/inactivity-periods/preview", get("/api/v1/me/inactivity-periods/preview").param("fromMonth", "2026-11"));
        reads.put("GET /me/leave-requests", get("/api/v1/me/leave-requests"));
        Map<String, MockHttpServletRequestBuilder> writes = new LinkedHashMap<>();
        writes.put("POST /me/card-setup", post("/api/v1/me/card-setup").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"successUrl\":\"https://e8-a.example.test/ok\",\"cancelUrl\":\"https://e8-a.example.test/ko\"}"));
        writes.put("POST /me/inactivity-periods", post("/api/v1/me/inactivity-periods").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content("{\"fromMonth\":\"2026-11\"}"));
        writes.put("POST /me/leave-requests", post("/api/v1/me/leave-requests").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content("{\"requestedDate\":\"2026-10-31\",\"reasonKey\":\"EXTERNAL\"}"));
        for (var route : java.util.stream.Stream.concat(reads.entrySet().stream(), writes.entrySet().stream()).toList()) {
            var response = mvc.perform(route.getValue().header("Host", HOST).with(noMember)).andReturn().getResponse();
            assertThat(response.getStatus()).as(route.getKey() + " without a member").isEqualTo(404);
            response = mvc.perform(route.getValue().header("Host", HOST).with(foreign)).andReturn().getResponse();
            assertThat(response.getStatus()).as(route.getKey() + " with another club's member").isEqualTo(404);
            response = mvc.perform(route.getValue().header("Host", HOST).with(erased)).andReturn().getResponse();
            assertThat(response.getStatus()).as(route.getKey() + " of an erased member").isEqualTo(writes.containsKey(route.getKey()) ? 409 : 501);
            if (route.getKey().equals("POST /me/card-setup")) { continue; } // Success is proved separately; this assertion covers refused writes.
            response = mvc.perform(as(route.getValue(), "MEMBER")).andReturn().getResponse();
            assertThat(response.getStatus()).as(route.getKey() + " of the member").isEqualTo(501);
        }
        var after = database();
        assertThat(after).isEqualTo(before);
    }

    /**
     * S13 §3 and R-13-04 (round 2, review #4): an open period has `toMonth: null`. Every inactivity body accepts it, and both
     * PATCH bodies keep field presence: an omitted field, `toMonth: null` (open the end) and a new end all reach the stub,
     * while `fromMonth: null`, an unknown field (a misspelt `toMonth` would otherwise be dropped silently), a malformed month
     * or a missing version are 400. What each body means is `MonthsPatch.patch()` (E8ResponseContractTest).
     */
    @Test void T_13_26_R_13_04_theInactivityBodiesTakeANullEndAndThePatchKeepsFieldPresence() throws Exception {
        for (String path : List.of("/api/v1/me/inactivity-periods/e8-period-a", "/api/v1/inactivity-periods/e8-period-a")) {
            String role = path.startsWith("/api/v1/me/") ? "MEMBER" : "ADMIN";
            for (String body : List.of("{\"version\":0}", "{\"toMonth\":null,\"version\":0}", "{\"toMonth\":\"2027-02\",\"comments\":null,\"version\":0}")) {
                error(as(patch(path).contentType("application/json").content(body), role), 501, "NOT_IMPLEMENTED");
            }
            for (String body : List.of("{\"fromMonth\":null,\"version\":0}", "{\"tomonth\":null,\"version\":0}", "{\"toMonth\":\"2027-2\",\"version\":0}",
                    "{\"toMonth\":null}")) {
                error(as(patch(path).contentType("application/json").content(body), role), 400, "VALIDATION_ERROR");
            }
        }
        error(as(post("/api/v1/me/inactivity-periods").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"fromMonth\":\"2026-11\",\"toMonth\":null,\"comments\":null}"), "MEMBER"), 501, "NOT_IMPLEMENTED");
        error(as(post("/api/v1/inactivity-periods").contentType("application/json")
                .content("{\"memberId\":\"e8-member-a\",\"fromMonth\":\"2026-11\",\"toMonth\":null}"), "ADMIN"), 501, "NOT_IMPLEMENTED");
    }

    /** S13 R-13-17 (E8-T01 publishes, E8-T05 computes): the three member filters answer 501, the rest of `GET /members` is unchanged. */
    @Test void T_13_22_theDeferredMemberFiltersAnswer501() throws Exception {
        for (String filter : List.of("leaveSource:eq:PACK_EXPIRED", "inactivityUntil:lte:2026-12-31", "hasPendingRequest:eq:true")) {
            error(as(get("/api/v1/members").param("filter", filter), "ADMIN"), 501, "NOT_IMPLEMENTED");
        }
        error(as(get("/api/v1/members/filter-values").param("field", "leaveSource"), "ADMIN"), 501, "NOT_IMPLEMENTED");
        error(as(get("/api/v1/members").param("filter", "leaveSource:eq:ADMIN"), "INSTRUCTOR"), 400, "INVALID_FILTER");
        error(as(get("/api/v1/members").param("filter", "leaveSource:between:a,b"), "ADMIN"), 400, "INVALID_FILTER");
        mvc.perform(as(get("/api/v1/members").param("filter", "status:eq:ACTIVE"), "ADMIN")).andExpect(status().isOk());
        club(CLUB, without(Module.INACTIVITY));
        error(as(get("/api/v1/members").param("filter", "inactivityUntil:lte:2026-12-31"), "ADMIN"), 400, "INVALID_FILTER");
        error(as(get("/api/v1/members").param("filter", "hasPendingRequest:eq:true"), "ADMIN"), 501, "NOT_IMPLEMENTED");
    }

    private Map<String, List<Document>> database() {
        var result = new TreeMap<String, List<Document>>();
        for (String name : mongo.getCollectionNames()) {
            if (name.equals("security_events")) { continue; }
            var documents = mongo.getCollection(name).find().sort(new Document("_id", 1)).into(new ArrayList<>());
            if (!documents.isEmpty()) { result.put(name, documents); }
        }
        return result;
    }
    @Test void T_12_21_T_13_24_theStubsWriteNothing() throws Exception {
        var before = database();
        for (Route route : stubRoutes().toList()) {
            for (String role : route.roles()) { mvc.perform(call(route, CLUB, role)).andExpect(status().isNotImplemented()); }
        }
        assertThat(database()).isEqualTo(before);
    }

    @Test void WP_12_A_WP_13_A_snapshotPublishesEveryOperationWithTypedFormsListMetadataAndCanonicalStatuses() throws Exception {
        var api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(routes().count()).isEqualTo(57);
        long s13 = routes().filter(r -> r.path().contains("inactivity") || r.path().contains("leave") || r.path().endsWith("/reactivation")).count();
        assertThat(List.of(routes().count() - s13, s13)).containsExactly(36L, 21L);
        for (Route route : routes().toList()) {
            var op = api.path("paths").path(route.path()).path(route.method().toLowerCase());
            assertThat(op.isMissingNode()).as(route.label()).isFalse();
            if (route.served()) { assertThat(op.path("description").asText()).as(route.label()).contains("Roles:").doesNotContain("Contract only"); }
            else { assertThat(op.path("description").asText()).as(route.label()).contains("Roles:", "501", "guards"); }
            assertThat(op.path("responses").has(Integer.toString(route.success()))).as(route.label()).isTrue();
            assertThat(op.path("operationId").asText()).as(route.label()).doesNotContain("_");
            // Club routes take the tenant from the JWT (or the host); only the webhook names its club in the path.
            if (route.club()) { assertThat(op.path("parameters").findValuesAsText("name")).as(route.label()).doesNotContain("clubId"); }
            var key = Stream.of(op.has("parameters") ? mapper.convertValue(op.path("parameters"), JsonNode[].class) : new JsonNode[0])
                    .filter(p -> p.path("name").asText().equals("Idempotency-Key")).findFirst();
            assertThat(key.isPresent()).as(route.label() + " Idempotency-Key").isEqualTo(route.idempotency());
            if (route.idempotency()) { assertThat(key.orElseThrow().path("required").asBoolean()).isTrue(); assertThat(key.orElseThrow().at("/schema/format").asText()).isEqualTo("uuid"); }
            // INSTRUCTOR → 403 on every route of the two verticals (MATRIU «Facturació», «Inactivitat / baixa»).
            assertThat(op.path("description").asText()).as(route.label()).satisfiesAnyOf(text -> assertThat(text).contains("INSTRUCTOR → 403"),
                    text -> assertThat(text).contains("INSTRUCTOR-only tokens → 403"), text -> assertThat(text).contains("Roles: Stripe"),
                    text -> assertThat(text).contains("Roles: the session's creator"));
        }
        assertThat(api.at("/paths/~1webhooks~1stripe~1{clubId}/post/parameters").findValuesAsText("name")).contains("clubId", "Stripe-Signature");
        // Universal lists (CONVENCIONS_API §4).
        Map<String, List<String>> filterable = Map.of("invoices", List.of("period", "status", "memberId", "paymentMethodType", "runId", "remittanceId", "issueDate", "total", "kind"),
                "remittances", List.of("period", "status"), "inactivity-periods", List.of("memberId", "state", "fromMonth", "toMonth", "origin", "requestedAt"),
                "leave-requests", List.of("memberId", "state", "source", "requestedDate", "effectiveDate", "reasonKey", "nps"));
        Map<String, List<String>> sortable = Map.of("invoices", List.of("number", "issueDate", "total", "memberLastName"), "remittances", List.of("period", "creationAt"),
                "inactivity-periods", List.of("fromMonth", "requestedAt", "memberLastName"), "leave-requests", List.of("requestedAt", "requestedDate", "effectiveDate"));
        filterable.forEach((list, fields) -> {
            var op = api.path("paths").path("/api/v1/" + list).path("get");
            assertThat(strings(op.path("x-filterable"))).as(list).containsExactlyElementsOf(fields);
            assertThat(strings(op.path("x-sortable"))).as(list).containsExactlyElementsOf(sortable.get(list));
            assertThat(op.path("parameters").findValuesAsText("name")).as(list).contains("page", "size", "sort", "filter", "fields");
        });
        assertThat(api.at("/paths/~1api~1v1~1remittances/get/parameters").findValuesAsText("name")).doesNotContain("q");
        assertThat(api.at("/paths/~1api~1v1~1invoices/get/parameters").findValuesAsText("name")).contains("q");
        assertThat(strings(api.at("/paths/~1api~1v1~1members/get/x-filterable"))).contains("leaveSource", "inactivityUntil", "hasPendingRequest");
        // Canonical statuses (CATALEG_ERRORS §1 and rule 0), whatever S12/S13 §6 write.
        assertStatus(api, "/api/v1/billing/runs", "post", "409", "RUN_EXISTS", "SIMULATION_STALE", "BILLING_BUSY", "IDEMPOTENCY_KEY_REUSED");
        assertStatus(api, "/api/v1/billing/runs", "post", "422", "COLLECTION_DATE_TOO_SOON", "NO_INVOICES", "SEPA_NOT_CONFIGURED");
        assertStatus(api, "/api/v1/billing/runs/{id}/rollback", "post", "409", "RUN_NOT_ROLLBACKABLE");
        assertStatus(api, "/api/v1/invoices/{id}/retry", "post", "409", "MAX_ATTEMPTS", "INVALID_STATE", "STALE_VERSION");
        assertStatus(api, "/api/v1/invoices/{id}/retry", "post", "422", "NO_PAYMENT_METHOD");
        assertStatus(api, "/api/v1/invoices/{id}/refund", "post", "422", "REFUND_EXCEEDS_PAID", "PAYMENT_PROVIDER_NOT_ENABLED");
        assertStatus(api, "/api/v1/upfront-payments", "post", "422", "AMOUNT_EXCEEDS_DUE", "PLAN_NOT_PACK");
        assertStatus(api, "/api/v1/pack-balances/{id}/adjustments", "post", "422", "PACK_NEGATIVE");
        assertStatus(api, "/webhooks/stripe/{clubId}", "post", "401", "WEBHOOK_SIGNATURE_INVALID");
        assertStatus(api, "/api/v1/me/inactivity-periods", "post", "409", "LEAVE_ALREADY_SCHEDULED", "INACTIVITY_OVERLAP", "IDEMPOTENCY_KEY_REUSED");
        assertStatus(api, "/api/v1/me/inactivity-periods", "post", "422", "MEMBER_NOT_ACTIVE", "INACTIVITY_DEADLINE_PASSED", "INACTIVITY_INVALID_RANGE", "INACTIVITY_NOT_APPLICABLE");
        assertStatus(api, "/api/v1/me/inactivity-periods/{id}", "patch", "409", "INACTIVITY_INVALID_STATE", "STALE_VERSION");
        assertStatus(api, "/api/v1/me/leave-requests", "post", "409", "LEAVE_ALREADY_REQUESTED", "LEAVE_ALREADY_SCHEDULED");
        assertStatus(api, "/api/v1/me/leave-requests", "post", "422", "MEMBER_NOT_ACTIVE", "LEAVE_DATE_INVALID", "LEAVE_REASON_UNKNOWN");
        assertStatus(api, "/api/v1/me/leave-requests", "post", "403", "READ_ONLY");
        assertStatus(api, "/api/v1/leave-requests/{id}/decision", "post", "409", "LEAVE_INVALID_STATE");
        assertStatus(api, "/api/v1/members/{id}/planned-leave", "delete", "422", "NO_PLANNED_LEAVE");
        assertStatus(api, "/api/v1/members/{id}/reactivation", "post", "422", "MEMBER_NOT_LEFT");
        // Security of the two non-bearer operations.
        assertThat(api.at("/paths/~1webhooks~1stripe~1{clubId}/post/security")).isEqualTo(mapper.readTree("[{\"stripeSignature\": []}]"));
        assertThat(api.at("/components/securitySchemes/stripeSignature/name").asText()).isEqualTo("Stripe-Signature");
        assertThat(api.at("/paths/~1api~1v1~1checkout-sessions~1{id}/get/security")).isEqualTo(mapper.readTree("[{}, {\"bearer\": []}]"));
        var schemas = api.at("/components/schemas");
        // Contract changes of existing operations: the checkout request gains two optional fields; S08's PackBalance is untouched.
        assertThat(strings(schemas.at("/CheckoutSessionRequest/required"))).containsExactlyInAnyOrder("memberId", "successUrl", "cancelUrl");
        assertThat(names(schemas.at("/CheckoutSessionRequest/properties"))).contains("bookingId", "upfrontPaymentIds");
        assertThat(names(schemas.at("/PackBalance/properties"))).containsExactlyInAnyOrder("available", "expiresOn");
        assertThat(strings(schemas.at("/CheckoutStatus/enum"))).containsExactly("PENDING", "PAID", "EXPIRED");
        assertThat(strings(schemas.at("/AuditAction/enum"))).contains("INVOICE_MARKED_PAID", "INVOICE_MARKED_FAILED", "INVOICE_CANCELLED", "REMITTANCE_GENERATED",
                "REMITTANCE_ROLLED_BACK", "UPFRONT_PAYMENT_RECORDED", "PAYMENT_REFUNDED", "MEMBER_PLAN_CHANGED", "INACTIVITY_RESOLVED", "LEAVE_RESOLVED", "LEAVE_CANCELLED",
                "PACK_ADJUSTED", "INVOICE_CREATED_MANUAL", "REMITTANCE_SUBMITTED", "CARD_CHARGES_STARTED");
        for (String detail : List.of("RunNotRollbackableDetails", "CollectionDateTooSoonDetails", "MaxAttemptsDetails", "InactivityDeadlineDetails",
                "InactivityOverlapDetails", "MemberLeavingDetails")) {
            assertThat(schemas.path(detail).path("properties").isEmpty()).as(detail).isFalse();
        }
        for (String item : List.of("InvoiceListItem", "RemittanceListItem", "InactivityPeriodListItem", "LeaveRequestListItem")) {
            assertThat(strings(schemas.path(item).path("required"))).as(item).containsExactly("id");
        }
        assertThat(names(schemas.at("/Creditor/properties"))).contains("maskedIban").doesNotContain("iban");
        for (String nullable : List.of("BillingPeriod/simulation", "BillingPeriod/run", "BillingPeriod/remittance", "BillingRunResult/remittance", "UpfrontPayment/provider",
                "InvoicePaymentMethod/channel", "InactivityPeriod/decision", "InactivityPeriod/feeSnapshot", "InactivityPeriod/finishReason", "MeInactivityContext/fee",
                "LeaveRequest/decision", "LeaveMember/leftReason", "MeLeaveContext/plannedLeave", "MeLeaveContext/fee")) {
            String[] parts = nullable.split("/");
            var field = schemas.path(parts[0]).path("properties").path(parts[1]);
            assertThat(field.path("anyOf").get(0).has("$ref")).as(nullable).isTrue();
            assertThat(field.path("anyOf").get(1).path("type").asText()).as(nullable).isEqualTo("null");
        }
        // Every fixture conforms to its schema: required keys present, no unknown key, closed enums, null only where nullable.
        for (String group : List.of("e8-billing-responses", "e8-lifecycle-responses")) {
            var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/" + group + ".json"));
            fixtures.fields().forEachRemaining(entry -> assertFixtureSchema(schemas, schemas.path(entry.getKey().split("#")[0]), entry.getValue(), entry.getKey()));
        }
        assertFixtureSchema(schemas, schemas.path("BillingSimulation"), mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e8-simulation.json")), "BillingSimulation");
        assertFixtureSchema(schemas, schemas.path("MeInactivityContext"), mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e8-me-inactivity-periods.json")),
                "MeInactivityContext");
    }
    private static void assertStatus(JsonNode api, String path, String method, String status, String... codes) {
        assertThat(api.path("paths").path(path).path(method).path("responses").path(status).path("description").asText()).as(method + " " + path + " " + status).contains(codes);
    }
    private void assertFixtureSchema(JsonNode schemas, JsonNode schema, JsonNode value, String path) {
        if (value.isNull()) { assertThat(nullable(schemas, schema)).as(path + " is null but not nullable").isTrue(); return; }
        if (schema.has("anyOf")) { assertFixtureSchema(schemas, schema.path("anyOf").get(0), value, path); return; }
        if (schema.has("$ref")) {
            assertFixtureSchema(schemas, schemas.path(schema.path("$ref").asText().substring("#/components/schemas/".length())), value, path); return;
        }
        assertThat(schema.isMissingNode()).as(path + " schema").isFalse();
        if (value.isObject() && schema.has("properties")) {
            assertThat(value.fieldNames()).toIterable().as(path + " required").containsAll(strings(schema.path("required")));
            value.fields().forEachRemaining(entry -> {
                assertThat(schema.path("properties").has(entry.getKey())).as(path + "." + entry.getKey()).isTrue();
                assertFixtureSchema(schemas, schema.path("properties").path(entry.getKey()), entry.getValue(), path + "." + entry.getKey());
            });
        }
        if (value.isArray() && schema.has("items")) { value.forEach(item -> assertFixtureSchema(schemas, schema.path("items"), item, path + "[]")); }
        if (schema.has("enum")) { assertThat(strings(schema.path("enum"))).as(path).contains(value.asText()); }
    }
    private static boolean nullable(JsonNode schemas, JsonNode schema) {
        if (schema.has("anyOf")) { for (var option : schema.path("anyOf")) { if ("null".equals(option.path("type").asText())) { return true; } } }
        if (strings(schema.path("type")).contains("null") || schema.path("nullable").asBoolean()) { return true; }
        if (schema.has("$ref")) { return strings(schemas.path(schema.path("$ref").asText().substring("#/components/schemas/".length())).path("type")).contains("null"); }
        return false;
    }
    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        if (node.isArray()) { node.forEach(v -> values.add(v.asText())); } else if (!node.isMissingNode()) { values.add(node.asText()); }
        return values;
    }
    private static List<String> names(JsonNode node) { var values = new ArrayList<String>(); node.fieldNames().forEachRemaining(values::add); return values; }
}

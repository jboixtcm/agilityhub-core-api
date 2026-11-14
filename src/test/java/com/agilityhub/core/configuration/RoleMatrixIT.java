package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.application.ImpersonationService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * REL-06 (owner decision of 13 Nov 2026): the bearer routes that no other route fixture covered, with the roles MATRIU_PERMISOS
 * gives them ({@code fixtures/contracts/role-matrix-routes.json}; the row each one reads is its {@code matrix} field, and the
 * routes where that reading is unclear are listed in FIXES.md under REL-06), tested as the matrix's rule 5 asks: without a bearer
 * 401; every role the matrix allows passes the role guard and every other role is refused with 403; a token of another club on the
 * club's host is refused (TENANT_MISMATCH), while a global route ignores the host; and an impersonation token (rule 3: the member,
 * never on an ADMIN route) reaches only the routes that act for the member. An entry whose {@code current} differs from its
 * {@code roles} is a permission the matrix does not give, kept as the code has it until the owner decides (REL-06: reported, not
 * changed); {@link RoleMatrixCoverageTest} keeps those entries honest and every published bearer operation in some fixture.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleMatrixIT extends AbstractIntegrationTest {
    static final String CLUB = "role-matrix-a", OTHER = "role-matrix-b", HOST = "role-matrix-a.example.test", OTHER_HOST = "role-matrix-b.example.test";
    /** A host of no club, where the platform's own tokens (no club) call the account and platform routes. */
    static final String PLATFORM_HOST = "id.agilitydoghub.com";
    /** The role accounts' fictional password (a live session is opened with it for the routes that need one). */
    static final String PASSWORD = "Fictional role matrix passphrase";
    /** {@code MEMBER_ID}: the member the impersonation grants name; {@code OWN_MEMBER_ID}: the MEMBER token's own member. */
    static final String MEMBER_ID = "role-matrix-member", OWN_MEMBER_ID = "role-matrix-own-member", MISSING = "role-matrix-missing";
    static final List<String> ROLES = List.of("MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN");
    /** The codes of the guards a route runs before its own code: authentication, roles, impersonation and tenant. */
    static final Set<String> GUARDS = Set.of("UNAUTHENTICATED", "FORBIDDEN", "IMPERSONATION_DENIED", "TENANT_MISMATCH", "NO_MEMBERSHIP");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired MongoTemplate mongo;
    @Autowired ImpersonationService impersonations;
    @Autowired com.agilityhub.core.identity.application.TokenService tokens;
    @Autowired com.agilityhub.core.identity.application.PasswordHasher hasher;
    boolean prepared;

    /**
     * One row of the fixture. {@code roles}: what the matrix gives; {@code current}: only when the code admits other roles (a
     * reported discrepancy); {@code scope}: CLUB (the host's club must be the token's) or GLOBAL (no tenant: the console, the
     * platform's account administration, the OIDC session); {@code denial}: the code a refused role gets (FORBIDDEN by default);
     * {@code headers}, {@code cookies} and {@code token} ({@code audience}, {@code club: false} for a token without a club): what a
     * route asks of every caller before it looks at roles, so that an allowed role reaches its own answer; {@code session}: the
     * route works on the caller's live session (its refresh family), so each role calls it with a session opened by a password
     * sign-in (the club app for MEMBER, the back office for staff, the console for the platform administrator).
     */
    record Route(String method, String path, List<String> roles, List<String> current, String scope, boolean impersonation,
                 Boolean currentImpersonation, boolean idempotency, boolean session, JsonNode body, Map<String, String> params, Map<String, String> headers,
                 Map<String, String> cookies, Token token, List<String> scopes, String denial, String matrix, String question) {
        record Token(List<String> audience, Boolean club) { }
        boolean clubless() { return token != null && Boolean.FALSE.equals(token.club()); }
        List<String> admitted() { return current != null ? current : roles; }
        boolean admitsImpersonation() { return currentImpersonation != null ? currentImpersonation : impersonation; }
        boolean club() { return scope.equals("CLUB"); }
        String refusal() { return denial != null ? denial : "FORBIDDEN"; }
        String label() { return method + " " + path + (body != null && body.has("purpose") ? " " + body.path("purpose").asText()
                : body != null && body.has("entityType") ? " " + body.path("entityType").asText()
                : body != null && body.has("targetClientId") ? " to " + body.path("targetClientId").asText()
                : body != null && body.has("bookingsOnly") ? " bookingsOnly" : ""); }
        @Override public String toString() { return label(); }
    }

    static Stream<Route> routes() throws Exception {
        try (var input = RoleMatrixIT.class.getResourceAsStream("/fixtures/contracts/role-matrix-routes.json")) {
            return Arrays.stream(new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                    .readValue(input, Route[].class));
        }
    }

    @BeforeEach void prepare() {
        if (prepared) { return; }
        clean();
        for (String clubId : List.of(CLUB, OTHER)) {
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
            tree.set("modules", mapper.valueToTree(Module.values()));
            clubs.save(mapper.convertValue(tree, Club.class));
            configs.invalidate(clubId);
        }
        hosts.invalidate();
        // The role tokens' accounts (the identity routes read the caller's account): one membership of its role in the club each;
        // the platform administrator's account has the platform role and no membership.
        for (String role : ROLES) {
            String id = "role-matrix-" + role.toLowerCase();
            mongo.save(new Account(id, id + "@example.test", "Example " + role.toLowerCase(), "ca", hasher.hash(PASSWORD),
                    role.equals("AGILITYHUB_ADMIN") ? Set.of(Account.PlatformRole.AGILITYHUB_ADMIN) : Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0),
                    Map.of(), false, clock.instant()));
            if (!role.equals("AGILITYHUB_ADMIN")) {
                var membership = Role.valueOf(role);
                mongo.save(new Membership(id, id, CLUB, role.equals("MEMBER") ? OWN_MEMBER_ID : null, Set.of(membership), Membership.Status.ACTIVE, membership));
            }
        }
        mongo.save(new Document("_id", OWN_MEMBER_ID).append("clubId", CLUB).append("accountId", "role-matrix-member").append("status", "ACTIVE")
                .append("firstName", "Pau").append("lastName1", "Example").append("memberNumber", 902).append("version", 0), "members");
        // Rule 3's impersonation: an ADMIN of the club and a member with an account, so that a real grant can be issued.
        for (var role : List.of(Role.ADMIN, Role.MEMBER)) {
            String id = "role-matrix-imp-" + role.name().toLowerCase();
            mongo.save(new Account(id, id + "@example.test", "Example " + role.name().toLowerCase(), "ca", null, Set.of(), Account.Status.ACTIVE, null,
                    Map.of(), false, clock.instant()));
            mongo.save(new Membership(id, id, CLUB, role == Role.MEMBER ? MEMBER_ID : null, Set.of(role), Membership.Status.ACTIVE, role));
        }
        mongo.save(new Document("_id", MEMBER_ID).append("clubId", CLUB).append("accountId", "role-matrix-imp-member").append("status", "ACTIVE")
                .append("firstName", "Laia").append("lastName1", "Example").append("memberNumber", 901).append("version", 0), "members");
        prepared = true;
    }

    @AfterAll void clean() {
        for (String collection : mongo.getCollectionNames()) {
            if (BOOTSTRAP_COLLECTIONS.contains(collection) || collection.startsWith("system.")) { continue; }
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
            // The platform administrator's console session and its traces name no club, only the account.
            mongo.remove(Query.query(Criteria.where("accountId").regex("^role-matrix-")), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        mongo.remove(Query.query(Criteria.where("_id").regex("^role-matrix-")), "accounts");
        configs.invalidate(CLUB); configs.invalidate(OTHER); hosts.invalidate();
    }

    private MockHttpServletRequestBuilder call(Route route, String host) throws Exception {
        String path = route.path().replace("{clubId}", CLUB).replaceAll("\\{[^}]+}", MISSING);
        var request = request(HttpMethod.valueOf(route.method()), path).header("Host", host);
        if (route.body() != null && !route.body().isNull()) { request.contentType("application/json").content(mapper.writeValueAsString(route.body())); }
        route.params().forEach(request::param);
        if (route.headers() != null) { route.headers().forEach(request::header); }
        if (route.cookies() != null) { route.cookies().forEach((name, value) -> request.cookie(new jakarta.servlet.http.Cookie(name, value))); }
        if (route.idempotency()) { request.header("Idempotency-Key", UUID.randomUUID().toString()); }
        return request;
    }

    /** The host a role's token is used on: the platform administrator's own token (no club) on a host of no club. */
    private static String host(Route route, String role) { return platformToken(route, role) ? PLATFORM_HOST : HOST; }

    /**
     * The platform administrator calls the routes the matrix gives it with its own token, which names no club (S17); on the
     * others it is tested with the club in its token, the strongest case: even inside a club it is not that club's ADMIN.
     */
    private static boolean platformToken(Route route, String role) { return role.equals("AGILITYHUB_ADMIN") && route.admitted().contains(role); }

    /** A live session of {@code role}'s account (password sign-in), for a route that works on the caller's refresh family. */
    private org.springframework.security.oauth2.jwt.Jwt session(String role) {
        String email = "role-matrix-" + role.toLowerCase() + "@example.test";
        if (role.equals("AGILITYHUB_ADMIN")) { return tokens.password(email, PASSWORD, "clubs-admin").access(); }
        try (var tenant = TenantContext.open(CLUB)) { return tokens.password(email, PASSWORD, role.equals("MEMBER") ? "clubs-app" : "clubs-admin").access(); }
    }

    /** A bearer of {@code role} in {@code clubId} (null: a token without a club), signed in now (S01 R-01-18), with the route's scopes. */
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Route route, String role, String clubId) {
        var authorities = new ArrayList<GrantedAuthority>(List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        if (route.scopes() != null) { route.scopes().forEach(scope -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope))); }
        if (route.session() && CLUB.equals(clubId) || route.session() && platformToken(route, role)) { return request.with(jwt().jwt(session(role)).authorities(authorities)); }
        boolean clubless = route.clubless() || platformToken(route, role);
        return request.with(jwt().jwt(token -> {
            token.subject("role-matrix-" + role.toLowerCase()).claim("auth_time", signedInNow());
            if (clubId != null && !clubless) { token.claim("clubId", clubId); }
            if (role.equals("MEMBER") && !clubless) { token.claim("memberId", OWN_MEMBER_ID); }
            if (route.token() != null && route.token().audience() != null) { token.audience(route.token().audience()); }
        }).authorities(authorities));
    }

    private String describe(MvcResult result) throws Exception {
        return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private String code(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body.isBlank() || !body.trim().startsWith("{")) { return ""; }
        return mapper.readTree(body).path("code").asText();
    }

    /** Past every guard: the route's own answer (a success, a business error, or 501 for a published stub), never a 500. */
    private void passes(SoftAssertions soft, String label, MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        soft.assertThat(status == 401 || status == 500 || status == 403 && GUARDS.contains(code(result))).as(label + " passes the guards: " + describe(result)).isFalse();
    }

    private void refused(SoftAssertions soft, String label, MvcResult result, String... codes) throws Exception {
        soft.assertThat(result.getResponse().getStatus()).as(label + ": " + describe(result)).isEqualTo(403);
        soft.assertThat(code(result)).as(label + ": " + describe(result)).isIn((Object[]) codes);
    }

    @ParameterizedTest(name = "{0}") @MethodSource("routes")
    void REL_06_theMatrixRolesPassTheRoleGuardAndEveryOtherRoleIsRefused(Route route) throws Exception {
        var soft = new SoftAssertions();
        var anonymous = mvc.perform(call(route, HOST)).andReturn();
        soft.assertThat(anonymous.getResponse().getStatus()).as(route + " without a bearer: " + describe(anonymous)).isEqualTo(401);
        soft.assertThat(code(anonymous)).as(route + " without a bearer").isEqualTo("UNAUTHENTICATED");
        for (String role : ROLES) {
            var result = mvc.perform(as(call(route, host(route, role)), route, role, CLUB)).andReturn();
            if (route.admitted().contains(role)) { passes(soft, route + " as " + role, result); }
            else { refused(soft, route + " as " + role, result, route.refusal()); }
        }
        String role = route.admitted().stream().filter(admitted -> !admitted.equals("AGILITYHUB_ADMIN")).findFirst().orElse("AGILITYHUB_ADMIN");
        if (route.club() && !role.equals("AGILITYHUB_ADMIN")) {
            // Another club's token on this club's host: the tenant guard refuses it before the route (CONVENCIONS_API §2).
            refused(soft, route + " with another club's " + role, mvc.perform(as(call(route, HOST), route, role, OTHER)).andReturn(), "TENANT_MISMATCH");
        } else if (!route.club() && !route.session()) {
            // A global route takes no tenant from the host: the platform's token, without a club, passes its guards on a club's host.
            role = route.admitted().contains("AGILITYHUB_ADMIN") ? "AGILITYHUB_ADMIN" : role;
            if (role.equals("AGILITYHUB_ADMIN")) {
                passes(soft, route + " global, " + role + " on a club's host", mvc.perform(as(call(route, OTHER_HOST), route, role, null)).andReturn());
            }
        }
        soft.assertAll();
    }

    /** Rule 3: the impersonation token is the member (MEMBER of that member, audited), and never reaches an ADMIN route. */
    @Test void REL_06_theImpersonationTokenReachesOnlyTheRoutesThatActForTheMember() throws Exception {
        var soft = new SoftAssertions();
        for (Route route : routes().toList()) {
            ImpersonationService.Issued issued;
            try (var tenant = TenantContext.open(CLUB)) { issued = impersonations.create("role-matrix-imp-admin", MEMBER_ID, "Role matrix test"); }
            var result = mvc.perform(call(route, HOST).with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"))).andReturn();
            if (route.admitsImpersonation()) { passes(soft, route + " impersonated", result); }
            else { refused(soft, route + " impersonated", result, "IMPERSONATION_DENIED", "FORBIDDEN"); }
        }
        soft.assertAll();
    }
}

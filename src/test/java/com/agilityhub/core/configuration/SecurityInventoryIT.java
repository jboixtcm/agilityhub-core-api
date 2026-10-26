package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.application.SupportAccessService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.api.RateLimitFilter;
import com.agilityhub.core.shared.api.SignedFileRequests;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.SupportRestricted;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** INC-45: published authentication versus the real security chains; includes E8 (E11-T06). */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SecurityInventoryIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;

    /** Both tests share the context's database: the second one finds the club already stored (a versioned save would insert it twice). */
    private void inventoryClub() {
        if (clubs.findById("security-inventory").isEmpty()) { clubs.save(PlatformFixtures.club("security-inventory", "inventory.example.test")); }
        configs.invalidate("security-inventory"); hosts.invalidate();
    }

    @Test void E11_T06_INC45_everyPublishedOperationEnforcesItsBearerRequirement() throws Exception {
        inventoryClub();
        var published = mapper.readTree(Files.readString(Path.of("docs/openapi/openapi.json")));
        var live = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var failures = new org.assertj.core.api.SoftAssertions();
        int checked = 0;
        for (var path : published.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (!Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(method.getKey())) { continue; }
                String label = method.getKey().toUpperCase() + " " + path.getKey();
                JsonNode requirement = effective(published, method.getValue());
                failures.assertThat(effective(live, live.path("paths").path(path.getKey()).path(method.getKey())))
                        .as(label + " live security").isEqualTo(requirement);
                boolean requiresBearer = !requirement.isEmpty();
                // E9-T04 (R-16-11): the build session stream's `access_token` query scheme is the same bearer token in another place.
                for (var alternative : requirement) { requiresBearer &= alternative.has("bearer") || alternative.has(E9ContractConfiguration.ACCESS_TOKEN_QUERY); }
                var request = request(HttpMethod.valueOf(method.getKey().toUpperCase()), path.getKey().replaceAll("\\{[^}]+}", "inventory-missing"))
                        .header("Host", "inventory.example.test").contentType("application/json");
                if (path.getKey().equals("/oauth2/token")) {
                    request.contentType("application/x-www-form-urlencoded").param("grant_type", "password")
                            .param("username", "inventory@example.test").param("password", "fictional-invalid-password");
                }
                if (path.getKey().equals("/webhooks/stripe/{clubId}") || path.getKey().equals("/webhooks/email/brevo")) request.content("{}");
                var response = mvc.perform(request).andReturn().getResponse();
                if (path.getKey().equals("/webhooks/stripe/{clubId}") || path.getKey().equals("/webhooks/email/brevo")) {
                    failures.assertThat(response.getStatus()).as(label + " requires provider authentication").isEqualTo(401);
                    failures.assertThat(mapper.readTree(response.getContentAsString()).path("code").asText())
                            .isEqualTo("WEBHOOK_SIGNATURE_INVALID");
                } else if (path.getKey().equals("/api/v1/checkout-sessions/{id}")) {
                    failures.assertThat(response.getStatus()).as(label + " requires signup capability").isEqualTo(401);
                    failures.assertThat(mapper.readTree(response.getContentAsString()).path("code").asText())
                            .isEqualTo("UNAUTHENTICATED");
                } else if (!path.getKey().equals("/oauth2/token")) {
                    failures.assertThat(response.getStatus() == 401).as(label + " without bearer -> " + response.getStatus())
                            .isEqualTo(requiresBearer);
                } else {
                    failures.assertThat(response.getStatus()).as(label + " requires client authentication").isEqualTo(401);
                }
                checked++;
            }
        }
        int publishedCount = 0;
        for (var path : published.path("paths")) {
            for (String method : Set.of("get", "post", "put", "patch", "delete", "head", "options")) {
                if (path.has(method)) publishedCount++;
            }
        }
        assertThat(checked).as("every published operation including S12/S13").isEqualTo(publishedCount);
        System.out.println("E11-T06 security inventory: " + checked + " / " + publishedCount + " operations");
        failures.assertAll();
    }

    /**
     * Published anonymous writes that {@link RateLimitFilter} deliberately leaves without a quota. Empty: every one has a route
     * today. An entry needs its reason here (for instance, a write that another limit already bounds).
     */
    private static final Set<String> ANONYMOUS_WRITES_WITHOUT_QUOTA = Set.of();

    /**
     * INC-67 (E11-T08): every published operation that a caller can reach without a bearer and that is not a `GET` falls in a
     * rate-limit family of {@link RateLimitFilter} ({@link RateLimits.Route}), or is on the documented exempt list above. A new
     * anonymous write added without a quota fails here, not only in the hand-written list of `AnonymousRateLimitsTest`.
     */
    @Test void INC_67_everyPublishedAnonymousWriteHasARateLimitRoute() throws Exception {
        var published = mapper.readTree(Files.readString(Path.of("docs/openapi/openapi.json")));
        var failures = new org.assertj.core.api.SoftAssertions();
        int anonymousWrites = 0;
        for (var path : published.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (!Set.of("post", "put", "patch", "delete").contains(method.getKey())) { continue; }
                JsonNode requirement = effective(published, method.getValue());
                boolean requiresBearer = !requirement.isEmpty();
                for (var alternative : requirement) { requiresBearer &= alternative.has("bearer") || alternative.has(E9ContractConfiguration.ACCESS_TOKEN_QUERY); }
                if (requiresBearer) { continue; }
                String label = method.getKey().toUpperCase() + " " + path.getKey();
                anonymousWrites++;
                if (ANONYMOUS_WRITES_WITHOUT_QUOTA.contains(label)) { continue; }
                RateLimits.Route route = RateLimitFilter.route(method.getKey().toUpperCase(), path.getKey().replaceAll("\\{[^}]+}", "inventory-missing"), false);
                failures.assertThat(route).as(label + " anonymous write has a rate-limit route").isNotNull();
            }
        }
        assertThat(anonymousWrites).as("the inventory found the anonymous writes").isPositive();
        System.out.println("INC-67 anonymous writes with a rate-limit route: " + anonymousWrites);
        failures.assertAll();
    }

    /**
     * INC-45 (E11-T06): the routes a URL signature authorises ({@link SignedFileRequests}) are hidden from the published contract,
     * so the walk above never reaches them. Without `expires` and `signature` each one is refused before its service runs: the
     * framework's missing-parameter 400 keeps its status and takes the catalog's `VALIDATION_ERROR` (ApiExceptionHandler,
     * CATALEG_ERRORS §1). Never a file and never a 401: no bearer is read there, so an invalid or an authenticated one changes nothing.
     */
    @Test void E11_T06_INC45_hiddenSignedRoutesRefuseAnUnsignedRequest() throws Exception {
        inventoryClub();
        var published = mapper.readTree(Files.readString(Path.of("docs/openapi/openapi.json"))).path("paths");
        // One concrete path per SignedFileRequests pattern: UPLOAD, both DOWNLOAD alternatives and the two signup paths.
        String[][] routes = {
                {"PUT", "/api/v1/attachments/uploads/{id}", "/api/v1/attachments/uploads/inventory-upload"},
                {"GET", "/api/v1/attachments/files/{id}", "/api/v1/attachments/files/inventory-file"},
                {"GET", "/api/v1/remittances/files/{clubId}/{id}", "/api/v1/remittances/files/security-inventory/inventory-remittance"},
                {"GET", "/api/v1/migrations/files/{clubId}/{id}", "/api/v1/migrations/files/security-inventory/inventory-run"},
                {"PUT", "/api/v1/signup/uploads", "/api/v1/signup/uploads"},
                {"GET", "/api/v1/signup/files", "/api/v1/signup/files"}};
        var failures = new org.assertj.core.api.SoftAssertions();
        for (String[] route : routes) {
            String label = route[0] + " " + route[2];
            failures.assertThat(SignedFileRequests.matches(new MockHttpServletRequest(route[0], route[2]))).as(label + " is signed").isTrue();
            failures.assertThat(published.has(route[1])).as(label + " is hidden").isFalse();
            for (String bearer : List.of("no bearer", "an invalid bearer", "the club ADMIN's bearer")) {
                var request = request(HttpMethod.valueOf(route[0]), route[2]).header("Host", "inventory.example.test").contentType("application/json");
                // The signup routes name their file in `fileKey`; only the signature and its expiry are missing.
                if (route[2].startsWith("/api/v1/signup/")) request.param("fileKey", "inventory-file-key");
                if (bearer.equals("an invalid bearer")) request.header("Authorization", "Bearer not-a-token");
                if (bearer.equals("the club ADMIN's bearer")) {
                    request.with(jwt().jwt(j -> j.subject("inventory-admin").claim("clubId", "security-inventory"))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
                }
                var response = mvc.perform(request).andReturn().getResponse();
                String body = response.getContentAsString();
                failures.assertThat(response.getStatus()).as(label + " unsigned with " + bearer + ": " + body).isEqualTo(400);
                failures.assertThat(mapper.readTree(body.isEmpty() ? "{}" : body).path("code").asText())
                        .as(label + " unsigned with " + bearer).isEqualTo("VALIDATION_ERROR");
            }
        }
        failures.assertAll();
    }

    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    /**
     * The shapes of the routes S17 R-17-10 closes to a support token, written apart from {@link SupportAccessService}'s own
     * pattern so that a new export, file, impersonation or erasure route that the pattern misses fails here: the personal-data
     * exports and their files, the SEPA files, a member's impersonation, the scheduling and cancelling of a member's erasure
     * (E11-T16), the account's own credentials and sessions, and the club's end of a support access.
     */
    static final java.util.regex.Pattern RESTRICTED_SHAPES = java.util.regex.Pattern.compile("(GET|POST) /api/v1/.*export.*|GET /api/v1/.*/files?(/.*)?"
            + "|POST /api/v1/.*/impersonation-token|(POST|DELETE) /api/v1/members/\\{[^}]+}/erasure"
            + "|(PUT|PATCH|DELETE|POST) /api/v1/me(/password|/sessions(/.*)?|/onboarding(/.*)?)?|DELETE /api/v1/support-accesses/.*");
    /** Published operations of those shapes that are not a club's personal data, each with its reason. */
    static final java.util.Map<String, String> SHAPED_BUT_OPEN = java.util.Map.of(
            "GET /api/v1/build-sessions/{id}/export", "S16 §14.1: the course's geometry for the Unity app, no personal data",
            "GET /api/v1/public/{clubSlug}/activities/{slug}/files/{fileId}", "anonymous: an activity's public file, no bearer is read");

    /**
     * S17 R-17-10 (E11-T16 step 3, audit 21-10 S3): the support restriction is the same list in its three places. Every published
     * operation of a restricted shape is matched by {@link SupportAccessService#restricted} (the request filter) and its handler is
     * {@code @SupportRestricted}, so it refuses a support token on its own as `CurrentUserFilter`'s Javadoc promises; every
     * operation the filter restricts has an annotated handler; and no annotated handler, published or hidden (the signed SEPA
     * file), is outside the filter's pattern.
     */
    @Test void T_17_09_R_17_10_everyRestrictedShapeIsFilteredAndRefusedByItsHandler() throws Exception {
        var published = mapper.readTree(Files.readString(Path.of("docs/openapi/openapi.json")));
        // `METHOD pattern` → its handlers (one route can have several, by `params` or `produces`): annotated = every one of them.
        var handlers = new java.util.HashMap<String, List<org.springframework.web.method.HandlerMethod>>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    handlers.computeIfAbsent(method.name() + " " + pattern, key -> new java.util.ArrayList<>()).add(entry.getValue());
                }
            }
        }
        var failures = new org.assertj.core.api.SoftAssertions();
        var restricted = new java.util.TreeSet<String>();
        for (var path : published.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(method.getKey())) { continue; }
                String label = method.getKey().toUpperCase() + " " + path.getKey();
                String sample = path.getKey().replaceAll("\\{[^}]+}", "inventory-missing");
                boolean filtered = SupportAccessService.restricted(method.getKey().toUpperCase(), sample);
                var routed = handlers.getOrDefault(label, List.of());
                boolean annotated = !routed.isEmpty() && routed.stream().allMatch(handler -> handler.hasMethodAnnotation(SupportRestricted.class));
                boolean anyAnnotated = routed.stream().anyMatch(handler -> handler.hasMethodAnnotation(SupportRestricted.class));
                if (RESTRICTED_SHAPES.matcher(label).matches() && !SHAPED_BUT_OPEN.containsKey(label)) {
                    failures.assertThat(filtered).as(label + " has a restricted shape: the filter closes it").isTrue();
                    failures.assertThat(annotated).as(label + " has a restricted shape: its handler is @SupportRestricted").isTrue();
                }
                if (filtered) {
                    restricted.add(label);
                    failures.assertThat(annotated).as(label + " is filtered: its handler is @SupportRestricted").isTrue();
                }
                if (anyAnnotated) { failures.assertThat(filtered).as(label + " is @SupportRestricted: the filter closes it too").isTrue(); }
            }
        }
        for (var entry : handlers.entrySet()) {
            if (entry.getValue().stream().noneMatch(handler -> handler.hasMethodAnnotation(SupportRestricted.class))) { continue; }
            String method = entry.getKey().substring(0, entry.getKey().indexOf(' ')), pattern = entry.getKey().substring(entry.getKey().indexOf(' ') + 1);
            failures.assertThat(SupportAccessService.restricted(method, pattern.replaceAll("\\{[^}]+}", "inventory-missing")))
                    .as(entry.getKey() + " is @SupportRestricted: the filter's pattern names it").isTrue();
        }
        System.out.println("E11-T16 support-restricted operations (" + restricted.size() + "):");
        restricted.forEach(label -> System.out.println("  " + label));
        assertThat(restricted).as("the published operations closed to a support token").hasSizeGreaterThanOrEqualTo(25);
        failures.assertAll();
    }

    private static JsonNode effective(JsonNode document, JsonNode operation) {
        return operation.has("security") ? operation.path("security") : document.path("security");
    }
}

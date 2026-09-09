package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class E2ContractIT extends AbstractIntegrationTest {
    static final String HOST = "e2-a.example.test";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    boolean prepared;

    record Route(String method, String path, List<String> roles, boolean global, String module,
                 JsonNode body, Map<String, String> params, Map<String, String> headers, boolean rejectImpersonation) { }

    static Stream<Route> routes() throws Exception {
        try (var input = E2ContractIT.class.getResourceAsStream("/fixtures/contracts/e2-routes.json")) {
            return List.of(new ObjectMapper().readValue(input, Route[].class)).stream();
        }
    }

    static Stream<Route> pendingRoutes() throws Exception {
        return routes().filter(route -> !(route.path().startsWith("/api/v1/members") && !route.path().contains("audit-entries") && !route.path().contains("data-export") && !route.path().contains("erasure"))
                && !route.path().startsWith("/api/v1/dogs")
                && !route.path().startsWith("/api/v1/family-groups")
                && !route.path().startsWith("/api/v1/me/dogs")
                && !route.path().equals("/api/v1/me/profile")
                && !route.path().equals("/api/v1/me/family-group") && !route.path().startsWith("/api/v1/exports")
                && !route.path().startsWith("/api/v1/saved-views")
                && !(route.method().equals("GET") && Set.of("/api/v1/members", "/api/v1/dogs", "/api/v1/members/filter-values", "/api/v1/dogs/filter-values", "/api/v1/members/export", "/api/v1/dogs/export").contains(route.path()))
                && !route.path().startsWith("/api/v1/plans")
                && !route.path().startsWith("/api/v1/prices")
                && !route.path().equals("/api/v1/public/{clubSlug}/plans")
                && !route.path().startsWith("/api/v1/instructors")
                && !route.path().startsWith("/api/v1/administrators")
                && !(route.method().equals("PUT") && route.path().equals("/api/v1/members/{id}/roles"))
                && !route.path().startsWith("/api/v1/levels")
                && !route.path().startsWith("/api/v1/rings")
                && !route.path().startsWith("/api/v1/faq-entries")
                && !route.path().startsWith("/api/v1/parameters")
                && !route.path().startsWith("/api/v1/club")
                && !route.path().startsWith("/api/v1/country-profile")
                && !route.path().equals("/api/v1/platform/parameter-catalog"));
    }

    @BeforeEach void prepareClubs() {
        if (prepared) { return; }
        var tree = mapper.valueToTree(PlatformFixtures.club("e2-club-a", HOST));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set("modules", mapper.valueToTree(Module.values()));
        clubs.save(mapper.convertValue(tree, Club.class));
        var other = mapper.valueToTree(PlatformFixtures.club("e2-club-b", "e2-b.example.test"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) other).set("modules", mapper.createArrayNode());
        clubs.save(mapper.convertValue(other, Club.class));
        configs.invalidate("e2-club-a"); configs.invalidate("e2-club-b"); hosts.invalidate();
        prepared = true;
    }

    private MockHttpServletRequestBuilder call(Route route) throws Exception { return call(route, HOST); }

    private MockHttpServletRequestBuilder call(Route route, String host) throws Exception {
        String path = route.path().replaceAll("\\{[^}]+}", "fixture-id");
        var request = request(HttpMethod.valueOf(route.method()), path).header("Host", host);
        if (route.body() != null && !route.body().isNull()) {
            request.contentType("application/json").content(mapper.writeValueAsString(route.body()));
        }
        route.params().forEach(request::param);
        route.headers().forEach(request::header);
        return request;
    }

    @ParameterizedTest(name = "{0}") @MethodSource("pendingRoutes")
    void T_03_33_T_14_22_everyE2ContractEnforcesRolesTenantAndPendingResponse(Route route) throws Exception {
        var anonymous = mvc.perform(call(route));
        if (route.roles().isEmpty()) {
            anonymous.andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
            if (route.global()) {
                mvc.perform(call(route, "external.example.test")).andExpect(status().isNotImplemented());
            }
            return;
        }
        anonymous.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN")) {
            boolean allowed = route.roles().contains(role);
            mvc.perform(call(route).with(jwt().jwt(j -> j.subject(route.method() + route.path() + role).claim("clubId", "e2-club-a"))
                            .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().is(allowed ? 501 : 403))
                    .andExpect(jsonPath("$.code").value(allowed ? "NOT_IMPLEMENTED" : "FORBIDDEN"))
                    .andExpect(jsonPath("$.length()").value(4))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
        var authority = new SimpleGrantedAuthority("ROLE_" + route.roles().getFirst());
        mvc.perform(call(route, "e2-b.example.test")
                        .with(jwt().jwt(j -> j.claim("clubId", "e2-club-a")).authorities(authority)))
                .andExpect(status().is(route.global() ? 501 : 403))
                .andExpect(jsonPath("$.code").value(route.global() ? "NOT_IMPLEMENTED" : "TENANT_MISMATCH"));
        mvc.perform(call(route, "id.example.test")
                        .with(jwt().authorities(authority)))
                .andExpect(status().is(route.global() ? 501 : 403));
        if (route.rejectImpersonation()) {
            // A synthetic imp claim has no persisted grant; real grant role rejection is covered by T-01-11.
            mvc.perform(call(route).with(jwt().jwt(j -> j.claim("clubId", "e2-club-a").claim("imp", true)).authorities(authority)))
                    .andExpect(status().is(route.global() ? 403 : 401));
        }
        if (route.module() != null) {
            mvc.perform(call(route, "e2-b.example.test")
                            .with(jwt().jwt(j -> j.claim("clubId", "e2-club-b")).authorities(authority)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        }
    }

    @Test void T_02_06_postalLookupRequiresHostContextAndPublicPlansUseTheirApiKeyHeader() throws Exception {
        mvc.perform(get("/api/v1/country-profile/postal-codes/08001").header("Host", "unknown.example.test"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        mvc.perform(get("/api/v1/country-profile/postal-codes/08001").header("Host", HOST))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/e2-club-a/plans")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
        mvc.perform(get("/api/v1/public/missing-club/plans").header("X-Api-Key", "fictional"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLUB_NOT_FOUND"));
    }

    @Test void T_03_22_T_05_01_T_14_13_allRoutesAndListCapabilitiesArePublished() throws Exception {
        JsonNode api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (Route route : routes().toList()) {
            var operation = api.path("paths").path(route.path()).path(route.method().toLowerCase());
            assertThat(operation.isMissingNode()).as(route.method() + " " + route.path()).isFalse();
            assertThat(operation.path("summary").asText()).isNotBlank();
            assertThat(operation.path("description").asText()).isNotBlank();
            assertThat(operation.path("parameters").findValuesAsText("name")).doesNotContain("clubId");
            if (route.body() != null && !route.body().isNull()) {
                assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText()).isNotBlank();
            }
        }
        var schemas = api.at("/components/schemas");
        for (String name : List.of("Member", "MemberListItem", "MemberInstructorView", "MemberOverview", "Dog", "DogListItem",
                "FamilyGroup", "MeDogs", "MeProfile", "SavedView", "Level", "Ring", "Instructor", "Administrator", "Plan", "Price",
                "PublicPlan", "FaqEntry", "Parameter", "ParameterHistoryEntry", "ClubSettings", "AuditEntry", "AuditEntryListItem", "ExportJob", "ListPage")) {
            assertThat(schemas.path(name).path("properties").isEmpty()).as(name).isFalse();
        }
        var lists = Map.of("members", List.of("lastName", "firstName", "memberNumber", "joinedAt", "leaveDate", "nextInvoiceDate", "city"),
                "dogs", List.of("name", "breed", "levelOrder", "ownerLastName", "registeredAt", "levelAssignedAt"), "audit-entries", List.of("at"));
        var filters = Map.of(
                "members", "id,memberNumber,lastName,fullName,status,displayStatus,planId,priceId,paymentMethodType,nextInvoiceDate,joinedAt,leaveDate,bookingBlocked,familyGroupId,imageRightsGranted,roles,city,postalCode,dogLevelId,dogName,hasPendingDocuments,freeTrainingAllowed,gender,birthDate",
                "dogs", "id,name,breed,levelId,memberId,ownerName,handlerName,status,freeTrainingAllowed,hasLicense,licenseOrganisation,hasPendingDocuments,sex,birthDate,chip,registeredAt,levelAssignedAt",
                "audit-entries", "at,action,entityType,entityId,memberId,actorAccountId,actorRole,impersonatedMemberId,origin");
        var columns = Map.of(
                "members", "fullName,dogs,plan,displayStatus,memberNumber,contact,paymentMethod,nextInvoiceDate,familyGroup,joinedAt,leaveDate,bookingBlocked,imageRights,roles,city,postalCode,pendingDocuments,freeTraining,birthDate,gender,idDocument",
                "dogs", "name,breed,level,owner,handler,freeTraining,licenses,displayStatus,sex,age,chip,pendingDocuments,levelAssignedAt,pack,registeredAt",
                "audit-entries", "at,action,entityLabel,actorName,impersonatedName,changes,origin");
        lists.forEach((resource, sort) -> {
            var operation = api.path("paths").path("/api/v1/" + resource).path("get");
            assertThat(strings(operation.path("x-sortable"))).containsExactlyElementsOf(sort);
            assertThat(strings(operation.path("x-filterable"))).containsExactly(filters.get(resource).split(","));
            assertThat(operation.path("x-columns").findValuesAsText("key")).containsExactly(columns.get(resource).split(","));
            assertThat(operation.path("x-exportable").asBoolean()).isTrue();
            assertThat(operation.path("parameters").findValuesAsText("name")).contains("page", "size", "sort", "q", "filter", "fields");
            for (String repeated : List.of("sort", "filter")) {
                var parameter = Stream.of(mapper.convertValue(operation.path("parameters"), JsonNode[].class))
                        .filter(p -> p.path("name").asText().equals(repeated)).findFirst().orElseThrow();
                assertThat(parameter.at("/schema/type").asText()).isEqualTo("array");
                assertThat(parameter.path("explode").asBoolean()).isTrue();
            }
            var export = api.path("paths").path("/api/v1/" + resource + "/export").path("get");
            assertThat(export.at("/responses/200/content/application~1pdf/schema/format").asText()).isEqualTo("binary");
            assertThat(export.at("/responses/202/content/application~1json/schema/$ref").asText()).endsWith("/ExportAccepted");
        });
        var memberColumns = api.path("paths").path("/api/v1/members").at("/get/x-columns");
        assertThat(java.util.stream.StreamSupport.stream(memberColumns.spliterator(), false)
                .filter(column -> column.path("defaultVisible").asBoolean()).map(column -> column.path("key").asText()))
                .containsExactly("fullName", "dogs", "plan", "displayStatus");
        assertThat(api.path("paths").path("/api/v1/dogs").at("/get/x-columns/2/parameter").asText()).isEqualTo("levels.enabled");
        assertThat(api.path("paths").path("/api/v1/dogs").at("/get/x-columns/5/module").asText()).isEqualTo("FREE_TRAINING");
        assertThat(strings(api.path("paths").path("/api/v1/members").at("/get/x-filter-operators/fullName"))).containsExactly("contains");
        assertThat(api.path("paths").path("/api/v1/me/profile").has("put")).isTrue();
        assertThat(api.path("paths").path("/api/v1/me/profile").has("patch")).isTrue();
        assertThat(api.path("paths").path("/api/v1/me/profile").has("get")).isTrue();
        assertThat(schemas.path("MemberInstructorView").path("properties").fieldNames()).toIterable()
                .doesNotContain("paymentMethod", "nextInvoiceDate", "consents", "internalNotes", "bookingBlock");
        assertThat(schemas.path("MeDog").path("properties").fieldNames()).toIterable().doesNotContain("chip", "remarks", "memberId");
        for (String levelSchema : List.of("Level", "LevelReaderView", "LevelCreate", "LevelPatch")) {
            assertThat(schemas.path(levelSchema).path("properties").has("agilityhubLevel")).isFalse();
        }
        assertThat(schemas.path("Level").at("/properties/warnings/$ref").asText()).isEqualTo("#/components/schemas/LevelUsage");
        assertThat(strings(schemas.path("RingPatch").at("/properties/trainingCapacity/type"))).containsExactlyInAnyOrder("integer", "null");
        assertThat(schemas.path("InstructorReaderView").path("properties").fieldNames()).toIterable().doesNotContain("memberId", "usage");
        assertThat(schemas.path("ListPageMemberListItem").at("/properties/items/items/anyOf").size()).isEqualTo(2);
        assertThat(api.path("paths").path("/api/v1/public/{clubSlug}/plans").at("/get/security/0/clubApiKey").isArray()).isTrue();
        assertThat(api.path("paths").path("/api/v1/exports/{id}").at("/get/responses/422/description").asText()).contains("EXPORT_EXPIRED");
        assertThat(api.path("paths").path("/api/v1/public/{clubSlug}/plans").at("/get/responses/403/description").asText()).contains("INVALID_API_KEY");
    }

    @Test void T_02_03_allE2CatalogErrorsAlreadyExistWithCanonicalStatuses() throws Exception {
        String catalog = java.nio.file.Files.readString(java.nio.file.Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"));
        for (String spec : List.of("S02", "S03", "S05", "S14")) {
            String row = catalog.lines().filter(line -> line.startsWith("| " + spec + " |")).findFirst().orElseThrow();
            var matcher = java.util.regex.Pattern.compile("`([A-Z][A-Z_]+)`").matcher(row);
            while (matcher.find()) { assertThat(ErrorCode.valueOf(matcher.group(1))).isNotNull(); }
        }
        assertThat(ErrorCode.MEMBER_NOT_ACTIVE.httpStatus()).isEqualTo(422);
        assertThat(ErrorCode.EXPORT_EXPIRED.httpStatus()).isEqualTo(422);
        assertThat(ErrorCode.INVALID_API_KEY.httpStatus()).isEqualTo(403);
        String auditSpec = java.nio.file.Files.readString(java.nio.file.Path.of("docs/specs/S14-tauler-auditoria-exportacions-rgpd.md"));
        String actionRow = auditSpec.lines().filter(line -> line.startsWith("| R-14-09 |")).findFirst().orElseThrow()
                .split("`AuditAction`")[1].split("\\. Accions impersonades:")[0];
        var matcher = java.util.regex.Pattern.compile("`([A-Z][A-Z_]+)`").matcher(actionRow);
        var actions = new java.util.LinkedHashSet<String>();
        while (matcher.find()) { actions.add(matcher.group(1)); }
        assertThat(java.util.Arrays.stream(com.agilityhub.core.platform.api.AuditContracts.AuditActionName.values()).map(Enum::name))
                .containsExactlyElementsOf(actions);
    }

    private List<String> strings(JsonNode node) {
        return java.util.stream.StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }
}

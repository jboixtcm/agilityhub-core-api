package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E3ContractIT extends AbstractIntegrationTest {
    static final String HOST = "e3-a.example.test";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired org.springframework.data.mongodb.core.MongoTemplate mongo;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    boolean prepared;
    record Route(String method, String path, List<String> roles, JsonNode body,
                 Map<String, String> params, boolean idempotency, String module) { }
    static Stream<Route> routes() throws Exception {
        try (var input = E3ContractIT.class.getResourceAsStream("/fixtures/contracts/e3-routes.json")) {
            return List.of(new ObjectMapper().readValue(input, Route[].class)).stream();
        }
    }
    @BeforeEach void prepare() {
        if (prepared) { return; }
        for (boolean enabled : List.of(true, false)) {
            String id = enabled ? "e3-club-a" : "e3-club-b";
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(
                    PlatformFixtures.club(id, enabled ? HOST : "e3-b.example.test"));
            tree.set("modules", mapper.valueToTree(enabled ? Module.values() : new Module[0]));
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
        }
        hosts.invalidate(); prepared = true;
    }
    private MockHttpServletRequestBuilder call(Route route, String host) throws Exception {
        var request = request(HttpMethod.valueOf(route.method()), route.path().replace("{id}", "foreign-member-id"))
                .header("Host", host);
        if (route.body() != null && !route.body().isNull()) { request.contentType("application/json").content(mapper.writeValueAsString(route.body())); }
        route.params().forEach(request::param);
        if (route.idempotency()) { request.header("Idempotency-Key", UUID.randomUUID().toString()); }
        return request;
    }
    private int expected(Route route,String role) {
        if(route.path().contains("/members/") || route.path().contains("/me/dogs/")) return 404;
        if(route.path().equals("/api/v1/checkout-sessions")) return role.equals("ANON")?401:404;
        if(route.path().equals("/api/v1/signup")) return route.method().equals("POST")?422:role.equals("ANON")?200:404;
        return 200;
    }
    @ParameterizedTest @MethodSource("routes")
    void T_04_25_allElevenRoutesEnforceRolesAndTenantBoundaries(Route route) throws Exception {
        mvc.perform(call(route, HOST)).andExpect(status().is(route.roles().contains("ANON") ? expected(route,"ANON") : 401))
                .andExpect(header().doesNotExist("Set-Cookie"));
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN")) {
            boolean allowed = route.roles().contains(role);
            var result=mvc.perform(call(route, HOST).with(jwt().jwt(j -> j.subject("e3-" + role).claim("clubId", "e3-club-a"))
                            .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().is(allowed ? expected(route,role) : 403));
            if(!allowed) result.andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
        mvc.perform(call(route, "e3-b.example.test").with(jwt().jwt(j -> j.claim("clubId", "e3-club-a"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        mvc.perform(call(route, "id.example.test").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
        if (route.roles().contains("ANON")) {
            mvc.perform(call(route, "unknown.example.test")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        }
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_04_21_T_04_25_validImpersonationCanUseMemberRoutesButNeverD2() throws Exception {
        for (String kind : List.of("admin", "member")) {
            String id = "e3-imp-" + kind;
            var role = kind.equals("admin") ? com.agilityhub.core.identity.domain.Role.ADMIN : com.agilityhub.core.identity.domain.Role.MEMBER;
            mongo.save(new com.agilityhub.core.identity.persistence.Account(id, id + "@example.test", "Example " + kind, "en",
                    null, Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
            mongo.save(new com.agilityhub.core.identity.persistence.Membership(id, id, "e3-club-a", id,
                    Set.of(role), com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, role));
            mongo.save(new org.bson.Document("_id", id).append("clubId", "e3-club-a").append("accountId", id).append("status", "ACTIVE"), "members");
        }
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var ignored = TenantContext.open("e3-club-a")) {
            issued = impersonations.create("e3-imp-admin", "e3-imp-member", "Contract role test");
        }
        for (Route route : routes().toList()) {
            mvc.perform(call(route, HOST).with(jwt().jwt(issued.token()).authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                    .andExpect(status().is(!route.roles().contains("MEMBER") ? 403
                            :route.path().equals("/api/v1/checkout-sessions")?403
                            :route.path().equals("/api/v1/me/dogs/signup")?422:200));
        }
    }
    @Test void T_04_26_moduleGuardsApplyToAnonymousAndAuthenticatedCheckoutAndFamilyLookups() throws Exception {
        for (Route route : routes().filter(r -> r.module() != null).toList()) {
            mvc.perform(call(route, "e3-b.example.test")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
            mvc.perform(call(route, "e3-b.example.test").content("{"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
            for (String role : route.roles().stream().filter(r -> !r.equals("ANON")).toList()) {
                mvc.perform(call(route, "e3-b.example.test").with(jwt().jwt(j -> j.claim("clubId", "e3-club-b"))
                                .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
            }
        }
    }
    @Test void T_04_18_T_04_23_invalidRequestsAndForeignDryRunDoNotWriteSignupEntities() throws Exception {
        var collections = List.of("members", "dogs", "dog_documents", "upfront_payments", "domain_events", "audit_entries", "notifications");
        var before = new LinkedHashMap<String, Long>();
        collections.forEach(name -> before.put(name, mongo.getCollection(name).countDocuments()));
        for (Route route : routes().toList()) {
            String role = route.roles().getFirst();
            var valid = call(route, HOST);
            if (!role.equals("ANON")) { valid.with(jwt().jwt(j -> j.claim("clubId", "e3-club-a")).authorities(new SimpleGrantedAuthority("ROLE_" + role))); }
            if (route.path().endsWith("/validation")) { valid.param("dryRun", "true"); }
            mvc.perform(valid).andExpect(status().is(expected(route,role)));
            if (route.idempotency()) {
                // Build without the fixture's required header.
                var missing = request(HttpMethod.valueOf(route.method()), route.path()).header("Host", HOST)
                        .contentType("application/json").content(mapper.writeValueAsString(route.body()));
                if (!role.equals("ANON")) { missing.with(jwt().jwt(j -> j.claim("clubId", "e3-club-a")).authorities(new SimpleGrantedAuthority("ROLE_" + role))); }
                mvc.perform(missing).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
        collections.forEach(name -> assertThat(mongo.getCollection(name).countDocuments()).as(name).isEqualTo(before.get(name)));
    }
    @Test void T_04_25_T_04_28_snapshotPublishesSchemasSecurityLimitsHeadersAndErrors() throws Exception {
        JsonNode api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (Route route : routes().toList()) {
            var op = api.path("paths").path(route.path()).path(route.method().toLowerCase());
            assertThat(op.path("description").asText()).doesNotContain("501");
            assertThat(op.path("parameters").findValuesAsText("name")).doesNotContain("clubId");
            if (route.roles().contains("ANON")) { assertThat(op.path("security").isArray()).isTrue(); assertThat(op.path("security").isEmpty() || op.path("security").get(0).isEmpty()).isTrue(); }
            if (route.idempotency()) {
                var key = Stream.of(mapper.convertValue(op.path("parameters"), JsonNode[].class))
                        .filter(p -> p.path("name").asText().equals("Idempotency-Key")).findFirst().orElseThrow();
                assertThat(key.path("required").asBoolean()).isTrue();
            }
        }
        var schema = api.path("components").path("schemas");
        for (String name : List.of("SignupConfig", "SignupPlan", "SignupPaymentMethod", "IdentityCheckRequest", "IdentityCheckResult", "Town",
                "UploadUrlRequest", "UploadUrl", "FamilyGroupLookupRequest", "FamilyGroupLookupResult", "SignupRequest", "SignupResult", "UpfrontLine",
                "CheckoutSessionRequest", "CheckoutSession", "AddDogSignupRequest", "AddDogSignupResult", "MemberSignupView", "ValidationRequest",
                "ValidationDryRun", "ValidationResult", "RejectionRequest", "RejectionResult")) { assertThat(schema.path(name).path("properties").isEmpty()).as(name).isFalse(); }
        assertThat(strings(schema.at("/SignupWarning/enum"))).containsExactly("NO_IMAGE_CONSENT","ACCOUNT_NOT_PROVIDED","DOCUMENT_PENDING","FAMILY_HOLDER_NOT_FOUND","UPFRONT_UNPAID","READMISSION");
        properties(schema, "SignupRequest", "locale,website,person,dog,planId,familyGroupClaim,payment,consents");
        properties(schema, "SignupPerson", "idDocument,firstName,lastName1,lastName2,birthDate,gender,emails,phones,address");
        properties(schema, "SignupIdDocument", "type,value"); properties(schema, "SignupAddress", "street,postalCode,town");
        properties(schema, "SignupDog", "name,sex,breed,birthMonth,chip,notesToInstructors,documents");
        properties(schema, "SignupPayment", "type,iban,holderName,holderTaxId,firstMonthOption");
        properties(schema, "SignupConsents", "privacyPolicy,imageUse");
        properties(schema,"AddDogSignupRequest","dog,documents,planIdRequested,consents,additionalDogOption");
        properties(schema,"AddDogCheckout","required,memberId");
        properties(schema,"SignupUpfront","lines,totalDue,additionalDog");
        assertThat(schema.at("/SignupUpfrontConfig/properties").has("additionalDogOptions")).isTrue();
        properties(schema, "SignupConfig", "enabled,closedText,steps,plans,paymentMethods,texts,legal,countryProfile,upfront,member");
        properties(schema, "MemberSignupView", "member,dogs,signup,familyGroupClaim,upfront,proposals,warnings,version");
        properties(schema, "ValidationRequest", "version,dogs,planId,priceId,nextInvoiceDate,familyGroupId,upfrontAmountPaid");
        properties(schema, "UpfrontLine", "id,concept,amount,status,paidAmount,provider");
        assertThat(strings(schema.at("/UpfrontLine/properties/concept/enum"))).containsExactly("ENTRY_FEE","FIRST_MONTH","PACK","ADDITIONAL_DOG_FEE");
        assertThat(strings(schema.at("/UpfrontLine/properties/status/enum"))).containsExactly("DUE","CHECKOUT_PENDING","PAID","PARTIAL","CANCELLED","REFUNDED");
        assertThat(schema.at("/SignupPayment/properties/iban/writeOnly").asBoolean()).isTrue();
        var validation = api.path("paths").path("/api/v1/members/{id}/validation").path("post");
        assertThat(validation.at("/responses/200/content/application~1json/schema/oneOf").findValuesAsText("$ref"))
                .containsExactly("#/components/schemas/ValidationDryRun", "#/components/schemas/ValidationResult");
        assertThat(validation.at("/responses/409/description").asText()).contains("MEMBERSHIP_EXISTS");
        var list = api.path("paths").path("/api/v1/members").path("get");
        assertThat(strings(list.path("x-filterable"))).contains("signupPending", "pendingDogs", "warnings");
        assertThat(strings(list.path("x-sortable"))).contains("signup.submittedAt");
        assertThat(schema.at("/MemberListItem/properties").fieldNames()).toIterable().contains("signupPending","pendingDogs","warnings","signup");
        for (var limit : Map.of("/signup/identity-checks", "10/hour", "/signup/family-group-lookups", "20/hour", "/signup/upload-urls", "30/hour", "/signup", "5/hour and 20/day", "/checkout-sessions", "10/hour").entrySet()) {
            assertThat(api.path("paths").path("/api/v1" + limit.getKey()).at("/post/description").asText()).contains(limit.getValue(), "X-Forwarded-For", "E3-T03");
        }
        assertThat(api.path("paths").path("/api/v1/signup/towns").at("/get/description").asText()).contains("60/hour");
    }
    @Test void T_14_01_dashboardSchemasMatchEveryFieldAndNullableBlockInS14() throws Exception {
        var api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andReturn().getResponse().getContentAsString());
        var schemas = api.at("/components/schemas");
        Map<String, String> fields = Map.ofEntries(
                Map.entry("Dashboard", "generatedAt,today,week,kpis,riskReview,pendingSignups,dogsByLevel"),
                Map.entry("DashboardWeek", "start,end"), Map.entry("DashboardCounters", "pendingSignups,pendingRequests,followUpUnread"),
                Map.entry("DashboardKpis", "activeMembers,classOccupancy,trainingBookings,pendingSignups"),
                Map.entry("ActiveMembersKpi", "value,deltaThisMonth"), Map.entry("ClassOccupancyKpi", "percent,booked,capacity,waitingTotal"),
                Map.entry("TrainingBookingsKpi", "value,distinctMembers"), Map.entry("PendingSignupsKpi", "value,olderThanWarn,warnDays"),
                Map.entry("RiskReview", "reviewTime,lookaheadDays,autoCancelSameDay,count,items"),
                Map.entry("RiskItem", "classSessionId,date,startTime,displayDescription,ringName,booked,status,notified,reviewAt"),
                Map.entry("RiskNotified", "memberFirstName,gender,dogName"), Map.entry("PendingSignups", "count,items"),
                Map.entry("PendingSignup", "memberId,shortName,dogs,planName,paymentMethodType,warnings,submittedAt,pendingDays"),
                Map.entry("PendingSignupDog", "name,breed,isAddDog"), Map.entry("DogsByLevel", "totalActiveDogs,activeDogWeeks,levels,others"),
                Map.entry("DashboardLevel", "levelId,code,name,color,total,withRecentBooking"));
        fields.forEach((name, expected) -> properties(schemas, name, expected));
        assertThat(strings(schemas.at("/RiskItem/properties/status/enum"))).containsExactly("CANCELLED","AT_RISK","WILL_CANCEL","PENDING_DECISION");
        for (String block : List.of("riskReview","pendingSignups","dogsByLevel")) {
            assertThat(strings(schemas.at("/Dashboard/properties/" + block + "/type"))).contains("null");
            assertThat(strings(schemas.at("/Dashboard/required"))).contains(block);
        }
        for (String block : List.of("activeMembers","classOccupancy","trainingBookings","pendingSignups")) {
            assertThat(strings(schemas.at("/DashboardKpis/properties/" + block + "/type"))).contains("null");
        }
        assertThat(strings(schemas.at("/ClassOccupancyKpi/properties/percent/type"))).contains("null");
        assertThat(strings(schemas.at("/ClassOccupancyKpi/required"))).contains("percent");
        assertThat(strings(schemas.at("/PendingSignup/required"))).doesNotContain("paymentMethodType", "warnings");
        assertThat(schemas.at("/PendingSignup/properties/warnings/items/$ref"))
                .isEqualTo(schemas.at("/MemberSignupView/properties/warnings/items/$ref"));
    }
    @Test void T_04_25_errorRowHasExactlyTheCanonicalCodesStatusesAndThreeTranslations() throws Exception {
        var expected = new LinkedHashMap<String, Integer>();
        for (String code : List.of("MEMBER_ALREADY_EXISTS","MEMBERSHIP_EXISTS")) { expected.put(code,409); }
        for (String code : List.of("INVALID_ID_DOCUMENT","INVALID_PHONE","FILE_NOT_FOUND")) { expected.put(code,400); }
        for (String code : List.of("SIGNUP_CLOSED","SIGNUP_ALREADY_PENDING","ID_DOCUMENT_AMBIGUOUS","PLAN_NOT_AVAILABLE","PAYMENT_METHOD_NOT_AVAILABLE","PAYMENT_PROVIDER_NOT_ENABLED","DOG_CHIP_ALREADY_REGISTERED","DOG_DOCUMENT_REQUIRED","CONSENT_VERSION_OUTDATED","LEVEL_REQUIRED","NEXT_INVOICE_DATE_REQUIRED","UPFRONT_AMOUNT_EXCEEDS_DUE","FAMILY_HOLDER_NOT_FOUND")) { expected.put(code,422); }
        expected.put("RATE_LIMITED",429);
        String row = Files.readAllLines(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md")).stream().filter(s -> s.startsWith("| S04 |")).findFirst().orElseThrow();
        var matcher = java.util.regex.Pattern.compile("`([A-Z_]+)`").matcher(row);
        var names = new HashSet<String>(); while (matcher.find()) { names.add(matcher.group(1)); }
        assertThat(names).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((code, status) -> assertThat(ErrorCode.valueOf(code).httpStatus()).as(code).isEqualTo(status));
        for (String locale : List.of("ca","es","en")) {
            var messages = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/messages/messages_" + locale + ".properties"))) { messages.load(reader); }
            names.forEach(code -> assertThat(messages.getProperty("error." + code)).as(locale + ":" + code).isNotBlank());
        }
    }
    private static void properties(JsonNode schemas, String name, String fields) {
        assertThat(schemas.path(name).path("properties").fieldNames()).toIterable().as(name).containsExactlyInAnyOrder(fields.split(","));
    }
    private static List<String> strings(JsonNode node) { var result = new ArrayList<String>(); node.forEach(n -> result.add(n.asText())); return result; }
}

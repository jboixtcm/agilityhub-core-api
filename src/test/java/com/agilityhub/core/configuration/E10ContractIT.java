package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Contract half of S17; no DNS, provider calls or provisioning is implemented in E10-T01. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E10ContractIT extends AbstractIntegrationTest {
    record Route(String method, String path, int success, JsonNode body, boolean key, Map<String,String> params, Map<String,List<String>> errors) { }
    static Stream<Route> routes() throws Exception {
        return Arrays.stream(new ObjectMapper().readValue(Path.of("src/test/resources/fixtures/contracts/e10-routes.json").toFile(),Route[].class));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired MongoTemplate mongo;
    @BeforeEach void setup() {
        for(String id:List.of("e10-target","e10-source")) {
            if(clubs.findById(id).isEmpty()) clubs.save(PlatformFixtures.club(id,id+".example.test"));
        }
    }
    @AfterEach void cleanup() {
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").in("e10-target","e10-source")),
                com.agilityhub.core.platform.persistence.Club.class);
    }
    MockHttpServletRequestBuilder call(Route route,String role) throws Exception {
        String path=route.path().replace("{id}","e10-target").replace("{host}","e10-target.example.test")
                .replace("{provider}","STRIPE").replace("{key}","levels.enabled").replace("{accountId}","e10-admin");
        var request=request(HttpMethod.valueOf(route.method()),path).header("Host","unknown.example.test");
        if(route.body()!=null&&!route.body().isNull()) request.contentType("application/json").content(mapper.writeValueAsString(route.body()));
        route.params().forEach(request::param);
        if(route.key()) request.header("Idempotency-Key",UUID.randomUUID().toString());
        if(!role.equals("ANON")) request.with(jwt().jwt(j->j.subject("e10-actor"))
                .authorities(new SimpleGrantedAuthority("ROLE_"+role)));
        return request;
    }
    void error(MockHttpServletRequestBuilder request,int status,String code) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI()+" "+response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentType()).as("Canonical API error media type").startsWith("application/json");
        var body=mapper.readTree(response.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(body.path("message").asText()).isNotBlank();
    }
    Map<String,List<org.bson.Document>> state() {
        var result=new TreeMap<String,List<org.bson.Document>>();
        for(String collection:List.of("clubs","parameters","memberships","accounts","domain_events","audit_entries","idempotency_records"))
            result.put(collection,mongo.findAll(org.bson.Document.class,collection));
        return result;
    }
    @ParameterizedTest @MethodSource("routes")
    void T_17_10_R_17_12_globalPlatformRoleChecksPrecedeNoWriteStubs(Route route) throws Exception {
        var before=state();
        for(String role:List.of("ANON","MEMBER","INSTRUCTOR","ADMIN","AGILITYHUB_ADMIN")) {
            error(call(route,role),role.equals("ANON")?401:role.equals("AGILITYHUB_ADMIN")?501:403,
                    role.equals("ANON")?"UNAUTHENTICATED":role.equals("AGILITYHUB_ADMIN")?"NOT_IMPLEMENTED":"FORBIDDEN");
        }
        assertThat(state()).isEqualTo(before);
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_17_02_to_11_eachRulePublishesTypedFormsCanonicalErrorsAndIdempotency() throws Exception {
        var api=mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(var r:routes().toList()) {
            var op=api.path("paths").path(r.path()).path(r.method().toLowerCase());
            assertThat(op.isMissingNode()).as(r.method()+" "+r.path()).isFalse();
            assertThat(op.path("responses").has(Integer.toString(r.success()))).isTrue();
            assertThat(op.path("responses").path("501").path("description").asText()).contains("NOT_IMPLEMENTED");
            for(var error:r.errors().entrySet()) for(String code:error.getValue())
                assertThat(op.path("responses").path(error.getKey()).path("description").asText()).contains(code);
            if(r.key()) assertThat(op.path("parameters").findValuesAsText("name")).contains("Idempotency-Key");
            if(r.body()!=null&&!r.body().isNull()) assertThat(op.path("requestBody").path("content").path("application/json").path("schema").toString()).contains("$ref");
        }
        var list=api.path("paths").path("/api/v1/platform/clubs").path("get");
        assertThat(list.path("x-filterable").toString()).isEqualTo("[\"status\",\"countryProfile\",\"module\",\"template\"]");
        assertThat(api.path("paths").path("/api/v1/platform/clubs/{id}/definition").path("get").path("responses").path("200").path("content").has("application/yaml")).isTrue();
        for(String existing:List.of("/api/v1/platform/accounts/{id}/platform-roles","/api/v1/platform/jobs/overview","/api/v1/platform/parameter-catalog","/api/v1/platform/audit-entries","/api/v1/platform/security-events","/api/v1/platform/courses"))
            assertThat(api.path("paths").has(existing)).as(existing).isTrue();
    }
    @Test void T_17_03_internalDomainCheckIsNeverPubliclyAuthorized() throws Exception {
        error(get("/internal/domains/allowed").param("host","e10-target.example.test"),501,"NOT_IMPLEMENTED");
        error(get("/internal/domains/allowed").param("host","e10-target.example.test").with(r->{r.setRemoteAddr("203.0.113.9");return r;}),403,"FORBIDDEN");
        error(get("/internal/domains/allowed").param("host","e10-target.example.test").header("X-Forwarded-For","127.0.0.1")
                .with(r->{r.setRemoteAddr("203.0.113.9");return r;}),403,"FORBIDDEN");
    }
    Route route(String method,String suffix) throws Exception {
        return routes().filter(r->r.method().equals(method)&&r.path().equals("/api/v1/platform"+suffix)).findFirst().orElseThrow();
    }
    @Test void T_17_02_R_17_02_definitionRejectsInvalidShapeAndPlaintextSecretsBefore501() throws Exception {
        var route=route("POST","/clubs");
        error(call(route,"AGILITYHUB_ADMIN").content("{}"),400,"VALIDATION_ERROR");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)route.body().deepCopy();
        body.putObject("paymentProviders").putObject("STRIPE").put("enabled",true).put("secretKey","fictional-inline-secret");
        error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),400,"VALIDATION_ERROR");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("paymentProviders").path("STRIPE")).putObject("secretKey").put("env","EXAMPLE_STRIPE_KEY");
        error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),501,"NOT_IMPLEMENTED");
        body.putArray("accounts").addObject().put("email","example@example.test").put("password","fictional-password");
        error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),400,"VALIDATION_ERROR");
        body.remove("accounts"); body.put("preset","COMPLET"); body.remove("modules");
        body.putObject("catalogs").put("fromTemplate","example-template");
        error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),501,"NOT_IMPLEMENTED");
    }
    @Test void T_17_04_R_17_04_modulesRequireOneSelectionAndVersion() throws Exception {
        var route=route("PUT","/clubs/{id}/modules");
        for(String body:List.of("{}","{\"version\":0}","{\"version\":0,\"modules\":[],\"preset\":\"MINIM\"}","{\"version\":-1,\"modules\":[]}"))
            error(call(route,"AGILITYHUB_ADMIN").content(body),400,"VALIDATION_ERROR");
        error(call(route,"AGILITYHUB_ADMIN").content("{\"version\":0,\"modules\":[]}"),501,"NOT_IMPLEMENTED");
    }
    @Test void T_17_05_R_17_05_providerSpecificInputsAndVersionAreChecked() throws Exception {
        var route=route("PUT","/clubs/{id}/payment-providers/{provider}");
        error(call(route,"AGILITYHUB_ADMIN").content("{\"version\":0}"),422,"PROVIDER_CONFIG_INVALID");
        error(call(route,"AGILITYHUB_ADMIN").content("{}"),400,"VALIDATION_ERROR");
        for(String provider:List.of("SEPA_XML","MANUAL")) {
            error(call(route,"AGILITYHUB_ADMIN").with(r->{r.setRequestURI(r.getRequestURI().replace("STRIPE",provider));return r;}),422,"PROVIDER_CONFIG_INVALID");
        }
        error(call(route("DELETE","/clubs/{id}/payment-providers/{provider}"),"AGILITYHUB_ADMIN").with(r->{r.setParameter("version","-1");return r;}),400,"VALIDATION_ERROR");
        error(call(route,"AGILITYHUB_ADMIN").with(r->{r.setRequestURI(r.getRequestURI().replace("STRIPE","SEPA_XML"));return r;})
                .content("{\"version\":0,\"creditorName\":\"Example\",\"creditorId\":\"fictional\",\"iban\":\"fictional\",\"suffix\":\"000\"}"),501,"NOT_IMPLEMENTED");
        error(call(route,"AGILITYHUB_ADMIN").with(r->{r.setRequestURI(r.getRequestURI().replace("STRIPE","MANUAL"));return r;})
                .content("{\"version\":0,\"instructions\":{\"ca\":\"Example\"}}"),501,"NOT_IMPLEMENTED");
    }
    @Test void T_17_06_T_17_13_R_17_07_suspensionRequiresReasonAndEditsRequireVersion() throws Exception {
        error(call(route("PUT","/clubs/{id}/status"),"AGILITYHUB_ADMIN").content("{\"status\":\"SUSPENDED\"}"),400,"VALIDATION_ERROR");
        error(call(route("PUT","/clubs/{id}/status"),"AGILITYHUB_ADMIN").content("{\"status\":\"ACTIVE\"}"),501,"NOT_IMPLEMENTED");
        error(call(route("PATCH","/clubs/{id}"),"AGILITYHUB_ADMIN").content("{\"name\":\"Example\"}"),400,"VALIDATION_ERROR");
        error(call(route("PUT","/clubs/{id}/theme"),"AGILITYHUB_ADMIN").content("{\"version\":0}"),400,"VALIDATION_ERROR");
    }
    @Test void T_17_07_R_17_08_cloneNamesExistingSourceAndNonemptyParts() throws Exception {
        var route=route("POST","/clubs/{id}/catalog-clone");
        error(call(route,"AGILITYHUB_ADMIN").content("{\"fromClubId\":\"missing\",\"parts\":[\"RINGS\"]}"),404,"TEMPLATE_CLUB_NOT_FOUND");
        error(call(route,"AGILITYHUB_ADMIN").content("{\"fromClubId\":\"e10-source\",\"parts\":[]}"),400,"VALIDATION_ERROR");
    }
    @Test void T_17_08_R_17_09_adminInvitationHasValidEmailAndName() throws Exception {
        error(call(route("POST","/clubs/{id}/admins"),"AGILITYHUB_ADMIN").content("{\"email\":\"invalid\",\"name\":\"\"}"),400,"VALIDATION_ERROR");
    }
    @Test void T_17_09_R_17_10_supportRequiresReasonAndRefusesImpersonation() throws Exception {
        error(call(route("POST","/clubs/{id}/support-access"),"AGILITYHUB_ADMIN").content("{\"reason\":\" \"}"),400,"VALIDATION_ERROR");
        for(var route:routes().toList()) error(call(route,"AGILITYHUB_ADMIN").with(jwt().jwt(j->j.subject("e10-actor").claim("imp",true))
                .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))),403,"IMPERSONATION_DENIED");
    }
    @Test void T_17_11_R_17_11_globalKeysRemainRetryableAfterStubAndCannotBypassRole() throws Exception {
        var route=route("POST","/clubs/{id}/public-api-key"); var before=state();
        String key=UUID.randomUUID().toString();
        for(int i=0;i<2;i++) error(call(route,"AGILITYHUB_ADMIN").with(r->{r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key",key);return r;}),501,"NOT_IMPLEMENTED");
        error(call(route,"ADMIN").with(r->{r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key",key);return r;}),403,"FORBIDDEN");
        error(call(route,"AGILITYHUB_ADMIN").with(r->{r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key","invalid");return r;}),400,"VALIDATION_ERROR");
        assertThat(state()).isEqualTo(before);
    }
    @Test void T_17_10_R_17_12_unknownResourcesAndInvalidListQueriesFailBefore501() throws Exception {
        for(var route:routes().filter(r->r.path().contains("{id}")).toList())
            error(call(route,"AGILITYHUB_ADMIN").with(r->{r.setRequestURI(r.getRequestURI().replace("e10-target","e10-missing"));return r;}),404,"CLUB_NOT_FOUND");
        error(call(route("POST","/clubs/{id}/domains/{host}/verification"),"AGILITYHUB_ADMIN")
                .with(r->{r.setRequestURI(r.getRequestURI().replace("e10-target.example.test","missing.example.test"));return r;}),404,"NOT_FOUND");
        error(call(route("PUT","/clubs/{id}/parameters/{key}"),"AGILITYHUB_ADMIN")
                .with(r->{r.setRequestURI(r.getRequestURI().replace("levels.enabled","missing.key"));return r;}),400,"UNKNOWN_PARAMETER");
        for(var param:Map.of("filter","email:eq:example@example.test","fields","secretKey","page","-1","size","17","sort","secretKey,asc").entrySet())
            error(call(route("GET","/clubs"),"AGILITYHUB_ADMIN").param(param.getKey(),param.getValue()),400,"INVALID_FILTER");
        error(call(route("GET","/clubs"),"AGILITYHUB_ADMIN").param("filter","template:eq:true").param("fields","name,usage"),501,"NOT_IMPLEMENTED");
        error(call(route("GET","/clubs/{id}/usage"),"AGILITYHUB_ADMIN").with(r->{r.setParameter("month","2026-13");return r;}),400,"VALIDATION_ERROR");
    }
    @Test void T_17_14_R_17_13_localizationUsesOnlySupportedLocales() throws Exception {
        error(call(route("PATCH","/clubs/{id}"),"AGILITYHUB_ADMIN").content("{\"version\":0,\"locales\":[\"zz\"]}"),400,"VALIDATION_ERROR");
        error(call(route("PATCH","/clubs/{id}"),"AGILITYHUB_ADMIN").content("{\"version\":0,\"legal\":{\"privacyPolicyUrl\":\"https://example.test/privacy\",\"imageConsentText\":{\"ca\":\"Text\"},\"legalTextsVersion\":\"v1\"}}"),501,"NOT_IMPLEMENTED");
        for(String locale:List.of("ca","es","en"))
            error(call(route("PATCH","/clubs/{id}"),"AGILITYHUB_ADMIN").content("{\"version\":0,\"locales\":[\""+locale+"\"],\"defaultLocale\":\""+locale+"\"}"),501,"NOT_IMPLEMENTED");
    }

    @Test void T_17_02_R_17_02_yamlExportStillUsesTheCanonicalJsonStubError() throws Exception {
        error(call(route("GET","/clubs/{id}/definition"),"AGILITYHUB_ADMIN").accept("application/yaml"),501,"NOT_IMPLEMENTED");
        error(call(route("GET","/clubs/{id}/definition"),"AGILITYHUB_ADMIN").accept("application/yaml")
                .with(r->{r.setRequestURI(r.getRequestURI().replace("e10-target","e10-missing"));return r;}),404,"CLUB_NOT_FOUND");
    }

}

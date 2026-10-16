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

/** S17 contract guards remain stable as E10-T02 implements the first cut. */
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
        String path=route.path().replace("{id}","e10-target").replace("{clubId}","e10-target").replace("{host}","e10-target.example.test")
                .replace("{provider}","STRIPE").replace("{key}","levels.enabled").replace("{accountId}","e10-admin");
        var request=request(HttpMethod.valueOf(route.method()),path).header("Host","unknown.example.test");
        if(route.body()!=null&&!route.body().isNull()) request.contentType("application/json").content(mapper.writeValueAsString(route.body()));
        route.params().forEach(request::param);
        if(route.key()) request.header("Idempotency-Key",UUID.randomUUID().toString());
        if(!role.equals("ANON")) request.with(jwt().jwt(j->j.subject("e10-actor"))
                .authorities(new SimpleGrantedAuthority("ROLE_"+role)));
        return request;
    }
    void success(MockHttpServletRequestBuilder request) throws Exception { mvc.perform(request).andExpect(status().isOk()); }
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
        for(String collection:List.of("clubs","parameters","memberships","accounts","domain_events","audit_entries","idempotency_records","attachment_uploads"))
            result.put(collection,mongo.findAll(org.bson.Document.class,collection));
        return result;
    }
    @ParameterizedTest @MethodSource("routes")
    void T_17_10_R_17_12_globalPlatformRoleChecksPrecedeWrites(Route route) throws Exception {
        var before=state();
        for(String role:List.of("ANON","MEMBER","INSTRUCTOR","ADMIN")) {
            error(call(route,role),role.equals("ANON")?401:403,role.equals("ANON")?"UNAUTHENTICATED":"FORBIDDEN");
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
            // E10-T04: no S17 route is a 501 contract any more.
            assertThat(op.path("responses").path("501").path("description").asText()).as(r.path()).doesNotContain("NOT_IMPLEMENTED");
            for(var error:r.errors().entrySet()) for(String code:error.getValue())
                assertThat(op.path("responses").path(error.getKey()).path("description").asText()).as(r.method()+" "+r.path()).contains(code);
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
        // The internal network gets its answer (the fixture's own verified host); an untrusted address never does.
        mvc.perform(get("/internal/domains/allowed").param("host","e10-target.example.test")).andExpect(status().isOk());
        error(get("/internal/domains/allowed").param("host","pending.e10-target.example.test"),404,"NOT_FOUND");
        error(get("/internal/domains/allowed").param("host","e10-target.example.test").with(r->{r.setRemoteAddr("203.0.113.9");return r;}),403,"FORBIDDEN");
        error(get("/internal/domains/allowed").param("host","e10-target.example.test").header("X-Forwarded-For","127.0.0.1")
                .with(r->{r.setRemoteAddr("203.0.113.9");return r;}),403,"FORBIDDEN");
    }
    Route route(String method,String suffix) throws Exception {
        return routes().filter(r->r.method().equals(method)&&r.path().equals("/api/v1/platform"+suffix)).findFirst().orElseThrow();
    }
    @Test void T_17_02_R_17_02_definitionRejectsInvalidShapeAndPlaintextSecretsBeforeWrites() throws Exception {
        var route=route("POST","/clubs");
        error(call(route,"AGILITYHUB_ADMIN").content("{}"),400,"VALIDATION_ERROR");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)route.body().deepCopy();
        body.putObject("paymentProviders").putObject("STRIPE").put("enabled",true).put("secretKey","fictional-inline-secret");
        error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),400,"VALIDATION_ERROR");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("paymentProviders").path("STRIPE")).putObject("secretKey").put("env","EXAMPLE_STRIPE_KEY");
        error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),422,"PROVIDER_CONFIG_INVALID");
        body.putArray("accounts").addObject().put("email","example@example.test").put("password","fictional-password");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("club")).put("foo","fictional");
        var rejected=mvc.perform(call(route,"AGILITYHUB_ADMIN").content(body.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn().getResponse().getContentAsString();
        // CONVENCIONS_API §6: an unknown property names itself, never the parent object (E10-T01 review #1).
        assertThat(mapper.readTree(rejected).path("details").path("fieldErrors")).contains(
                mapper.valueToTree(Map.of("field","accounts","code","INVALID_VALUE")),mapper.valueToTree(Map.of("field","club.foo","code","INVALID_VALUE")));
        assertThat(rejected).doesNotContain("fictional-password");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("club")).remove("foo");
        body.remove("accounts"); body.put("preset","COMPLET"); body.remove("modules");
        body.putObject("catalogs").put("fromTemplate","example-template");
        error(call(route,"AGILITYHUB_ADMIN").content(body.toString()),404,"TEMPLATE_CLUB_NOT_FOUND");
    }
    @Test void T_17_04_R_17_04_modulesRequireOneSelectionAndVersion() throws Exception {
        var route=route("PUT","/clubs/{id}/modules");
        for(String body:List.of("{}","{\"version\":0}","{\"version\":0,\"modules\":[],\"preset\":\"MINIM\"}","{\"version\":-1,\"modules\":[]}"))
            error(call(route,"AGILITYHUB_ADMIN").content(body),400,"VALIDATION_ERROR");
        success(call(route,"AGILITYHUB_ADMIN").content("{\"version\":0,\"modules\":[]}"));
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
                .content("{\"version\":0,\"creditorName\":\"Example\",\"creditorId\":\"fictional\",\"iban\":\"fictional\",\"suffix\":\"000\"}"),422,"PROVIDER_CONFIG_INVALID");
        success(call(route,"AGILITYHUB_ADMIN").with(r->{r.setRequestURI(r.getRequestURI().replace("STRIPE","MANUAL"));return r;})
                .content("{\"version\":0,\"instructions\":{\"ca\":\"Example\"}}"));
    }
    @Test void T_17_06_T_17_13_R_17_07_suspensionRequiresReasonAndEditsRequireVersion() throws Exception {
        error(call(route("PUT","/clubs/{id}/status"),"AGILITYHUB_ADMIN").content("{\"status\":\"SUSPENDED\"}"),400,"VALIDATION_ERROR");
        success(call(route("PUT","/clubs/{id}/status"),"AGILITYHUB_ADMIN").content("{\"status\":\"ACTIVE\"}"));
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
    @Test void T_17_11_R_17_11_keyedRotationReplaysItsKeyAndCannotBypassRole() throws Exception {
        var route=route("POST","/clubs/{id}/public-api-key");
        String key=UUID.randomUUID().toString();
        var first=mvc.perform(call(route,"AGILITYHUB_ADMIN").with(r->{r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key",key);return r;}))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var before=state();
        // The replay answers the same key, which a lost answer would otherwise leave unknown (the first rotation committed).
        assertThat(mvc.perform(call(route,"AGILITYHUB_ADMIN").with(r->{r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key",key);return r;}))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        error(call(route,"ADMIN").with(r->{r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key",key);return r;}),403,"FORBIDDEN");
        error(call(route,"AGILITYHUB_ADMIN").with(r->{r.removeHeader("Idempotency-Key");r.addHeader("Idempotency-Key","invalid");return r;}),400,"VALIDATION_ERROR");
        assertThat(state()).isEqualTo(before);
        // Sealed at rest: the stored answer never holds the key in clear (S17 R-17-11, «es mostra un sol cop»).
        String apiKey=mapper.readTree(first).path("apiKey").asText();
        var stored=mongo.find(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("key").is(key)),
                org.bson.Document.class,"idempotency_records").stream()
                .map(record->new String(record.get("responseBody",org.bson.types.Binary.class).getData(),java.nio.charset.StandardCharsets.UTF_8)).toList();
        assertThat(stored).isNotEmpty().allSatisfy(body->assertThat(body).doesNotContain(apiKey,"apiKey"));
    }
    @Test void T_17_10_R_17_12_unknownResourcesAndInvalidListQueriesFailBeforeWrites() throws Exception {
        for(var route:routes().filter(r->r.path().contains("{id}")||r.path().contains("{clubId}")).toList())
            error(call(route,"AGILITYHUB_ADMIN").with(r->{r.setRequestURI(r.getRequestURI().replace("e10-target","e10-missing"));return r;}),404,"CLUB_NOT_FOUND");
        error(call(route("POST","/clubs/{id}/domains/{host}/verification"),"AGILITYHUB_ADMIN")
                .with(r->{r.setRequestURI(r.getRequestURI().replace("e10-target.example.test","missing.example.test"));return r;}),404,"NOT_FOUND");
        error(call(route("PUT","/clubs/{id}/parameters/{key}"),"AGILITYHUB_ADMIN")
                .with(r->{r.setRequestURI(r.getRequestURI().replace("levels.enabled","missing.key"));return r;}),400,"UNKNOWN_PARAMETER");
        for(var param:Map.of("filter","email:eq:example@example.test","fields","secretKey","page","-1","size","17","sort","secretKey,asc").entrySet())
            error(call(route("GET","/clubs"),"AGILITYHUB_ADMIN").param(param.getKey(),param.getValue()),400,"INVALID_FILTER");
        success(call(route("GET","/clubs"),"AGILITYHUB_ADMIN").param("filter","template:eq:true").param("fields","name,usage"));
        error(call(route("GET","/clubs/{id}/usage"),"AGILITYHUB_ADMIN").with(r->{r.setParameter("month","2026-13");return r;}),400,"VALIDATION_ERROR");
    }
    @Test void T_17_14_R_17_13_localizationUsesOnlySupportedLocales() throws Exception {
        error(call(route("PATCH","/clubs/{id}"),"AGILITYHUB_ADMIN").content("{\"version\":0,\"locales\":[\"zz\"]}"),400,"VALIDATION_ERROR");
        success(call(route("PATCH","/clubs/{id}"),"AGILITYHUB_ADMIN").content("{\"version\":0,\"legal\":{\"privacyPolicyUrl\":\"https://example.test/privacy\",\"imageConsentText\":{\"ca\":\"Text\"},\"legalTextsVersion\":\"v1\"}}"));
        for(String locale:List.of("ca","es","en"))
            success(call(route("PATCH","/clubs/{id}"),"AGILITYHUB_ADMIN").content("{\"version\":"+clubs.findById("e10-target").orElseThrow().version()+",\"locales\":[\""+locale+"\"],\"defaultLocale\":\""+locale+"\"}"));
    }

    @Test void T_17_02_R_17_02_yamlExportUsesCanonicalJsonForErrors() throws Exception {
        success(call(route("GET","/clubs/{id}/definition"),"AGILITYHUB_ADMIN").accept("application/yaml"));
        error(call(route("GET","/clubs/{id}/definition"),"AGILITYHUB_ADMIN").accept("application/yaml")
                .with(r->{r.setRequestURI(r.getRequestURI().replace("e10-target","e10-missing"));return r;}),404,"CLUB_NOT_FOUND");
    }

    /** S17 §2/§6 (the wizard's and the Tema tab's logos): the console's one upload route, with the attachment route's shape and limits. */
    @Test void T_17_02_to_11_platformLogoUploadUsesTheAttachmentShapeAndImageLimits() throws Exception {
        var route=route("POST","/clubs/{id}/theme/upload-url");
        var before=state();
        error(call(route,"AGILITYHUB_ADMIN").content("{\"fileName\":\"logo.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":3}"),400,"FILE_TYPE_NOT_ALLOWED");
        mvc.perform(call(route,"AGILITYHUB_ADMIN").content("{\"fileName\":\"logo.png\",\"mimeType\":\"image/png\",\"sizeBytes\":26214401}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.details.maxSizeMb").value(25));
        error(call(route,"AGILITYHUB_ADMIN").content("{}"),400,"VALIDATION_ERROR");
        assertThat(state()).isEqualTo(before);
        var upload=mapper.readTree(mvc.perform(call(route,"AGILITYHUB_ADMIN").content("{\"fileName\":\"logo.png\",\"mimeType\":\"image/png\",\"sizeBytes\":26214400}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(upload.propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("uploadUrl","fileKey","expiresAt","headers");
        assertThat(upload.path("headers").path("Content-Type").asText()).isEqualTo("image/png");
        var grant=mongo.findById(upload.path("fileKey").asText(),org.bson.Document.class,"attachment_uploads");
        assertThat(grant.getString("clubId")).isEqualTo("e10-target");
        assertThat(grant.getString("purpose")).isEqualTo("CLUB_LOGO");
        assertThat(grant.get("boundEntity")).isNull();
        assertThat(TenantContext.current()).isNull();
    }

    @Test void CONVENCIONS_API_4_platformClubPageReturnsTheAppliedUniversalFilters() throws Exception {
        var result=mvc.perform(call(route("GET","/clubs"),"AGILITYHUB_ADMIN")
                .param("filter","template:eq:false","module:in:FAQ,WAITLIST").param("fields","name"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(result.getContentAsString()).path("appliedFilters"))
                .isEqualTo(mapper.readTree("[{\"field\":\"template\",\"op\":\"eq\",\"value\":false},{\"field\":\"module\",\"op\":\"in\",\"value\":[\"FAQ\",\"WAITLIST\"]}]"));
    }

    @Test void T_17_02_R_17_02_definitionErrorsNameNestedFieldsWithoutInputValues() throws Exception {
        var route=route("POST","/clubs");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)route.body().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("club")).remove("name");
        body.putArray("modules").add("FAQ").add("WAITLIST").add("fictional-invalid-module");
        body.putObject("paymentProviders").putObject("SEPA_XML").put("enabled",true).put("creditorId",42);
        var before=state();
        var result=mvc.perform(call(route,"AGILITYHUB_ADMIN").content(body.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fields").doesNotExist()).andReturn().getResponse();
        var details=mapper.readTree(result.getContentAsString()).path("details");
        assertThat(details.path("fieldErrors")).contains(
                mapper.valueToTree(Map.of("field","club.name","code","REQUIRED")),
                mapper.valueToTree(Map.of("field","modules[2]","code","INVALID_VALUE")),
                mapper.valueToTree(Map.of("field","paymentProviders.SEPA_XML.creditorId","code","INVALID_VALUE")));
        assertThat(details.toString()).doesNotContain("fictional-invalid-module", "\"42\"");
        assertThat(state()).isEqualTo(before);
    }

    @Test void T_17_05_R_17_05_providerReadsAndWritesProjectOnlyNonSecretSettings() throws Exception {
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is("e10-target")),
                org.springframework.data.mongodb.core.query.Update.update("paymentProviders",Map.of(
                        "SEPA_XML",Map.of("enabled",true,"creditorName","Example Club","creditorId","ES00000X0000000X","suffix","000","ibanLast4","1332","ibanEnc","fictional-ciphertext"),
                        "MANUAL",Map.of("enabled",true,"instructions",Map.of("ca","Pay at reception")))),
                com.agilityhub.core.platform.persistence.Club.class);
        // E10-T01 review #5: the account id is the one the Stripe probe returned, never a value seeded into Mongo.
        mvc.perform(call(route("PUT","/clubs/{id}/payment-providers/{provider}"),"AGILITYHUB_ADMIN")
                .content("{\"version\":"+clubs.findById("e10-target").orElseThrow().version()+",\"enabled\":true,\"secretKey\":\"sk_test_fictional_e10\",\"webhookSecret\":\"whsec_fictional_e10\",\"publishableKey\":\"pk_test_fictional1234\",\"mode\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountIdMasked").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(call(route("POST","/clubs/{id}/payment-providers/stripe/test"),"AGILITYHUB_ADMIN")).andExpect(status().isOk());
        assertThat(((Map<?,?>)clubs.findById("e10-target").orElseThrow().paymentProviders().get("STRIPE")).get("accountId")).isEqualTo("acct_fictional5678");
        var response=mvc.perform(call(route("GET","/clubs/{id}"),"AGILITYHUB_ADMIN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var providers=mapper.readTree(response).path("paymentProviders");
        assertThat(providers.path("SEPA_XML").path("creditorName").asText()).isEqualTo("Example Club");
        assertThat(providers.path("SEPA_XML").path("creditorId").asText()).isEqualTo("ES00000X0000000X");
        assertThat(providers.path("SEPA_XML").path("suffix").asText()).isEqualTo("000");
        assertThat(providers.path("SEPA_XML").path("ibanMasked").asText()).isEqualTo("****1332");
        assertThat(providers.path("MANUAL").path("instructions").path("ca").asText()).isEqualTo("Pay at reception");
        assertThat(providers.path("STRIPE").path("accountIdMasked").asText()).isEqualTo("****5678");
        assertThat(providers.path("STRIPE").path("creditorName").isNull()).isTrue();
        assertThat(providers.path("SEPA_XML").path("instructions").isNull()).isTrue();
        assertThat(response).doesNotContain("fictional-ciphertext","pk_test_fictional1234","acct_fictional5678","sk_test_fictional_e10","whsec_fictional_e10","\"iban\"","secretKey","webhookSecret");
        mvc.perform(call(route("PUT","/clubs/{id}/payment-providers/{provider}"),"AGILITYHUB_ADMIN")
                .with(r->{r.setRequestURI(r.getRequestURI().replace("STRIPE","MANUAL"));return r;})
                .content("{\"version\":"+mapper.readTree(response).path("version").asLong()+",\"instructions\":{\"ca\":\"Updated instructions\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instructions.ca").value("Updated instructions"));
        var audit=mongo.findAll(org.bson.Document.class,"audit_entries").toString();
        assertThat(audit).doesNotContain("Updated instructions","Pay at reception","ES00000X0000000X","fictional-ciphertext");
    }

}

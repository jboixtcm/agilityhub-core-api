package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.FakeDomainDns;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * S17 R-17-02/R-17-03 (E10-T04): the console's club domains over a fake resolver (no real DNS from a test): add, verify by
 * CNAME or TXT, Caddy's `ask`, `GET /branding` before and after, primary and removal.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PlatformDomainsIT extends AbstractIntegrationTest {
    static final String BASE = "/api/v1/platform/clubs/";
    static final String TARGET = "domains-target", OTHER = "domains-other";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired HostTenantResolver hosts;
    @Autowired FakeDomainDns dns;

    @BeforeEach void clubs() {
        wipeDatabaseKeepingBootstrap();
        dns.clear();
        clubs.save(PlatformFixtures.club(TARGET, "target.example.test"));
        clubs.save(PlatformFixtures.club(OTHER, "other.example.test"));
        hosts.invalidate();
    }
    MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder call) {
        return call.header("Host", "unknown.example.test").with(jwt().jwt(j -> j.subject("domains-actor"))
                .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")));
    }
    JsonNode call(MockHttpServletRequestBuilder call, Object body, int status) throws Exception {
        if (body != null) { call.contentType("application/json").content(mapper.writeValueAsString(body)); }
        var response = mvc.perform(platform(call)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    JsonNode add(String host, String app, int status) throws Exception {
        return call(post(BASE + TARGET + "/domains").header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("host", host, "app", app), status);
    }
    JsonNode verify(String host, String key, int status) throws Exception {
        return call(post(BASE + TARGET + "/domains/" + host + "/verification").header("Idempotency-Key", key), null, status);
    }
    int allowed(String host) throws Exception { return mvc.perform(get("/internal/domains/allowed").param("host", host)).andReturn().getResponse().getStatus(); }
    int branding(String host) throws Exception { return mvc.perform(get("/api/v1/branding").header("Host", host)).andReturn().getResponse().getStatus(); }
    Club.Domain stored(String host) {
        return clubs.findById(TARGET).orElseThrow().domains().stream().filter(d -> d.host().equals(host)).findFirst().orElseThrow();
    }
    long events(String type) { return mongo.count(Query.query(Criteria.where("type").is(type).and("clubId").is(TARGET)), "domain_events"); }

    @Test @AuditCovers(AuditAction.CLUB_DOMAIN_CHANGED)
    void T_17_03_R_17_03_cnameVerificationOpensCaddyAndBrandingOnlyOnceVerified() throws Exception {
        var added = add("App.Club.example.test.", "clubs", 201);
        assertThat(added.path("host").asText()).isEqualTo("app.club.example.test");
        assertThat(added.path("status").asText()).isEqualTo("PENDING");
        assertThat(added.path("primary").asBoolean()).isFalse();
        String token = stored("app.club.example.test").verificationToken();
        assertThat(token).hasSize(32).matches("[A-Za-z0-9_-]+");
        assertThat(added.path("verification")).isEqualTo(mapper.valueToTree(Map.of("recordName", "_agilityhub-verify.app.club.example.test",
                "recordValue", "agh-verify-" + token, "cnameTarget", "clubs.agilitydoghub.com")));
        // Pending: Caddy issues no certificate and the host resolves no club.
        assertThat(allowed("app.club.example.test")).isEqualTo(404);
        assertThat(branding("app.club.example.test")).isEqualTo(404);
        // Without a record: 422 with the records it expects; nothing changes and the key is released for the retry.
        String key = UUID.randomUUID().toString();
        long version = clubs.findById(TARGET).orElseThrow().version();
        var missing = verify("app.club.example.test", key, 422);
        assertThat(missing.path("code").asText()).isEqualTo("HOST_NOT_VERIFIED");
        assertThat(missing.path("details")).isEqualTo(mapper.valueToTree(Map.of("host", "app.club.example.test", "cnameTarget", "clubs.agilitydoghub.com",
                "recordName", "_agilityhub-verify.app.club.example.test", "recordValue", "agh-verify-" + token)));
        assertThat(clubs.findById(TARGET).orElseThrow().version()).isEqualTo(version);
        // A CNAME to the other app's product host is not this app's.
        dns.cname("app.club.example.test", "clubsadmin.agilitydoghub.com");
        assertThat(verify("app.club.example.test", key, 422).path("code").asText()).isEqualTo("HOST_NOT_VERIFIED");
        dns.cname("app.club.example.test", "Clubs.AgilityDogHub.com.");
        var verified = verify("app.club.example.test", key, 200);
        assertThat(verified.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(verified.path("method").asText()).isEqualTo("CNAME");
        assertThat(verified.path("checkedAt").asText()).isEqualTo(clock.instant().toString());
        // The same key replays the stored answer, without a second lookup or a second event.
        int lookups = dns.lookups().size();
        assertThat(verify("app.club.example.test", key, 200)).isEqualTo(verified);
        assertThat(dns.lookups()).hasSize(lookups);
        assertThat(events("ClubDomainVerified")).isEqualTo(1);
        var event = mongo.findOne(Query.query(Criteria.where("type").is("ClubDomainVerified")), Document.class, "domain_events");
        assertThat(event.get("payload", Document.class)).isEqualTo(new Document("clubId", TARGET).append("host", "app.club.example.test"));
        var domain = stored("app.club.example.test");
        assertThat(domain.status()).isEqualTo(Club.DomainStatus.VERIFIED);
        assertThat(domain.verifiedAt()).isEqualTo(clock.instant());
        assertThat(domain.verificationToken()).isNull();
        // The fixture's own verified clubs host stays the primary one: a second verified host does not take it over.
        assertThat(domain.primary()).isFalse();
        // Verified: Caddy's ask accepts it and /branding resolves the club right after the commit (no 5-minute cache).
        assertThat(allowed("app.club.example.test")).isEqualTo(200);
        assertThat(allowed("APP.club.example.test")).isEqualTo(200);
        var brand = mvc.perform(get("/api/v1/branding").header("Host", "app.club.example.test")).andReturn().getResponse();
        assertThat(brand.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(brand.getContentAsString()).path("club").path("slug").asText()).isEqualTo(TARGET);
        var club = call(get(BASE + TARGET), null, 200).path("domains");
        assertThat(club.findValues("host").stream().map(JsonNode::asText).toList()).contains("app.club.example.test");
        assertThat(club.toString()).doesNotContain(token);
        // Audited, without the challenge.
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(TARGET).and("action").is("CLUB_DOMAIN_CHANGED")), Document.class, "audit_entries");
        assertThat(audit).extracting(entry -> entry.getString("reason")).containsExactly("Domain added", "Domain verified");
        assertThat(audit.toString()).doesNotContain(token);
        // A verified domain has nothing left to verify.
        assertThat(verify("app.club.example.test", UUID.randomUUID().toString(), 409).path("code").asText()).isEqualTo("INVALID_STATE");
    }

    @Test void T_17_03_R_17_03_txtChallengeVerifiesAnApexDomainAndIsThenRemoved() throws Exception {
        add("club.example.test", "clubs-admin", 201);
        String token = stored("club.example.test").verificationToken();
        dns.txt("_agilityhub-verify.club.example.test", "\"agh-verify-another\"");
        assertThat(verify("club.example.test", UUID.randomUUID().toString(), 422).path("details").path("cnameTarget").asText())
                .isEqualTo("clubsadmin.agilitydoghub.com");
        dns.txt("_agilityhub-verify.club.example.test", "\"agh-verify-" + token + "\"");
        var verified = verify("club.example.test", UUID.randomUUID().toString(), 200);
        assertThat(verified.path("method").asText()).isEqualTo("TXT");
        assertThat(dns.lookups()).contains("CNAME club.example.test", "TXT _agilityhub-verify.club.example.test");
        var domain = stored("club.example.test");
        assertThat(domain.verificationToken()).isNull();
        // The first verified clubs-admin host is that app's primary one (the fixture's clubs-admin host is still pending).
        assertThat(domain.primary()).isTrue();
        assertThat(mongo.findById(TARGET, Document.class, "clubs").toJson()).doesNotContain(token);
        assertThat(call(get(BASE + TARGET), null, 200).path("domains").toString()).contains("\"verification\":null");
    }

    @Test void T_17_02_R_17_02_hostsAreUniqueNormalizedAndProductHostsReserved() throws Exception {
        long version = clubs.findById(TARGET).orElseThrow().version();
        // Another club's host, verified or pending, and this club's own: 409.
        for (String taken : List.of("other.example.test", "pending.other.example.test", "TARGET.example.test"))
            assertThat(add(taken, "clubs", 409).path("code").asText()).as(taken).isEqualTo("HOST_ALREADY_USED");
        for (String reserved : List.of("clubs.agilitydoghub.com", "clubsadmin.example.test", "ID.example.test", "core.example.test"))
            assertThat(add(reserved, "clubs", 422).path("code").asText()).as(reserved).isEqualTo("HOST_RESERVED");
        assertThat(add("localhost", "clubs", 400).path("details").path("field").asText()).isEqualTo("host");
        assertThat(call(post(BASE + TARGET + "/domains"), Map.of("host", "bad host", "app", "clubs"), 400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(call(post(BASE + TARGET + "/domains"), Map.of("host", "new.example.test", "app", "unknown"), 400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(clubs.findById(TARGET).orElseThrow().version()).isEqualTo(version);
        // A template club is never served, so it has no domains.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(OTHER)), new org.springframework.data.mongodb.core.query.Update().set("template", true).set("domains", List.of()), Club.class);
        assertThat(call(post(BASE + OTHER + "/domains"), Map.of("host", "template.example.test", "app", "clubs"), 400).path("details").path("field").asText()).isEqualTo("template");
        // Unknown club and another club's host under this club's path.
        assertThat(call(post(BASE + "domains-missing/domains"), Map.of("host", "new.example.test", "app", "clubs"), 404).path("code").asText()).isEqualTo("CLUB_NOT_FOUND");
        assertThat(verify("other.example.test", UUID.randomUUID().toString(), 404).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test void T_17_03_R_17_03_onlyPendingDomainsLeaveAndOnlyVerifiedOnesArePrimary() throws Exception {
        // S17 §5: PENDING → [*] only; a verified domain keeps serving its club.
        assertThat(call(delete(BASE + TARGET + "/domains/target.example.test").header("Idempotency-Key", UUID.randomUUID().toString()), null, 409)
                .path("details").path("status").asText()).isEqualTo("VERIFIED");
        call(delete(BASE + TARGET + "/domains/pending.target.example.test").header("Idempotency-Key", UUID.randomUUID().toString()), null, 204);
        assertThat(clubs.findById(TARGET).orElseThrow().domains()).extracting(Club.Domain::host).containsExactly("target.example.test");
        assertThat(call(delete(BASE + TARGET + "/domains/pending.target.example.test").header("Idempotency-Key", UUID.randomUUID().toString()), null, 404)
                .path("code").asText()).isEqualTo("NOT_FOUND");
        // Two verified clubs hosts: the primary one moves, the app's previous primary stops being it; the other app is untouched.
        add("second.example.test", "clubs", 201);
        assertThat(call(put(BASE + TARGET + "/domains/second.example.test/primary"), null, 409).path("details").path("status").asText()).isEqualTo("PENDING");
        dns.cname("second.example.test", "clubs.agilitydoghub.com");
        verify("second.example.test", UUID.randomUUID().toString(), 200);
        var domains = call(put(BASE + TARGET + "/domains/second.example.test/primary"), null, 200);
        assertThat(domains.findValues("host").stream().map(JsonNode::asText).toList()).containsExactly("target.example.test", "second.example.test");
        assertThat(stored("second.example.test").primary()).isTrue();
        assertThat(stored("target.example.test").primary()).isFalse();
        long version = clubs.findById(TARGET).orElseThrow().version();
        call(put(BASE + TARGET + "/domains/second.example.test/primary"), null, 200);
        assertThat(clubs.findById(TARGET).orElseThrow().version()).isEqualTo(version);
        // The club's links follow the primary host.
        try (var scope = com.agilityhub.core.shared.application.TenantContext.open(TARGET)) {
            assertThat(urls.login("clubs-app")).startsWith("https://second.example.test");
        }
    }
    @Autowired com.agilityhub.core.platform.application.ClubAppUrls urls;

    @Test void T_17_10_R_17_12_domainRoutesAreForThePlatformOnly() throws Exception {
        for (var request : List.of(post(BASE + TARGET + "/domains").contentType("application/json").content("{\"host\":\"new.example.test\",\"app\":\"clubs\"}"),
                post(BASE + TARGET + "/domains/target.example.test/verification"), put(BASE + TARGET + "/domains/target.example.test/primary"),
                delete(BASE + TARGET + "/domains/pending.target.example.test"))) {
            var response = mvc.perform(request.header("Host", "target.example.test").with(jwt().jwt(j -> j.subject("club-admin").claim("clubId", TARGET))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(403);
        }
        assertThat(clubs.findById(TARGET).orElseThrow().domains()).hasSize(2);
        assertThat(dns.lookups()).isEmpty();
    }
}

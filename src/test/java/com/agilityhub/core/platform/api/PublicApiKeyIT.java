package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.PublicClubAccess;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** S17 R-17-11 / T-17-11 (E10-T04): the club's public key is shown once, opens `/public/*` and overlaps 24 h after a rotation. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PublicApiKeyIT extends AbstractIntegrationTest {
    static final String CLUB = "public-key-club", OTHER = "public-key-other";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    Instant start;

    @BeforeEach void clubs() {
        wipeDatabaseKeepingBootstrap();
        clubs.save(PlatformFixtures.club(CLUB, "public-key.example.test"));
        clubs.save(PlatformFixtures.club(OTHER, "public-key-other.example.test"));
        start = clock.instant();
    }
    String rotate(String club) throws Exception {
        var response = mvc.perform(post("/api/v1/platform/clubs/" + club + "/public-api-key").header("Host", "unknown.example.test")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(jwt().jwt(j -> j.subject("key-actor")).authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return mapper.readTree(response.getContentAsString()).path("apiKey").asText();
    }
    int plans(String slug, String key) throws Exception {
        var request = get("/api/v1/public/" + slug + "/plans");
        if (key != null) { request.header("X-Api-Key", key); }
        var response = mvc.perform(request).andReturn().getResponse();
        if (response.getStatus() == 403) { assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("INVALID_API_KEY"); }
        return response.getStatus();
    }

    @Test @AuditCovers(AuditAction.PUBLIC_API_KEY_ROTATED)
    void T_17_11_R_17_11_keyIsShownOnceStoredAsADigestAndOpensThePublicRoutes() throws Exception {
        String key = rotate(CLUB);
        assertThat(key).startsWith("agh_pub_").hasSize("agh_pub_".length() + 43);
        var stored = mongo.findById(CLUB, Document.class, "clubs");
        assertThat(stored.getString("publicApiKeyHash")).isEqualTo(PublicClubAccess.digest(key));
        assertThat(stored.toJson()).doesNotContain(key);
        assertThat(stored.get("publicApiKeyPreviousHash")).isNull();
        assertThat(plans(CLUB, key)).isEqualTo(200);
        assertThat(plans(CLUB, key + "x")).isEqualTo(403);
        assertThat(plans(OTHER, key)).isEqualTo(403);
        // Never readable again: not in the console's club view, the audit or the outbox.
        var club = mvc.perform(get("/api/v1/platform/clubs/" + CLUB).header("Host", "unknown.example.test")
                .with(jwt().jwt(j -> j.subject("key-actor")).authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")))).andReturn().getResponse();
        assertThat(club.getContentAsString()).doesNotContain(key, PublicClubAccess.digest(key), "publicApiKey");
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("PUBLIC_API_KEY_ROTATED")), Document.class, "audit_entries");
        assertThat(audit).hasSize(1);
        assertThat(audit.toString()).doesNotContain(key, PublicClubAccess.digest(key));
        assertThat(mongo.findAll(Document.class, "domain_events").toString()).doesNotContain(key, PublicClubAccess.digest(key));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(OTHER).and("action").is("PUBLIC_API_KEY_ROTATED")), "audit_entries")).isZero();
    }

    @Test void T_17_11_R_17_11_rotationKeepsTheReplacedKeyForTwentyFourHours() throws Exception {
        String first = rotate(CLUB);
        clock.setInstant(start.plus(Duration.ofHours(1)));
        String second = rotate(CLUB);
        assertThat(second).isNotEqualTo(first);
        assertThat(plans(CLUB, second)).isEqualTo(200);
        assertThat(plans(CLUB, first)).isEqualTo(200);
        var audit = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("PUBLIC_API_KEY_ROTATED"))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "at")), Document.class, "audit_entries");
        assertThat(audit.get("details", Document.class).getString("previousValidUntil")).isEqualTo(start.plus(Duration.ofHours(25)).toString());
        clock.setInstant(start.plus(Duration.ofHours(25)).minusSeconds(1));
        assertThat(plans(CLUB, first)).isEqualTo(200);
        clock.setInstant(start.plus(Duration.ofHours(25)));
        assertThat(plans(CLUB, first)).isEqualTo(403);
        assertThat(plans(CLUB, second)).isEqualTo(200);
        // A rotation inside the overlap ends the older key at once; only the one it replaced overlaps.
        String third = rotate(CLUB);
        String fourth = rotate(CLUB);
        assertThat(plans(CLUB, second)).isEqualTo(403);
        assertThat(plans(CLUB, third)).isEqualTo(200);
        assertThat(plans(CLUB, fourth)).isEqualTo(200);
    }
}

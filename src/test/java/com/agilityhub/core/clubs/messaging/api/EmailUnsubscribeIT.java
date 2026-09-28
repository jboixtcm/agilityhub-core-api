package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.engine.UnsubscribeTokens;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T-11-23 (R-11-08) through `POST /api/v1/email-unsubscribes`, anonymous, the club taken from the host: a valid token of the
 * «Deixar de rebre aquests comunicats» link turns `emailByCategory.CLUB_NEWS` off for its member (the other preferences
 * stay) and publishes `EmailUnsubscribed{memberId}` once; an expired, tampered, another club's or an unknown member's token is
 * `422 UNSUBSCRIBE_TOKEN_INVALID` (CATALEG_ERRORS rule 0) and writes nothing.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class EmailUnsubscribeIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t02-unsub-a", OTHER = "e7t02-unsub-b", HOST = "unsub-a.example.test", OTHER_HOST = "unsub-b.example.test";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts; @Autowired UnsubscribeTokens tokens;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-07T08:00:00Z"));
        for (String id : List.of(CLUB, OTHER)) {
            mongo.remove(Query.query(Criteria.where("_id").is(id)), Club.class);
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id.equals(CLUB) ? HOST : OTHER_HOST));
            tree.set("modules", mapper.valueToTree(List.of(Module.PUSH)));
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
            mongo.remove(Query.query(Criteria.where("clubId").is(id)), "members"); mongo.remove(Query.query(Criteria.where("clubId").is(id)), "domain_events");
        }
        hosts.invalidate();
        mongo.save(new Document("_id", "unsub-laura").append("clubId", CLUB).append("status", "ACTIVE").append("firstName", "Laura").append("lastName1", "Serra")
                .append("contactEmails", List.of(new Document("email", "laura@example.test")))
                .append("notificationPreferences", new Document("emailByCategory", new Document("PERSONAL", false)).append("reminderMinutesBefore", 120).append("essentialOnly", false))
                .append("version", 0), "members");
    }
    private ResultActions unsubscribe(String host, String token) throws Exception {
        return mvc.perform(post("/api/v1/email-unsubscribes").header("Host", host).contentType("application/json").content(mapper.writeValueAsString(Map.of("token", token))));
    }
    private Document preferences() { return mongo.findById("unsub-laura", Document.class, "members").get("notificationPreferences", Document.class); }
    private long events() { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("EmailUnsubscribed")), "domain_events"); }

    @Test void T_11_23_aValidTokenTurnsClubNewsOffOnceAndKeepsTheOtherPreferences() throws Exception {
        String token = tokens.issue(CLUB, "unsub-laura");
        unsubscribe(HOST, token).andExpect(status().isOk()).andExpect(jsonPath("$.category").value("CLUB_NEWS"));
        var stored = preferences();
        assertThat(stored.get("emailByCategory", Document.class)).containsEntry("CLUB_NEWS", false).containsEntry("PERSONAL", false).containsEntry("CLUB_CHANGES", true);
        assertThat(stored).containsEntry("reminderMinutesBefore", 120).containsEntry("essentialOnly", false).containsEntry("updatedByAccountId", null);
        assertThat(events()).isEqualTo(1);
        var event = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("EmailUnsubscribed")), Document.class, "domain_events");
        assertThat(event.get("payload", Document.class)).containsExactlyEntriesOf(Map.of("memberId", "unsub-laura"));
        assertThat(event.toJson()).doesNotContain("laura@example.test");
        // Using the link again changes nothing and publishes nothing.
        unsubscribe(HOST, token).andExpect(status().isOk());
        assertThat(events()).isEqualTo(1);
        // Still valid until its 30th day.
        clock.advance(Duration.ofDays(30).minusMinutes(1));
        unsubscribe(HOST, token).andExpect(status().isOk());
    }

    @Test void T_11_23_expiredTamperedForeignAndUnknownTokensAre422AndWriteNothing() throws Exception {
        String token = tokens.issue(CLUB, "unsub-laura");
        var before = preferences();
        for (String invalid : List.of(token + "x", "a.b.c.d", tokens.issue(OTHER, "unsub-laura"), tokens.issue(CLUB, "unsub-nobody"))) {
            unsubscribe(HOST, invalid).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNSUBSCRIBE_TOKEN_INVALID"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
        }
        // The token of club A on club B's host: B is the tenant, so the token is foreign there.
        unsubscribe(OTHER_HOST, token).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNSUBSCRIBE_TOKEN_INVALID"));
        clock.advance(Duration.ofDays(30));
        unsubscribe(HOST, token).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNSUBSCRIBE_TOKEN_INVALID"));
        unsubscribe(HOST, " ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(preferences()).isEqualTo(before);
        assertThat(events()).isZero();
    }
}

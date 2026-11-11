package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
class ClassNoticesIT extends AbstractIntegrationTest {
    private static final String CLUB = "class-notices-a", OTHER = "class-notices-b";
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-11-11T12:00:00Z"));
        for (String club : List.of(CLUB, OTHER)) {
            mongo.remove(Query.query(Criteria.where("_id").is(club)), Club.class);
            clubs.save(PlatformFixtures.club(club, club + ".example.test"));
            configs.invalidate(club);
            mongo.remove(Query.query(Criteria.where("clubId").is(club)), "notifications");
        }
        hosts.invalidate();
        notice("yesterday", CLUB, "old", "N-16", "ADMINS", "2026-11-10T22:59:59Z", "DELIVERED");
        notice("midnight", CLUB, "midnight", "N-16", "ADMINS", "2026-11-10T23:00:00Z", "DELIVERED");
        for (int i = 0; i < 3; i++) notice("cancel-" + i, CLUB, "cancel", "N-17", "ADMINS", "2026-11-11T06:30:00Z", "DELIVERED");
        notice("instructor", CLUB, "cancel", "N-17", "INSTRUCTORS", "2026-11-11T06:30:00Z", "DELIVERED");
        notice("unsent", CLUB, "unsent", "N-17", "ADMINS", "2026-11-11T07:00:00Z", "SKIPPED_NO_CONTACT");
        notice("unrelated", CLUB, "unrelated", "N-04", "ADMINS", "2026-11-11T07:00:00Z", "DELIVERED");
        notice("foreign", OTHER, "foreign", "N-17", "ADMINS", "2026-11-11T07:00:00Z", "DELIVERED");
    }
    private void notice(String id, String club, String event, String code, String audience, String at, String status) {
        mongo.insert(new Document("_id", id).append("clubId", club).append("eventId", event).append("code", code).append("audience", audience)
                .append("createdAt", Date.from(Instant.parse(at))).append("subject", new Document("classSessionId", "same-class"))
                .append("title", "Class notice").append("body", "Frozen notice body")
                .append("variables", new Document("class_date", "11/11/2026").append("class_time", "18:50").append("class_description", "B+C").append("ring_name", "Central"))
                .append("deliveries", List.of(new Document("channel", "EMAIL").append("target", "staff@example.test").append("status", status).append("attempts", 1))), "notifications");
    }
    private JsonNode call(String club, String role, String query, int status) throws Exception {
        var response = mvc.perform(get("/api/v1/class-notices" + query).header("Host", club + ".example.test")
                .with(jwt().jwt(j -> j.subject("notice-admin").claim("clubId", club)).authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    @Test void T_11_26_classHistoryDeduplicatesStaffAndPreservesDistinctEventsWithStablePages() throws Exception {
        var first = call(CLUB, "ADMIN", "?size=2", 200);
        assertThat(first.path("totalItems").asInt()).isEqualTo(3);
        assertThat(first.path("items").findValuesAsText("id")).containsExactly("cancel-0", "midnight");
        assertThat(first.at("/items/0/classDescription").asText()).isEqualTo("B+C");
        assertThat(first.at("/items/0/classTime").asText()).isEqualTo("18:50");
        assertThat(first.toString()).doesNotContain("staff@example.test", "recipient", "deliveries");
        assertThat(call(CLUB, "ADMIN", "?page=1&size=2", 200).path("items").findValuesAsText("id")).containsExactly("yesterday");
        assertThat(call(CLUB, "ADMIN", "?page=2&size=2", 200).path("items")).isEmpty();
    }
    @Test void T_11_26_todayUsesTheClubDayAndFirstOccurrenceTime() throws Exception {
        notice("late-copy", CLUB, "old", "N-16", "ADMINS", "2026-11-11T06:31:00Z", "SENT");
        notice("tomorrow", CLUB, "tomorrow", "N-54", "ADMINS", "2026-11-11T23:00:00Z", "DELIVERED");
        var today = call(CLUB, "ADMIN", "?today=true", 200);
        assertThat(today.path("totalItems").asInt()).isEqualTo(2);
        assertThat(today.path("items").findValuesAsText("id")).containsExactly("cancel-0", "midnight");
    }
    @Test void T_11_29_classNoticesAreTenantAndAdminScoped() throws Exception {
        assertThat(call(OTHER, "ADMIN", "", 200).path("items").findValuesAsText("id")).containsExactly("foreign");
        call(CLUB, "MEMBER", "", 403); call(CLUB, "INSTRUCTOR", "", 403);
        var response = mvc.perform(get("/api/v1/class-notices").header("Host", CLUB + ".example.test")
                .with(jwt().jwt(j -> j.subject("notice-admin").claim("clubId", CLUB).claim("imp", true))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andReturn().getResponse();
        assertThat(response.getStatus()).as("Unbacked impersonation tokens are rejected before the controller").isEqualTo(401);
    }
    @Test void T_11_26_validatesPagingAndKeepsLegacyOccurrencesSeparate() throws Exception {
        call(CLUB, "ADMIN", "?page=-1", 400); call(CLUB, "ADMIN", "?size=0", 400); call(CLUB, "ADMIN", "?size=101", 400);
        for (String id : List.of("legacy-one", "legacy-two")) {
            notice(id, CLUB, null, "N-08a", "ADMINS", "2026-11-11T08:00:00Z", "SENT");
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new org.springframework.data.mongodb.core.query.Update().unset("variables").unset("title").unset("body"), "notifications");
        }
        var history = call(CLUB, "ADMIN", "", 200);
        assertThat(history.path("totalItems").asInt()).isEqualTo(5);
        assertThat(history.at("/items/0/classDate").asText()).isEmpty();
        assertThat(history.at("/items/0/body").asText()).isEmpty();
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "notifications");
        assertThat(call(CLUB, "ADMIN", "", 200).path("totalItems").asInt()).isZero();
    }
}

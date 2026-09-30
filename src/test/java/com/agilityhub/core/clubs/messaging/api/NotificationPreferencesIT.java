package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * T-11-20 (R-11-04), the «Avisos» block of 12 and D10: the product defaults when the member has no block (never stored on
 * reading); a partial `PUT` that keeps what it does not name (`null` reminder = «Mai», an absent one kept); a reminder outside
 * `messaging.reminderOptionsMinutes` → `422 INVALID_REMINDER_OPTION`; D10's save audited as `MEMBER_UPDATED` with the actor's
 * `actorAccountId` and `NotificationPreferencesChanged{memberId, diff, byAccountId}`; the impersonation token saves with
 * audit; `modules.sms/push` follow the club; `locale` is the account's and `availableLocales` the club's; another club's
 * member → 404.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NotificationPreferencesIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t03-pref-a", OTHER = "e7t03-pref-b", HOST = "pref-a.example.test", OTHER_HOST = "pref-b.example.test";
    static final String LAURA = "pref-laura", MEMBER = "pref-m-laura", ADMIN = "pref-admin";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts; @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z"));
        club(CLUB, List.of(Module.values())); club(OTHER, List.of(Module.values()));
        hosts.invalidate();
        for (String collection : List.of("members", "domain_events", "audit_entries", "memberships")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.save(new Document("_id", MEMBER).append("clubId", CLUB).append("accountId", LAURA).append("status", "ACTIVE").append("firstName", "Laura")
                .append("lastName1", "Serra").append("bookingBlock", Map.of("active", false)).append("version", 0L), "members");
        for (var account : List.of(Map.entry(LAURA, "es"), Map.entry(ADMIN, "ca"))) {
            mongo.save(new com.agilityhub.core.identity.persistence.Account(account.getKey(), account.getKey() + "@example.test", "Example " + account.getKey(),
                    account.getValue(), null, Set.of(), com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, clock.instant()));
        }
    }
    private void club(String clubId, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
        tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String account, String role, String club) {
        return request.header("Host", club.equals(CLUB) ? HOST : OTHER_HOST).contentType("application/json")
                .with(jwt().jwt(j -> j.subject(account).claim("clubId", club)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    private Document stored() { return mongo.findById(MEMBER, Document.class, "members").get("notificationPreferences", Document.class); }
    private List<Document> events() {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("NotificationPreferencesChanged")), Document.class, "domain_events");
    }
    private List<Document> audits() {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("MEMBER_UPDATED").and("entityId").is(MEMBER)), Document.class, "audit_entries");
    }

    @Test void T_11_20_theProductDefaultsWithoutABlockAndTheClubsOptionsModulesAndLanguages() throws Exception {
        var view = call(as(get("/api/v1/me/notification-preferences"), LAURA, "MEMBER", CLUB), 200);
        assertThat(view).isEqualTo(mapper.readTree("""
                {"emailByCategory": {"OPERATIONAL": false, "PERSONAL": true, "CLUB_CHANGES": true, "CLUB_NEWS": true}, "smsFixed": true,
                 "reminderMinutesBefore": null, "reminderOptionsMinutes": [60, 120, 240, 360, 720, 1440], "pushClubNews": true, "locale": "es",
                 "availableLocales": ["ca", "es", "en"], "modules": {"sms": true, "push": true}}"""));
        assertThat(stored()).isNull(); // reading never stores the defaults
        var modules = new ArrayList<>(List.of(Module.values())); modules.remove(Module.SMS); modules.remove(Module.PUSH);
        club(CLUB, modules);
        assertThat(call(as(get("/api/v1/me/notification-preferences"), LAURA, "MEMBER", CLUB), 200).path("modules"))
                .isEqualTo(mapper.readTree("{\"sms\": false, \"push\": false}"));
        // An instructor or an admin without the MEMBER role has no block of 12.
        call(as(get("/api/v1/me/notification-preferences"), ADMIN, "ADMIN", CLUB), 403);
    }

    @Test void T_11_20_aPartialSaveKeepsWhatItDoesNotNameAndValidatesTheReminder() throws Exception {
        var saved = call(as(put("/api/v1/me/notification-preferences").content("{\"emailByCategory\": {\"CLUB_NEWS\": false}, \"reminderMinutesBefore\": 120}"), LAURA, "MEMBER", CLUB), 200);
        assertThat(saved.path("emailByCategory")).isEqualTo(mapper.readTree("{\"OPERATIONAL\": false, \"PERSONAL\": true, \"CLUB_CHANGES\": true, \"CLUB_NEWS\": false}"));
        assertThat(saved.path("reminderMinutesBefore").asInt()).isEqualTo(120); assertThat(saved.path("pushClubNews").asBoolean()).isTrue();
        assertThat(stored().get("emailByCategory", Document.class)).containsEntry("CLUB_NEWS", false).containsEntry("OPERATIONAL", false);
        assertThat(stored()).containsEntry("reminderMinutesBefore", 120).containsEntry("updatedByAccountId", LAURA);
        // An absent key keeps its value; a JSON null reminder is «Mai».
        var push = call(as(put("/api/v1/me/notification-preferences").content("{\"pushClubNews\": false}"), LAURA, "MEMBER", CLUB), 200);
        assertThat(push.path("reminderMinutesBefore").asInt()).isEqualTo(120); assertThat(push.at("/emailByCategory/CLUB_NEWS").asBoolean()).isFalse();
        var never = call(as(put("/api/v1/me/notification-preferences").content("{\"reminderMinutesBefore\": null}"), LAURA, "MEMBER", CLUB), 200);
        assertThat(never.path("reminderMinutesBefore").isNull()).isTrue(); assertThat(never.path("pushClubNews").asBoolean()).isFalse();
        // Outside messaging.reminderOptionsMinutes → 422 INVALID_REMINDER_OPTION (CATALEG_ERRORS rule 0), nothing written.
        var before = stored();
        var invalid = call(as(put("/api/v1/me/notification-preferences").content("{\"reminderMinutesBefore\": 90}"), LAURA, "MEMBER", CLUB), 422);
        assertThat(invalid.path("code").asText()).isEqualTo("INVALID_REMINDER_OPTION");
        assertThat(stored()).isEqualTo(before);
        // A save that changes nothing writes nothing.
        int events = events().size();
        call(as(put("/api/v1/me/notification-preferences").content("{\"pushClubNews\": false}"), LAURA, "MEMBER", CLUB), 200);
        assertThat(events()).hasSize(events).hasSize(3);
        assertThat(events().getFirst().get("payload", Document.class)).containsEntry("memberId", MEMBER).containsEntry("byAccountId", LAURA)
                .containsKey("diff");
    }

    @Test @AuditCovers(AuditAction.MEMBER_UPDATED)
    void T_11_20_theD10SaveIsAuditedWithTheActorAndPublishesTheChange() throws Exception {
        var saved = call(as(put("/api/v1/members/" + MEMBER + "/notification-preferences").content("{\"emailByCategory\": {\"OPERATIONAL\": true}, \"pushClubNews\": false}"),
                ADMIN, "ADMIN", CLUB), 200);
        assertThat(saved.at("/emailByCategory/OPERATIONAL").asBoolean()).isTrue(); assertThat(saved.path("locale").asText()).isEqualTo("es");
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit.getString("actorAccountId")).isEqualTo(ADMIN); assertThat(audit.getString("memberId")).isEqualTo(MEMBER);
            assertThat(audit.getString("entityType")).isEqualTo("Member");
            assertThat(audit.getList("changes", Document.class)).extracting(c -> c.getString("path"))
                    .containsExactlyInAnyOrder("notificationPreferences.emailByCategory.OPERATIONAL", "notificationPreferences.pushClubNews");
            System.out.println("E7-T03 D10 MEMBER_UPDATED: actor=" + audit.getString("actorAccountId") + " member=" + audit.getString("memberId") + " changes="
                    + audit.getList("changes", Document.class).stream().map(c -> c.getString("path") + " " + c.get("before") + "→" + c.get("after")).toList());
        });
        assertThat(events()).singleElement().satisfies(event -> {
            var payload = event.get("payload", Document.class);
            assertThat(payload.getString("byAccountId")).isEqualTo(ADMIN); assertThat(payload.getString("memberId")).isEqualTo(MEMBER);
            assertThat(payload.get("diff", Document.class)).containsOnlyKeys("emailByCategory", "pushClubNews");
        });
        assertThat(stored()).containsEntry("updatedByAccountId", ADMIN);
        // Another club's member is 404; an unknown one too; the member cannot save someone else's block.
        call(as(put("/api/v1/members/" + MEMBER + "/notification-preferences").content("{\"pushClubNews\": true}"), ADMIN, "ADMIN", OTHER), 404);
        call(as(put("/api/v1/members/pref-nobody/notification-preferences").content("{\"pushClubNews\": true}"), ADMIN, "ADMIN", CLUB), 404);
        call(as(put("/api/v1/members/" + MEMBER + "/notification-preferences").content("{\"pushClubNews\": true}"), LAURA, "MEMBER", CLUB), 403);
        call(as(put("/api/v1/members/" + MEMBER + "/notification-preferences").content("{\"reminderMinutesBefore\": 5}"), ADMIN, "ADMIN", CLUB), 422);
        assertThat(audits()).hasSize(1);
    }

    @Test void T_11_28_theImpersonationTokenSavesTheMembersBlockWithAudit() throws Exception {
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(ADMIN, ADMIN, CLUB, null, Set.of(com.agilityhub.core.identity.domain.Role.ADMIN),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.ADMIN));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership(LAURA, LAURA, CLUB, MEMBER, Set.of(com.agilityhub.core.identity.domain.Role.MEMBER),
                com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.MEMBER));
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try (var scope = TenantContext.open(CLUB)) { issued = impersonations.create(ADMIN, MEMBER, "Help with the alerts"); }
        var request = put("/api/v1/me/notification-preferences").header("Host", HOST).contentType("application/json").content("{\"reminderMinutesBefore\": 60}")
                .with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"));
        assertThat(call(request, 200).path("reminderMinutesBefore").asInt()).isEqualTo(60);
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit.getString("impersonatedMemberId")).isEqualTo(MEMBER); assertThat(audit.getString("origin")).isEqualTo("BACKOFFICE"); });
        assertThat(events().getFirst().get("payload", Document.class).getString("byAccountId")).isEqualTo(ADMIN);
        var read = get("/api/v1/me/notification-preferences").header("Host", HOST).with(jwt().jwt(issued.token()).authorities(() -> "ROLE_MEMBER"));
        assertThat(call(read, 200).path("reminderMinutesBefore").asInt()).isEqualTo(60);
    }
}

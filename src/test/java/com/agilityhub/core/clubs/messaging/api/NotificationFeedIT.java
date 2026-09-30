package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * T-11-19 (R-11-10, R-11-11), feed 11 through `GET /me/notifications` and `POST /me/notifications/{id}/read|read-all`: only the
 * caller's notices with a delivered APP delivery (the `MEMBER` and the `INSTRUCTORS` copies in the same feed, `audience`
 * filters one), `createdAt desc` and paged; `channels` with SMS/PUSH only when SENT or DELIVERED and never EMAIL; a notice
 * without APP (N-38) or a stale reminder absent from the feed but in the log; `action.type/params` per code and `enabled`
 * computed on reading (`CLAIM_SEAT` false once the entry is no longer NOTIFIED, `CHANGE_CLASS` false for a dog the member
 * cannot book any more); `read`/`read-all` idempotent with the same `unreadCount` as `GET /me/home`; another account's → 404.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NotificationFeedIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t03-feed-a", OTHER = "e7t03-feed-b", HOST = "feed-a.example.test", OTHER_HOST = "feed-b.example.test";
    static final String LAURA = "feed-laura", MARTA = "feed-marta";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    Instant now;

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z")); now = clock.instant();
        for (String id : List.of(CLUB, OTHER)) {
            mongo.remove(Query.query(Criteria.where("_id").is(id)), Club.class);
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id.equals(CLUB) ? HOST : OTHER_HOST));
            tree.set("modules", mapper.valueToTree(List.of(Module.values())));
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(id);
            for (String collection : List.of("notifications", "members", "dogs", "waitlist_entries", "family_groups")) {
                mongo.remove(Query.query(Criteria.where("clubId").is(id)), collection);
            }
        }
        hosts.invalidate();
        mongo.save(member("feed-m-laura", LAURA), "members"); mongo.save(member("feed-m-marta", MARTA), "members");
        mongo.save(dog("feed-duna", "feed-m-laura", "ACTIVE"), "dogs"); mongo.save(dog("feed-rock", "feed-m-laura", "INACTIVE"), "dogs");
        mongo.save(new Document("_id", "feed-w-live").append("clubId", CLUB).append("state", "NOTIFIED").append("confirmBy", Date.from(now.plusSeconds(1800)))
                .append("memberId", "feed-m-laura").append("dogId", "feed-duna").append("position", 1).append("version", 0L), "waitlist_entries");
        mongo.save(new Document("_id", "feed-w-gone").append("clubId", CLUB).append("state", "CONSOLIDATED").append("memberId", "feed-m-laura").append("dogId", "feed-duna")
                .append("position", 2).append("version", 0L), "waitlist_entries");
        var app = delivery(NotificationChannel.APP, LAURA, DeliveryStatus.DELIVERED);
        insert(notice("feed-n08a", CLUB, LAURA, "N-08a", NotificationAudience.MEMBER, now.minusSeconds(60), action(NotificationActionType.CHANGE_CLASS, "dogId", "feed-duna"),
                app, delivery(NotificationChannel.EMAIL, "laura@example.test", DeliveryStatus.SENT), delivery(NotificationChannel.SMS, "+34600000001", DeliveryStatus.SENT),
                delivery(NotificationChannel.SMS, "+34600000002", DeliveryStatus.FAILED)));
        insert(notice("feed-n15", CLUB, LAURA, "N-15", NotificationAudience.MEMBER, now.minusSeconds(120),
                action(NotificationActionType.CLAIM_SEAT, "waitlistEntryId", "feed-w-live", "classSessionId", "feed-c1", "dogId", "feed-duna"),
                app, delivery(NotificationChannel.SMS, "+34600000001", DeliveryStatus.QUEUED), delivery(NotificationChannel.PUSH, "feed-sub", DeliveryStatus.SENT)));
        insert(notice("feed-n15-old", CLUB, LAURA, "N-15", NotificationAudience.MEMBER, now.minusSeconds(3600),
                action(NotificationActionType.CLAIM_SEAT, "waitlistEntryId", "feed-w-gone", "classSessionId", "feed-c0", "dogId", "feed-duna"), app));
        insert(notice("feed-n16-rock", CLUB, LAURA, "N-16", NotificationAudience.MEMBER, now.minusSeconds(7200), action(NotificationActionType.CHANGE_CLASS, "dogId", "feed-rock"), app));
        // No APP delivery (N-38 is e-mail only) and a stale reminder: in the log, never in the feed.
        insert(notice("feed-n38", CLUB, LAURA, "N-38", NotificationAudience.MEMBER, now.minusSeconds(30), null, delivery(NotificationChannel.EMAIL, "laura@example.test", DeliveryStatus.SENT)));
        insert(notice("feed-n13-stale", CLUB, LAURA, "N-13", NotificationAudience.MEMBER, now.minusSeconds(20), null, delivery(NotificationChannel.APP, null, DeliveryStatus.SKIPPED_STALE)));
        // Marta (an instructor who is a member too) gets a member notice and N-21 as an instructor.
        insert(notice("feed-n21", CLUB, MARTA, "N-21", NotificationAudience.INSTRUCTORS, now.minusSeconds(10), action(NotificationActionType.OPEN_DOG, "dogId", "feed-duna"),
                delivery(NotificationChannel.APP, MARTA, DeliveryStatus.DELIVERED)));
        insert(notice("feed-n04-marta", CLUB, MARTA, "N-04", NotificationAudience.MEMBER, now.minusSeconds(50), null, delivery(NotificationChannel.APP, MARTA, DeliveryStatus.DELIVERED)));
        // Laura's account in another club: never in this club's feed.
        insert(notice("feed-other", OTHER, LAURA, "N-04", NotificationAudience.MEMBER, now.minusSeconds(5), null, delivery(NotificationChannel.APP, LAURA, DeliveryStatus.DELIVERED)));
    }
    private static Document member(String id, String account) {
        return new Document("_id", id).append("clubId", CLUB).append("accountId", account).append("status", "ACTIVE").append("firstName", "Example")
                .append("lastName1", "Example").append("bookingBlock", Map.of("active", false)).append("version", 0L);
    }
    private static Document dog(String id, String member, String status) {
        return new Document("_id", id).append("clubId", CLUB).append("memberId", member).append("name", id).append("status", status).append("version", 0L);
    }
    private static Notification.Delivery delivery(NotificationChannel channel, String target, DeliveryStatus status) {
        return new Notification.Delivery(channel, target, status, status == DeliveryStatus.QUEUED ? 0 : 1, null, null, null, null, null, null);
    }
    private static Notification.Action action(NotificationActionType type, String... params) {
        var map = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < params.length; i += 2) { map.put(params[i], params[i + 1]); }
        return new Notification.Action(type, map);
    }
    static Notification notice(String id, String club, String account, String code, NotificationAudience audience, Instant at, Notification.Action action,
            Notification.Delivery... deliveries) {
        var spec = NotificationCatalog.byCode(code).orElseThrow();
        return new Notification(id, club, code, spec.category(), "tpl-" + code, 0L, "evt-" + id, "Example", "dedup-" + id, audience,
                new Notification.Recipient(account, null, null, null, "Laura Example"), "ca", new Notification.Subject(null, null, null, null, null, null, null, null, null),
                spec.icon(), spec.color(), "Títol " + id, "Cos " + id, null, action, List.of(deliveries), null, at, null, null, null, null, null, null, null, null);
    }
    private void insert(Notification notification) { mongo.insert(notification); }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String account, String role) {
        String member = account.equals(LAURA) ? "feed-m-laura" : account.equals(MARTA) ? "feed-m-marta" : null;
        return request.header("Host", HOST).with(jwt().jwt(j -> { j.subject(account).claim("clubId", CLUB); if (member != null) { j.claim("memberId", member); } })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }
    private static List<String> ids(JsonNode page) { var ids = new ArrayList<String>(); page.path("items").forEach(i -> ids.add(i.path("id").asText())); return ids; }
    private JsonNode item(JsonNode page, String id) {
        for (var item : page.path("items")) { if (item.path("id").asText().equals(id)) { return item; } }
        throw new AssertionError(id);
    }
    private long homeUnread() throws Exception {
        return call(as(get("/api/v1/me/home"), LAURA, "MEMBER"), 200).at("/notifications/unreadCount").asLong();
    }

    @Test void T_11_19_theFeedHoldsTheCallersAppNoticesNewestFirstWithChannelsAndActions() throws Exception {
        var page = call(as(get("/api/v1/me/notifications"), LAURA, "MEMBER"), 200);
        assertThat(ids(page)).containsExactly("feed-n08a", "feed-n15", "feed-n15-old", "feed-n16-rock");
        assertThat(page.path("totalItems").asLong()).isEqualTo(4); assertThat(page.path("size").asInt()).isEqualTo(20); assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("unreadCount").asLong()).isEqualTo(4).isEqualTo(homeUnread());
        var n08a = item(page, "feed-n08a");
        assertThat(n08a.path("channels")).hasToString("[\"APP\",\"SMS\"]"); // the SENT SMS («i per SMS»), never the e-mail
        assertThat(n08a.path("icon").asText()).isEqualTo("x"); assertThat(n08a.path("color").asText()).isEqualTo("ERROR");
        assertThat(n08a.path("title").asText()).isEqualTo("Títol feed-n08a"); assertThat(n08a.path("readAt").isNull()).isTrue();
        assertThat(n08a.path("action")).isEqualTo(mapper.readTree("{\"type\":\"CHANGE_CLASS\",\"params\":{\"dogId\":\"feed-duna\"},\"enabled\":true}"));
        var n15 = item(page, "feed-n15");
        assertThat(n15.path("channels")).hasToString("[\"APP\",\"PUSH\"]"); // the SMS is still QUEUED
        assertThat(n15.at("/action/type").asText()).isEqualTo("CLAIM_SEAT"); assertThat(n15.at("/action/enabled").asBoolean()).isTrue();
        assertThat(n15.at("/action/params/classSessionId").asText()).isEqualTo("feed-c1");
        // The entry is no longer NOTIFIED; the dog is inactive: the buttons are disabled.
        assertThat(item(page, "feed-n15-old").at("/action/enabled").asBoolean()).isFalse();
        assertThat(item(page, "feed-n16-rock").at("/action/enabled").asBoolean()).isFalse();
        // A FIFO offer past its confirmBy is disabled too.
        clock.setInstant(now.plusSeconds(3600));
        assertThat(item(call(as(get("/api/v1/me/notifications"), LAURA, "MEMBER"), 200), "feed-n15").at("/action/enabled").asBoolean()).isFalse();
        clock.setInstant(now);
        // Pages of the same order.
        var first = call(as(get("/api/v1/me/notifications").param("size", "3"), LAURA, "MEMBER"), 200);
        var second = call(as(get("/api/v1/me/notifications").param("size", "3").param("page", "1"), LAURA, "MEMBER"), 200);
        assertThat(ids(first)).containsExactly("feed-n08a", "feed-n15", "feed-n15-old"); assertThat(ids(second)).containsExactly("feed-n16-rock");
        call(as(get("/api/v1/me/notifications").param("size", "101"), LAURA, "MEMBER"), 400);
        assertThat(call(as(get("/api/v1/me/notifications").param("page", "-1"), LAURA, "MEMBER"), 400).at("/details/fieldErrors/0/field").asText()).isEqualTo("page");
        // The e-mail-only notice and the stale reminder are in the log, not in the feed.
        var log = call(as(get("/api/v1/notifications").param("filter", "code:in:N-38,N-13"), "feed-admin", "ADMIN"), 200);
        assertThat(ids(log)).containsExactlyInAnyOrder("feed-n38", "feed-n13-stale");
    }

    @Test void T_11_19_anInstructorReadsItsStaffNoticesInTheSameFeedAndAudienceFiltersThem() throws Exception {
        var feed = call(as(get("/api/v1/me/notifications"), MARTA, "INSTRUCTOR"), 200);
        assertThat(ids(feed)).containsExactly("feed-n21", "feed-n04-marta");
        assertThat(item(feed, "feed-n21").at("/action/type").asText()).isEqualTo("OPEN_DOG");
        assertThat(ids(call(as(get("/api/v1/me/notifications").param("audience", "INSTRUCTORS"), MARTA, "INSTRUCTOR"), 200))).containsExactly("feed-n21");
        assertThat(ids(call(as(get("/api/v1/me/notifications").param("audience", "MEMBER"), MARTA, "MEMBER"), 200))).containsExactly("feed-n04-marta");
    }

    @Test void T_11_19_readAndReadAllAreIdempotentAndTheBellAgrees() throws Exception {
        var read = call(as(post("/api/v1/me/notifications/feed-n15/read"), LAURA, "MEMBER"), 200);
        assertThat(read.path("unreadCount").asLong()).isEqualTo(3).isEqualTo(homeUnread());
        Instant firstRead = mongo.findById("feed-n15", Notification.class).readAt();
        assertThat(firstRead).isEqualTo(now);
        clock.setInstant(now.plusSeconds(60));
        assertThat(call(as(post("/api/v1/me/notifications/feed-n15/read"), LAURA, "MEMBER"), 200).path("unreadCount").asLong()).isEqualTo(3);
        assertThat(mongo.findById("feed-n15", Notification.class).readAt()).isEqualTo(firstRead);
        assertThat(item(call(as(get("/api/v1/me/notifications"), LAURA, "MEMBER"), 200), "feed-n15").path("readAt").asText()).isEqualTo(firstRead.toString());
        // Another account's notice or another club's is not found; nothing changes.
        call(as(post("/api/v1/me/notifications/feed-n21/read"), LAURA, "MEMBER"), 404);
        call(as(post("/api/v1/me/notifications/feed-other/read"), LAURA, "MEMBER"), 404);
        call(as(post("/api/v1/me/notifications/feed-missing/read"), LAURA, "MEMBER"), 404);
        assertThat(mongo.findById("feed-n21", Notification.class).readAt()).isNull();
        // read-all marks what was created until now; a notice created later stays unread.
        insert(notice("feed-later", CLUB, LAURA, "N-04", NotificationAudience.MEMBER, clock.instant().plusSeconds(5), null, delivery(NotificationChannel.APP, LAURA, DeliveryStatus.DELIVERED)));
        assertThat(call(as(post("/api/v1/me/notifications/read-all"), LAURA, "MEMBER"), 200).path("unreadCount").asLong()).isEqualTo(1).isEqualTo(homeUnread());
        assertThat(call(as(post("/api/v1/me/notifications/read-all"), LAURA, "MEMBER"), 200).path("unreadCount").asLong()).isEqualTo(1);
        assertThat(mongo.findById("feed-n15", Notification.class).readAt()).isEqualTo(firstRead);
        assertThat(mongo.findById("feed-n21", Notification.class).readAt()).isNull(); // Marta's
        assertThat(mongo.findById("feed-other", Notification.class).readAt()).isNull(); // the other club's
    }
}

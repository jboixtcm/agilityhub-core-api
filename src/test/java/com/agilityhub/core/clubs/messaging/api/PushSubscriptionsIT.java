package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
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
import java.util.Base64;
import java.util.List;
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
 * T-11-21 (R-11-07, R-11-17), the push devices through `POST/DELETE /push-subscriptions`: an upsert by endpoint (stored
 * with its SHA-256; the same browser again is the same row, `ACTIVE` again after a logout), `deviceLabel` from the User-Agent,
 * `PushSubscribed`/`PushUnsubscribed` with the endpoint's hash, never the URL; a malformed endpoint or key → `422
 * PUSH_SUBSCRIPTION_INVALID`; another account's or club's subscription → 404; `PUSH` off → `404 MODULE_DISABLED`; the
 * impersonation token is refused. The deliveries of `PUSH`/`SMS` off (`SKIPPED_MODULE_OFF`) are the engine's
 * (`NotificationEngineIT`, E7-T02); the SMS column of D9 is `MessageTemplatesIT`.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PushSubscriptionsIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t03-push-a", OTHER = "e7t03-push-b", HOST = "push-a.example.test", OTHER_HOST = "push-b.example.test";
    static final String P256DH = Base64.getUrlEncoder().withoutPadding().encodeToString(key(65, (byte) 4)), AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(key(16, (byte) 7));
    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;

    static byte[] key(int size, byte first) { var bytes = new byte[size]; java.util.Arrays.fill(bytes, (byte) 0x2a); bytes[0] = first; return bytes; }

    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z"));
        club(CLUB, List.of(Module.values())); club(OTHER, List.of(Module.values()));
        hosts.invalidate();
        for (String collection : List.of("push_subscriptions", "domain_events")) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
    }
    private void club(String clubId, List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : OTHER_HOST));
        tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String account, String role, String club) {
        return request.header("Host", club.equals(CLUB) ? HOST : OTHER_HOST).header("User-Agent", IPHONE).contentType("application/json")
                .with(jwt().jwt(j -> j.subject(account).claim("clubId", club)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }
    private String body(String endpoint, String p256dh, String auth, String label) {
        var body = mapper.createObjectNode().put("endpoint", endpoint);
        body.putObject("keys").put("p256dh", p256dh).put("auth", auth);
        if (label != null) { body.put("deviceLabel", label); }
        return body.toString();
    }
    private List<Document> events(String type) {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("type").is(type)), Document.class, "domain_events");
    }

    @Test void T_11_21_anUpsertByEndpointWithTheLabelFromTheUserAgentAndItsEvents() throws Exception {
        String endpoint = "https://push.example.test/send/device-1";
        String id = call(as(post("/api/v1/push-subscriptions").content(body(endpoint, P256DH, AUTH, null)), "push-laura", "MEMBER", CLUB), 201).path("id").asText();
        var stored = mongo.findById(id, PushSubscription.class);
        assertThat(stored.accountId()).isEqualTo("push-laura"); assertThat(stored.status()).isEqualTo(PushSubscription.Status.ACTIVE);
        assertThat(stored.deviceLabel()).isEqualTo("iPhone · Safari"); assertThat(stored.endpointHash()).isEqualTo(PushSubscription.hash(endpoint));
        assertThat(events("PushSubscribed")).singleElement().satisfies(e -> {
            assertThat(e.get("payload", Document.class)).containsEntry("accountId", "push-laura").containsEntry("endpoint", PushSubscription.hash(endpoint));
            assertThat(e.toJson()).doesNotContain("push.example.test", P256DH, AUTH);
        });
        // The same browser again: the same row, nothing new; new keys or a label update it.
        assertThat(call(as(post("/api/v1/push-subscriptions").content(body(endpoint, P256DH, AUTH, null)), "push-laura", "MEMBER", CLUB), 201).path("id").asText()).isEqualTo(id);
        assertThat(events("PushSubscribed")).hasSize(1);
        assertThat(call(as(post("/api/v1/push-subscriptions").content(body(endpoint, P256DH, AUTH, "Mòbil de la Laura")), "push-laura", "MEMBER", CLUB), 201)
                .path("id").asText()).isEqualTo(id);
        assertThat(mongo.findById(id, PushSubscription.class).deviceLabel()).isEqualTo("Mòbil de la Laura");
        // Logout: EXPIRED + PushUnsubscribed, idempotent; subscribing again reactivates the same row.
        call(as(delete("/api/v1/push-subscriptions/" + id), "push-laura", "MEMBER", CLUB), 204);
        call(as(delete("/api/v1/push-subscriptions/" + id), "push-laura", "MEMBER", CLUB), 204);
        assertThat(mongo.findById(id, PushSubscription.class).status()).isEqualTo(PushSubscription.Status.EXPIRED);
        assertThat(events("PushUnsubscribed")).hasSize(1);
        call(as(post("/api/v1/push-subscriptions").content(body(endpoint, P256DH, AUTH, null)), "push-laura", "MEMBER", CLUB), 201);
        var again = mongo.findById(id, PushSubscription.class);
        assertThat(again.status()).isEqualTo(PushSubscription.Status.ACTIVE); assertThat(again.expiredAt()).isNull();
        // Another account on the same browser takes it over; one row per endpoint and club.
        call(as(post("/api/v1/push-subscriptions").content(body(endpoint, P256DH, AUTH, null)), "push-marc", "INSTRUCTOR", CLUB), 201);
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), PushSubscription.class)).singleElement()
                .satisfies(s -> assertThat(s.accountId()).isEqualTo("push-marc"));
        assertThat(events("PushSubscribed")).hasSize(4);
        System.out.println("E7-T03 push: " + events("PushSubscribed").size() + " PushSubscribed, " + events("PushUnsubscribed").size() + " PushUnsubscribed");
    }

    @Test void T_11_21_malformedEndpointsAndKeysAreRejected() throws Exception {
        var bad = new ArrayList<String>();
        bad.add(body("http://push.example.test/send/x", P256DH, AUTH, null)); // not https
        bad.add(body("https:///no-host", P256DH, AUTH, null));
        bad.add(body("https://push.example.test/send/x", "not+base64/url", AUTH, null));
        bad.add(body("https://push.example.test/send/x", Base64.getUrlEncoder().withoutPadding().encodeToString(key(64, (byte) 4)), AUTH, null)); // not 65 bytes
        bad.add(body("https://push.example.test/send/x", Base64.getUrlEncoder().withoutPadding().encodeToString(key(65, (byte) 3)), AUTH, null)); // not an uncompressed point
        bad.add(body("https://push.example.test/send/x", P256DH, Base64.getUrlEncoder().withoutPadding().encodeToString(key(12, (byte) 1)), null)); // not 16 bytes
        for (String body : bad) {
            assertThat(call(as(post("/api/v1/push-subscriptions").content(body), "push-laura", "MEMBER", CLUB), 422).path("code").asText()).as(body)
                    .isEqualTo("PUSH_SUBSCRIPTION_INVALID");
        }
        assertThat(call(as(post("/api/v1/push-subscriptions").content("{\"endpoint\":\"https://push.example.test/x\"}"), "push-laura", "MEMBER", CLUB), 400)
                .path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), PushSubscription.class)).isZero();
        assertThat(events("PushSubscribed")).isEmpty();
    }

    @Test void T_11_21_T_11_29_anotherAccountsOrClubsDeviceIsNotFoundAndPushOffHidesTheRoutes() throws Exception {
        String id = call(as(post("/api/v1/push-subscriptions").content(body("https://push.example.test/send/device-2", P256DH, AUTH, null)), "push-laura", "MEMBER", CLUB), 201)
                .path("id").asText();
        call(as(delete("/api/v1/push-subscriptions/" + id), "push-marc", "MEMBER", CLUB), 404);
        call(as(delete("/api/v1/push-subscriptions/" + id), "push-laura", "MEMBER", OTHER), 404);
        assertThat(mongo.findById(id, PushSubscription.class).status()).isEqualTo(PushSubscription.Status.ACTIVE);
        var modules = new ArrayList<>(List.of(Module.values())); modules.remove(Module.PUSH);
        club(CLUB, modules);
        assertThat(call(as(post("/api/v1/push-subscriptions").content(body("https://push.example.test/send/device-3", P256DH, AUTH, null)), "push-laura", "MEMBER", CLUB), 404)
                .path("code").asText()).isEqualTo("MODULE_DISABLED");
        assertThat(call(as(delete("/api/v1/push-subscriptions/" + id), "push-laura", "MEMBER", CLUB), 404).path("code").asText()).isEqualTo("MODULE_DISABLED");
    }

    @Test void T_11_21_theDeviceLabelFromCommonUserAgents() throws Exception {
        var cases = List.of(
                List.of("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36", "Android · Chrome"),
                List.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:131.0) Gecko/20100101 Firefox/131.0", "Mac · Firefox"),
                List.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0", "Windows · Edge"));
        int index = 0;
        for (var example : cases) {
            var request = post("/api/v1/push-subscriptions").content(body("https://push.example.test/send/ua-" + index++, P256DH, AUTH, null)).header("Host", HOST)
                    .header("User-Agent", example.get(0)).contentType("application/json")
                    .with(jwt().jwt(j -> j.subject("push-laura").claim("clubId", CLUB)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
            String id = call(request, 201).path("id").asText();
            assertThat(mongo.findById(id, PushSubscription.class).deviceLabel()).isEqualTo(example.get(1));
        }
    }
}

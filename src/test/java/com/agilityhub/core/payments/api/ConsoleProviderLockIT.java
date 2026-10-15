package com.agilityhub.core.payments.api;

import com.agilityhub.core.clubs.census.application.MemberService;
import com.agilityhub.core.platform.application.PlatformProviders;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * S17 R-17-05 (E10-T02 round 2, review #3): a member's payment-method assignment and the console's removal of that provider
 * share one tenant lock, so whichever transaction commits second sees the first. Each test holds one side's transaction open
 * at a deterministic point and runs the other side against it; without the shared lock both commit and a member is left on a
 * provider that no longer exists.
 */
class ConsoleProviderLockIT extends BillingItSupport {
    @Autowired PlatformProviders providers;
    @Autowired MemberService members;
    @Autowired TransactionTemplate transactions;

    /** No member collects through MANUAL yet, so the console may remove it. */
    @BeforeEach void nobodyPaysManually() {
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB).and("paymentMethod.type").is("MANUAL")),
                new Update().set("paymentMethod", new Document("type", "SEPA_DD").append("iban", IBAN).append("holderName", "Example Holder")), "members");
        configs.invalidate(CLUB);
    }
    long version() { return clubs.findById(CLUB).orElseThrow().version(); }
    boolean manualConfigured() { return clubs.findById(CLUB).orElseThrow().paymentProviders().containsKey("MANUAL"); }
    String method(String member) { return mongo.findById(member, Document.class, "members").get("paymentMethod", Document.class).getString("type"); }
    static <T> T within(Future<T> future, long seconds) throws Exception {
        try { return future.get(seconds, TimeUnit.SECONDS); } catch (TimeoutException waiting) { return null; }
    }
    static void await(CountDownLatch latch) {
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @Test void T_17_05_R_17_05_assignmentWaitsForAnUncommittedProviderRemoval() throws Exception {
        long version = version();
        var inside = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var console = pool.submit(() -> {
                try (var tenant = TenantContext.open(CLUB)) {
                    return transactions.execute(tx -> { providers.remove("MANUAL", version); inside.countDown(); await(release); return "OK"; });
                } catch (ApiException failure) { inside.countDown(); return failure.getMessage(); }
            });
            assertThat(inside.await(20, TimeUnit.SECONDS)).isTrue();
            var assignment = pool.submit(() -> mvc.perform(admin(patch("/api/v1/members/puig/payment-method")).contentType("application/json")
                    .content("{\"type\":\"MANUAL\",\"manual\":{\"channel\":\"cash\"}}")).andReturn().getResponse());
            // Without the shared lock the assignment reads MANUAL as enabled and commits while the removal is still open.
            MockHttpServletResponse early = within(assignment, 2);
            release.countDown();
            assertThat(console.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
            var response = early != null ? early : assignment.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(400, 499);
            assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("PAYMENT_PROVIDER_NOT_ENABLED");
        }
        assertThat(manualConfigured()).isFalse();
        assertThat(method("puig")).isEqualTo("SEPA_DD");
    }

    @Test void T_17_05_R_17_05_removalWaitsForAnUncommittedAssignmentAndCountsIt() throws Exception {
        var inside = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var assignment = pool.submit(() -> {
                try (var tenant = TenantContext.open(CLUB)) {
                    return transactions.execute(tx -> {
                        members.payment("puig", Map.of("type", "MANUAL", "manual", Map.of("channel", "cash")));
                        inside.countDown(); await(release); return "OK";
                    });
                } catch (ApiException failure) { inside.countDown(); return failure.getMessage(); }
            });
            assertThat(inside.await(20, TimeUnit.SECONDS)).isTrue();
            long version = version();
            var removal = pool.submit(() -> mvc.perform(delete("/api/v1/platform/clubs/" + CLUB + "/payment-providers/MANUAL").param("version", Long.toString(version))
                    .header("Host", "unknown.example.test").with(jwt().jwt(j -> j.subject("console-actor"))
                            .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")))).andReturn().getResponse());
            // Without the shared lock the removal counts zero users and commits while the assignment is still open.
            MockHttpServletResponse early = within(removal, 2);
            release.countDown();
            assertThat(assignment.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
            var response = early != null ? early : removal.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
            var error = mapper.readTree(response.getContentAsString());
            assertThat(error.path("code").asText()).isEqualTo("PROVIDER_IN_USE");
            assertThat(error.path("details").path("count").asInt()).isEqualTo(1);
        }
        assertThat(manualConfigured()).isTrue();
        assertThat(method("puig")).isEqualTo("MANUAL");
    }
}

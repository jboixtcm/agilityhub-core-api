package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5-T06 round 2: the configuration and host caches load outside Caffeine's lock (virtual-thread pinning), so a load that
 * overlaps an invalidation must never leave its value behind. Each test holds a cache load on its own thread right after
 * it has read the old state, commits the change (which invalidates), then lets the load finish and checks the cache.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CacheInvalidationRaceIT extends AbstractIntegrationTest {
    static final String CLUB = "race-a";
    static final String HOST = "race-a.example.test";
    static final String NEW_HOST = "race-new.example.test";
    static final String KEY = "bookings.lateCancelThresholdMinutes";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @MockitoSpyBean ClubRepository clubs;
    @MockitoSpyBean ParameterRepository parameters;
    @MockitoSpyBean com.agilityhub.core.clubs.signup.application.SignupPolicy signupPolicy;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signups;
    @Autowired com.agilityhub.core.platform.application.definition.ClubDefinitions definitions;
    @Autowired com.agilityhub.core.platform.application.definition.ClubDefinitionCodec codec;

    /** Holds the read of one loader thread until the test releases it. */
    static final class Gate {
        volatile Thread loader;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Object pass(Object result) throws InterruptedException {
            if (Thread.currentThread() == loader) {
                reached.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }
        <T> CompletableFuture<T> start(Supplier<T> load) throws InterruptedException {
            var ready = new CountDownLatch(1);
            var future = new CompletableFuture<T>();
            var thread = Thread.ofPlatform().unstarted(() -> {
                try { ready.await(); future.complete(load.get()); } catch (Throwable failure) { future.completeExceptionally(failure); }
            });
            loader = thread; thread.start(); ready.countDown();
            assertThat(reached.await(30, TimeUnit.SECONDS)).as("the load reached its read").isTrue();
            return future;
        }
    }

    @BeforeEach void seed() {
        TenantContext.clear();
        for (Class<?> type : List.of(Club.class, Parameter.class)) { mongo.remove(new Query(), type); }
        mongo.remove(new Query(), "class_sessions");
        clubs.save(PlatformFixtures.club(CLUB, HOST));
        configs.invalidate(CLUB); hosts.invalidate();
    }
    @AfterEach void resetSpies() { reset(clubs, parameters, signupPolicy); }

    void admin(String path, Object body) throws Exception {
        mvc.perform(put(path).header("Host", HOST).contentType("application/json").content(mapper.writeValueAsString(body))
                .with(jwt().jwt(j -> j.subject("race-admin").claim("name", "Example Admin").claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test void T_02_09_moduleToggleDuringAnInFlightConfigLoadLeavesNoStaleValue() throws Exception {
        boolean before = configs.get(CLUB).modules().contains(Module.PUSH);
        configs.invalidate(CLUB);
        var gate = new Gate();
        doAnswer(call -> gate.pass(call.callRealMethod())).when(parameters).findAll();
        var load = gate.start(() -> configs.get(CLUB));
        admin("/api/v1/club/modules/PUSH", Map.of("enabled", !before));
        gate.release.countDown();
        assertThat(load.get(30, TimeUnit.SECONDS).modules().contains(Module.PUSH)).as("the in-flight load read the old state").isEqualTo(before);
        assertThat(configs.get(CLUB).modules().contains(Module.PUSH)).as("the cache after the toggle").isEqualTo(!before);
    }

    @Test void T_02_08_parameterChangeDuringAnInFlightConfigLoadLeavesNoStaleValue() throws Exception {
        assertThat(configs.get(CLUB).get(KEY, Integer.class)).isEqualTo(240);
        configs.invalidate(CLUB);
        var gate = new Gate();
        doAnswer(call -> gate.pass(call.callRealMethod())).when(parameters).findAll();
        var load = gate.start(() -> configs.get(CLUB));
        admin("/api/v1/parameters/" + KEY, Map.of("value", 120, "version", 0));
        gate.release.countDown();
        assertThat(load.get(30, TimeUnit.SECONDS).get(KEY, Integer.class)).as("the in-flight load read the old value").isEqualTo(240);
        assertThat(configs.get(CLUB).get(KEY, Integer.class)).as("the cache after the change").isEqualTo(120);
    }

    /**
     * E3-T12 (review #4 of E3-T09, R-04-27): an anonymous `GET /signup` has read `signup.enabled = true` and is building its
     * configuration when `PUT /parameters/signup.enabled` commits and evicts. The load must not store what it read.
     */
    @Test void R_04_27_T_04_23_signupConfigurationLoadPausedAcrossAParameterWriteLeavesNoStaleValue() throws Exception {
        assertThat(signupEnabled()).isTrue();
        signups.invalidateConfiguration(CLUB);
        var gate = new Gate();
        doAnswer(call -> gate.pass(call.callRealMethod())).when(signupPolicy).plans();
        var load = gate.start(() -> { try { return signupEnabled(); } catch (Exception failure) { throw new IllegalStateException(failure); } });
        admin("/api/v1/parameters/signup.enabled", Map.of("value", false, "version", 0));
        gate.release.countDown();
        assertThat(load.get(30, TimeUnit.SECONDS)).as("the in-flight GET read the old value").isTrue();
        assertThat(signupEnabled()).as("the next GET /signup after the write").isFalse();
    }
    boolean signupEnabled() throws Exception {
        var body = mvc.perform(get("/api/v1/signup").header("Host", HOST)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("enabled").asBoolean();
    }

    /**
     * E5-T15 (review E5-T06 #3): the domain change goes through the production path, `club:apply`'s service
     * ({@link com.agilityhub.core.platform.application.definition.ClubDefinitions}), which commits and then invalidates.
     */
    @Test void T_02_06_domainAddedByClubApplyDuringAnInFlightNegativeHostLookupIsResolvedAfterwards() throws Exception {
        var definition = codec.read(java.nio.file.Path.of("seeds/club-minim.yaml")); definition.remove("accounts");
        String slug = "race-apply-" + java.util.UUID.randomUUID().toString().substring(0, 8), host = slug + ".example.test", added = "new-" + host;
        ((com.fasterxml.jackson.databind.node.ObjectNode) definition.get("club")).put("slug", slug).put("status", "ACTIVE");
        ((com.fasterxml.jackson.databind.node.ObjectNode) definition.at("/domains/0")).put("host", host);
        String club = definitions.apply(definition.deepCopy(), false).id();
        var gate = new Gate();
        doAnswer(call -> gate.pass(call.callRealMethod())).when(clubs).findByHost(anyString());
        var load = gate.start(() -> hosts.resolve(added));
        ((com.fasterxml.jackson.databind.node.ArrayNode) definition.get("domains")).addObject().put("host", added).put("app", "clubs").put("primary", false);
        assertThat(definitions.apply(definition, false).changes()).isPositive();
        gate.release.countDown();
        assertThat(load.get(30, TimeUnit.SECONDS)).as("the in-flight lookup read the old domains").isEmpty();
        assertThat(hosts.resolve(added)).isEqualTo(Optional.of(club));
    }

    @Test void T_02_06_domainRemovedDuringAnInFlightHostLookupIsNotResolvedAfterwards() throws Exception {
        var gate = new Gate();
        doAnswer(call -> gate.pass(call.callRealMethod())).when(clubs).findByHost(anyString());
        var load = gate.start(() -> hosts.resolve(HOST));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().pull("domains", Query.query(Criteria.where("host").is(HOST)).getQueryObject()), Club.class);
        hosts.invalidate();
        gate.release.countDown();
        assertThat(load.get(30, TimeUnit.SECONDS)).as("the in-flight lookup read the old domains").isEqualTo(Optional.of(CLUB));
        assertThat(hosts.resolve(HOST)).isEmpty();
    }
}

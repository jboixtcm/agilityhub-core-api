package com.agilityhub.core.platform.api;

import com.agilityhub.core.identity.application.DomainBrokenNotices;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.FakeDomainDns;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.platform.application.jobs.JobStatus;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E10-T03 step 2: S17 R-17-03 «es reverifica setmanalment (S15 P9); si falla 3 cops → BROKEN + avís a plataforma». P9 `cleanup`
 * re-checks the club's verified domains every `platform.domainRecheckDays` (7) over the fake resolver: a lost record counts, the
 * third failure in a row makes the domain `BROKEN` (audited as the system's, `ClubDomainBroken` → N-43 to the active platform
 * admins, Caddy's `ask` and `/branding` refuse it at once) and a good re-check restores it; all of it traced in the run.
 * <p>
 * Round 2 (E125): a lookup without an answer, or a product host without addresses, is `SKIPPED` and never counts; a console
 * write built before a re-check keeps its state; a status change that meets a concurrent write leaves the run PARTIAL and the
 * domain due; `RESTORED` keeps the first `verifiedAt`.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class DomainRecheckIT extends AbstractIntegrationTest {
    static final String TARGET = "recheck-target", OTHER = "recheck-other";
    static final String HOST = "target.example.test", OTHER_HOST = "other.example.test";
    /** The fixture's domains were verified at this instant (`fixtures/platform/club.json`). */
    static final Instant VERIFIED = Instant.parse("2030-01-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    /** A spy only to put a real concurrent club write inside a re-check's transaction (round 2, point 4); it calls through otherwise. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ClubRepository clubs;
    @Autowired HostTenantResolver hosts;
    @Autowired ClubConfigService configs;
    @Autowired FakeDomainDns dns;
    @Autowired JobRunner runner;
    @Autowired OutboxDispatcher outbox;
    @Autowired AccountRepository accounts;
    @Autowired DomainBrokenNotices notices;

    @BeforeEach void clubs() {
        wipeDatabaseKeepingBootstrap();
        dns.clear();
        // The product hosts resolve, as in production (round 2: a product host without addresses skips the re-check).
        dns.address("clubs.agilitydoghub.com", "203.0.113.1"); dns.address("clubsadmin.agilitydoghub.com", "203.0.113.10");
        clubs.save(PlatformFixtures.club(TARGET, HOST));
        clubs.save(PlatformFixtures.club(OTHER, OTHER_HOST));
        hosts.invalidate(); configs.invalidate(TARGET); configs.invalidate(OTHER);
        account("rc-admin-ca", "rc.admin.ca@example.test", "ca", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE);
        account("rc-admin-es", "rc.admin.es@example.test", "es", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.ACTIVE);
        account("rc-admin-blocked", "rc.admin.blocked@example.test", "ca", Set.of(Account.PlatformRole.AGILITYHUB_ADMIN), Account.Status.BLOCKED);
        account("rc-member", "rc.member@example.test", "ca", Set.of(), Account.Status.ACTIVE);
    }
    void account(String id, String email, String locale, Set<Account.PlatformRole> roles, Account.Status status) {
        accounts.save(new Account(id, email, "Fictional " + id, locale, null, roles, status, new Account.Security(0, null, null, 0), Map.of(), false, clock.instant()));
    }
    JobRun run(String club, boolean dryRun, Instant at) {
        clock.setInstant(at);
        var run = runner.manual(club, JobName.CLEANUP, dryRun, "rc-actor");
        assertThat(run.status()).as(run.errors().toString()).isEqualTo(JobStatus.SUCCEEDED);
        return run;
    }
    static Map<String, Long> counters(JobRun run) {
        var map = new TreeMap<String, Long>(); run.counters().forEach(e -> map.put(e.key(), ((Number) e.value()).longValue())); return map;
    }
    static Map<String, Object> detail(JobRun.Item item) {
        var map = new LinkedHashMap<String, Object>(); item.detail().forEach(e -> map.put(e.key(), e.value())); return map;
    }
    static List<JobRun.Item> domainItems(JobRun run) { return run.items().stream().filter(item -> item.entityType().equals("ClubDomain")).toList(); }
    Club.Domain stored(String club, String host) {
        return clubs.findById(club).orElseThrow().domains().stream().filter(d -> d.host().equals(host)).findFirst().orElseThrow();
    }
    List<Document> audits(String club) {
        return mongo.find(Query.query(Criteria.where("clubId").is(club).and("action").is("CLUB_DOMAIN_CHANGED"))
                .with(org.springframework.data.domain.Sort.by("at")), Document.class, "audit_entries");
    }
    List<Document> events(String club, String type) {
        return mongo.find(Query.query(Criteria.where("clubId").is(club).and("type").is(type)), Document.class, "domain_events");
    }
    int allowed(String host) throws Exception { return mvc.perform(get("/internal/domains/allowed").param("host", host)).andReturn().getResponse().getStatus(); }
    int branding(String host) throws Exception { return mvc.perform(get("/api/v1/branding").header("Host", host)).andReturn().getResponse().getStatus(); }
    void lose(String host) { dns.publish(FakeDomainDns.Type.CNAME, host, List.of()); dns.publish(FakeDomainDns.Type.A, host, List.of()); }

    @Test @AuditCovers(AuditAction.CLUB_DOMAIN_CHANGED)
    void T_17_03_R_17_03_p9BreaksAVerifiedDomainOnItsThirdFailedRecheckAndRestoresItWhenItAnswersAgain() throws Exception {
        dns.cname(HOST, "clubs.agilitydoghub.com"); dns.cname(OTHER_HOST, "clubs.agilitydoghub.com");
        // Six days after the verification nothing is due: no domain item, no lookup, the counters at zero and the parameter in the snapshot.
        var early = run(TARGET, false, VERIFIED.plus(Duration.ofDays(6)));
        assertThat(domainItems(early)).isEmpty();
        assertThat(dns.lookups()).isEmpty();
        assertThat(counters(early)).containsEntry("domainsRechecked", 0L).containsEntry("domainsBroken", 0L).containsEntry("domainsRestored", 0L);
        assertThat(early.parametersSnapshot()).contains(new JobRun.Entry("platform.domainRecheckDays", 7));

        // Seven days after: due. The dry run reports the outcome and writes nothing; the pending domain is never re-checked.
        long version = clubs.findById(TARGET).orElseThrow().version();
        var dry = run(TARGET, true, VERIFIED.plus(Duration.ofDays(7)));
        assertThat(domainItems(dry)).singleElement().satisfies(item -> {
            assertThat(item.entityId()).isEqualTo(HOST); assertThat(item.action()).isEqualTo("WOULD_RECHECK_DOMAIN");
            assertThat(detail(item)).containsEntry("outcome", "CONFIRMED").containsEntry("method", "CNAME").containsEntry("passed", true);
        });
        assertThat(counters(dry)).containsEntry("WOULD_RECHECK_DOMAIN", 1L).doesNotContainKey("domainsRechecked");
        assertThat(stored(TARGET, HOST).checkedAt()).isNull();

        // The real run confirms it: the check is recorded on the domain without bumping the club's version, and nothing is audited.
        Instant first = VERIFIED.plus(Duration.ofDays(7));
        var confirmed = run(TARGET, false, first);
        assertThat(domainItems(confirmed)).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("RECHECK_DOMAIN"); assertThat(detail(item)).containsEntry("outcome", "CONFIRMED");
        });
        assertThat(counters(confirmed)).containsEntry("domainsRechecked", 1L).containsEntry("domainsBroken", 0L);
        assertThat(stored(TARGET, HOST).checkedAt()).isEqualTo(first);
        assertThat(stored(TARGET, HOST).failures()).isZero();
        assertThat(clubs.findById(TARGET).orElseThrow().version()).isEqualTo(version);
        assertThat(audits(TARGET)).isEmpty();
        // The next day nothing is due again (the re-check, not the verification, starts the next week).
        assertThat(domainItems(run(TARGET, false, first.plus(Duration.ofDays(1))))).isEmpty();

        // The record is lost: two failures in a row are counted, the domain still serves.
        lose(HOST);
        for (int week = 1; week <= 2; week++) {
            var failed = run(TARGET, false, first.plus(Duration.ofDays(7L * week)));
            assertThat(domainItems(failed)).singleElement().satisfies(item -> assertThat(detail(item)).containsEntry("outcome", "FAILED").containsEntry("passed", false));
            assertThat(stored(TARGET, HOST).status()).isEqualTo(Club.DomainStatus.VERIFIED);
            assertThat(stored(TARGET, HOST).failures()).isEqualTo(week);
        }
        assertThat(allowed(HOST)).isEqualTo(200);
        assertThat(branding(HOST)).isEqualTo(200);
        assertThat(audits(TARGET)).isEmpty();

        // The third failure: BROKEN, with its version bump, ClubUpdated, ClubDomainBroken and a system audit entry.
        Instant third = first.plus(Duration.ofDays(21));
        assertThat(detail(domainItems(run(TARGET, true, third)).getFirst())).containsEntry("outcome", "BROKEN").containsEntry("failedChecks", 3);
        var broken = run(TARGET, false, third);
        assertThat(counters(broken)).containsEntry("domainsRechecked", 1L).containsEntry("domainsBroken", 1L).containsEntry("domainsRestored", 0L);
        var domain = stored(TARGET, HOST);
        assertThat(domain.status()).isEqualTo(Club.DomainStatus.BROKEN);
        assertThat(domain.verifiedAt()).isEqualTo(VERIFIED);
        assertThat(domain.failures()).isEqualTo(3);
        assertThat(domain.primary()).isTrue();
        assertThat(clubs.findById(TARGET).orElseThrow().version()).isEqualTo(version + 1);
        // Caddy's ask and the tenant resolution refuse it right after the commit (the host cache is evicted by ClubUpdated).
        assertThat(allowed(HOST)).isEqualTo(404);
        assertThat(branding(HOST)).isEqualTo(404);
        assertThat(events(TARGET, "ClubDomainBroken")).singleElement().satisfies(event -> {
            assertThat(event.get("payload", Document.class)).isEqualTo(new Document("clubId", TARGET).append("host", HOST));
            assertThat(event.getString("origin")).isEqualTo("SYSTEM"); assertThat(event.get("actorAccountId")).isNull();
        });
        assertThat(events(TARGET, "ClubUpdated")).singleElement().satisfies(event ->
                assertThat(event.get("payload", Document.class).get("diff", Document.class).get("domains", Document.class)).containsEntry("broken", HOST));
        assertThat(audits(TARGET)).singleElement().satisfies(entry -> {
            assertThat(entry.getString("reason")).isEqualTo("Domain broken");
            assertThat(entry.getString("origin")).isEqualTo("SYSTEM"); assertThat(entry.getString("actorRole")).isEqualTo("SYSTEM");
            assertThat(entry.get("actorAccountId")).isNull();
            assertThat(entry.get("details", Document.class)).containsEntry("job", "cleanup");
            assertThat(entry.toJson()).contains("BROKEN");
        });

        // N-43: one e-mail per active platform admin (not the blocked one, not a plain account), in their language, platform-branded.
        outbox.dispatch(); outbox.dispatch();
        var sent = mongo.find(Query.query(Criteria.where("code").is("N-43")), Document.class, "notifications");
        assertThat(sent).extracting(row -> row.get("recipient", Document.class).getString("accountId")).containsExactlyInAnyOrder("rc-admin-ca", "rc-admin-es");
        assertThat(sent).allSatisfy(row -> { assertThat(row.get("clubId")).isNull(); assertThat(row.getString("status")).isIn("SENT", "DELIVERED"); });
        assertThat(sent).extracting(row -> row.getString("locale")).containsExactlyInAnyOrder("ca", "es");
        // A redelivery of the same event sends nothing twice.
        String eventId = events(TARGET, "ClubDomainBroken").getFirst().getString("_id");
        notices.handle(eventId, new DomainBrokenNotices.ForeignEvent(TARGET, Map.of("clubId", TARGET, "host", HOST)));
        assertThat(mongo.count(Query.query(Criteria.where("code").is("N-43")), "notifications")).isEqualTo(2);

        // Still lost a week later: one more failure, no second audit nor event.
        run(TARGET, false, third.plus(Duration.ofDays(7)));
        assertThat(stored(TARGET, HOST).failures()).isEqualTo(4);
        assertThat(audits(TARGET)).hasSize(1);
        assertThat(events(TARGET, "ClubDomainBroken")).hasSize(1);

        // The record is back: RESTORED (VERIFIED again, no failure; verifiedAt keeps the first verification, checkedAt is this run),
        // ClubDomainVerified and its audit entry.
        dns.publish(FakeDomainDns.Type.CNAME, HOST, List.of("clubs.agilitydoghub.com."));
        Instant back = third.plus(Duration.ofDays(14));
        var restored = run(TARGET, false, back);
        assertThat(counters(restored)).containsEntry("domainsRestored", 1L).containsEntry("domainsBroken", 0L);
        domain = stored(TARGET, HOST);
        assertThat(domain.status()).isEqualTo(Club.DomainStatus.VERIFIED);
        assertThat(domain.verifiedAt()).isEqualTo(VERIFIED); assertThat(domain.checkedAt()).isEqualTo(back); assertThat(domain.failures()).isZero();
        assertThat(allowed(HOST)).isEqualTo(200);
        assertThat(branding(HOST)).isEqualTo(200);
        assertThat(events(TARGET, "ClubDomainVerified")).singleElement().satisfies(event -> assertThat(event.getString("origin")).isEqualTo("SYSTEM"));
        assertThat(audits(TARGET)).extracting(entry -> entry.getString("reason")).containsExactly("Domain broken", "Domain restored");

        // The other club's verified domain was never touched by this club's runs.
        assertThat(stored(OTHER, OTHER_HOST).checkedAt()).isNull();
        assertThat(audits(OTHER)).isEmpty();
        // Its own P9 re-checks only its own domain.
        var other = run(OTHER, false, back);
        assertThat(domainItems(other)).extracting(JobRun.Item::entityId).containsExactly(OTHER_HOST);
    }

    @Test void T_17_03_R_17_03_anApexDomainIsRecheckedByItsAddressesAndTheParameterTurnsTheRecheckOff() throws Exception {
        // An apex domain verified by TXT has no CNAME and no challenge left: it passes while it shares an address with its app's host.
        var club = clubs.findById(TARGET).orElseThrow();
        var apex = new Club.Domain("club.example.test", "clubs-admin", Club.DomainStatus.VERIFIED, VERIFIED, true, null);
        clubs.updateDomains(TARGET, club.version(), List.of(club.domains().getFirst(), apex), clock.instant());
        dns.cname(HOST, "clubs.agilitydoghub.com");
        dns.address("club.example.test", "203.0.113.10"); dns.address("clubsadmin.agilitydoghub.com", "203.0.113.10");
        Instant at = VERIFIED.plus(Duration.ofDays(7));
        var run = run(TARGET, false, at);
        assertThat(domainItems(run)).extracting(item -> item.entityId() + " " + detail(item).get("outcome") + " " + detail(item).get("method"))
                .containsExactlyInAnyOrder(HOST + " CONFIRMED CNAME", "club.example.test CONFIRMED ADDRESS");
        // Another address is not the app's: a failure.
        dns.publish(FakeDomainDns.Type.A, "club.example.test", List.of("198.51.100.7"));
        assertThat(domainItems(run(TARGET, false, at.plus(Duration.ofDays(7)))).stream().filter(item -> item.entityId().equals("club.example.test")))
                .singleElement().satisfies(item -> assertThat(detail(item)).containsEntry("outcome", "FAILED").doesNotContainKey("method"));

        // A BROKEN apex domain is re-verified from the console with the same address proof (it has no challenge left).
        club = clubs.findById(TARGET).orElseThrow();
        var brokenApex = new Club.Domain("club.example.test", "clubs-admin", Club.DomainStatus.BROKEN, VERIFIED, true, null, at, 3);
        clubs.updateDomains(TARGET, club.version(), List.of(club.domains().getFirst(), brokenApex), clock.instant(), Set.of("club.example.test"));
        var refused = mvc.perform(post("/api/v1/platform/clubs/" + TARGET + "/domains/club.example.test/verification").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Host", "unknown.example.test").with(jwt().jwt(j -> j.subject("rc-actor")).authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))))
                .andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(mapper.readTree(refused.getContentAsString()).path("details").path("recordName").isNull()).isTrue();
        dns.publish(FakeDomainDns.Type.A, "club.example.test", List.of("203.0.113.10"));
        var verified = mvc.perform(post("/api/v1/platform/clubs/" + TARGET + "/domains/club.example.test/verification").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Host", "unknown.example.test").with(jwt().jwt(j -> j.subject("rc-actor")).authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))))
                .andReturn().getResponse();
        assertThat(verified.getStatus()).as(verified.getContentAsString()).isEqualTo(200);
        assertThat(mapper.readTree(verified.getContentAsString()).path("method").asText()).isEqualTo("ADDRESS");
        assertThat(stored(TARGET, "club.example.test").status()).isEqualTo(Club.DomainStatus.VERIFIED);
        assertThat(stored(TARGET, "club.example.test").failures()).isZero();
        // Round 2 (E125): the console's restore keeps the first verification too.
        assertThat(stored(TARGET, "club.example.test").verifiedAt()).isEqualTo(VERIFIED);

        // `platform.domainRecheckDays = 0` turns the re-check off: no item, no lookup, no counters.
        mongo.insert(new Parameter(UUID.randomUUID().toString(), TARGET, "platform.domainRecheckDays", 0, "unknown", "club", null, List.of(), 0L, clock.instant()));
        configs.invalidate(TARGET);
        dns.clear();
        var off = run(TARGET, false, at.plus(Duration.ofDays(60)));
        assertThat(domainItems(off)).isEmpty();
        assertThat(dns.lookups()).isEmpty();
        assertThat(counters(off)).doesNotContainKey("domainsRechecked");
        assertThat(off.parametersSnapshot()).contains(new JobRun.Entry("platform.domainRecheckDays", 0));
    }

    @Test void T_17_03_R_17_03_aClubApplyKeepsTheRecheckStateAndTheTestDnsRouteValidatesItsRecords() throws Exception {
        dns.cname(HOST, "clubs.agilitydoghub.com");
        run(TARGET, false, VERIFIED.plus(Duration.ofDays(7)));
        lose(HOST);
        run(TARGET, false, VERIFIED.plus(Duration.ofDays(14)));
        var before = stored(TARGET, HOST);
        assertThat(before.failures()).isEqualTo(1);
        // The re-check's state is the process's: rebuilding the club from its own definition keeps it.
        var club = clubs.findById(TARGET).orElseThrow();
        var mapperBean = applicationContext.getBean(com.agilityhub.core.platform.application.definition.ClubDefinitionMapper.class);
        var merged = mapperBean.merge(mapperBean.export(club), club, TARGET, clock.instant(), false);
        assertThat(merged.domains()).contains(before);
        // `POST /api/v1/test/dns` (local/test only) refuses what is not a record set.
        for (String invalid : List.of("{\"name\":\"a.example.test\",\"values\":[]}", "{\"type\":\"A\",\"name\":\"nodot\",\"values\":[]}",
                "{\"type\":\"A\",\"name\":\"a.example.test\"}", "{\"type\":\"A\",\"name\":\"a.example.test\",\"values\":[\" \"]}")) {
            var response = mvc.perform(post("/api/v1/test/dns").contentType("application/json").content(invalid)).andReturn().getResponse();
            assertThat(response.getStatus()).as(invalid).isEqualTo(400);
            assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        var published = mvc.perform(post("/api/v1/test/dns").contentType("application/json")
                .content("{\"type\":\"CNAME\",\"name\":\"" + HOST + "\",\"values\":[\"clubs.agilitydoghub.com\"]}")).andReturn().getResponse();
        assertThat(published.getStatus()).isEqualTo(200);
        assertThat(dns.cnames(HOST)).containsExactly("clubs.agilitydoghub.com");
        // Round 2: `failing: true` (without values) makes the lookups get no answer, until the name is published again.
        var failing = mvc.perform(post("/api/v1/test/dns").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("type", "CNAME", "name", HOST, "failing", true)))).andReturn().getResponse();
        assertThat(failing.getStatus()).isEqualTo(200);
        assertThatThrownBy(() -> dns.cnames(HOST)).isInstanceOf(com.agilityhub.core.platform.application.DomainDns.LookupFailed.class);
        var both = mvc.perform(post("/api/v1/test/dns").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("type", "CNAME", "name", HOST, "failing", true, "values", List.of())))).andReturn().getResponse();
        assertThat(both.getStatus()).isEqualTo(400);
        dns.publish(FakeDomainDns.Type.CNAME, HOST, List.of("clubs.agilitydoghub.com"));
        assertThat(dns.cnames(HOST)).containsExactly("clubs.agilitydoghub.com");
    }

    JobRun partialRun(String club, Instant at) {
        clock.setInstant(at);
        return runner.manual(club, JobName.CLEANUP, false, "rc-actor");
    }
    org.springframework.mock.web.MockHttpServletResponse console(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Idempotency-Key", UUID.randomUUID().toString()).header("Host", "unknown.example.test")
                .with(jwt().jwt(j -> j.subject("rc-actor")).authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")))).andReturn().getResponse();
    }

    @Test void T_17_03_R_17_03_aLookupWithoutAnAnswerIsSkippedAndNeverCountsTowardsBroken() throws Exception {
        dns.cname(HOST, "clubs.agilitydoghub.com");
        Instant first = VERIFIED.plus(Duration.ofDays(7));
        run(TARGET, false, first);
        // An apex admin domain, proved by its address (TXT-verified once, no challenge left), and a pending one.
        var club = clubs.findById(TARGET).orElseThrow();
        var apex = new Club.Domain("club.example.test", "clubs-admin", Club.DomainStatus.VERIFIED, first, true, null, first, 0);
        var pending = new Club.Domain("pending.example.test", "clubs", Club.DomainStatus.PENDING, null, false, "0123456789abcdef");
        clubs.updateDomains(TARGET, club.version(), List.of(club.domains().getFirst(), apex, pending), clock.instant(), Set.of("club.example.test"));
        dns.address("club.example.test", "203.0.113.10"); dns.address("clubsadmin.agilitydoghub.com", "203.0.113.10");
        long version = clubs.findById(TARGET).orElseThrow().version();

        // The resolver gives no answer for the CNAME of one and the address of the other: both SKIPPED{LOOKUP_FAILED}, four weeks
        // in a row, which with real misses would have made them BROKEN. Nothing is counted, nothing is marked checked.
        dns.fail(FakeDomainDns.Type.CNAME, HOST); dns.fail(FakeDomainDns.Type.A, "club.example.test");
        var dry = run(TARGET, true, first.plus(Duration.ofDays(7)));
        assertThat(domainItems(dry)).extracting(item -> item.entityId() + " " + item.action() + " " + detail(item).get("outcome") + " " + detail(item).get("reason"))
                .containsExactlyInAnyOrder(HOST + " WOULD_RECHECK_DOMAIN SKIPPED LOOKUP_FAILED", "club.example.test WOULD_RECHECK_DOMAIN SKIPPED LOOKUP_FAILED");
        for (int week = 1; week <= 4; week++) {
            var skipped = run(TARGET, false, first.plus(Duration.ofDays(7L * week)));
            assertThat(domainItems(skipped)).hasSize(2).allSatisfy(item -> {
                assertThat(item.action()).isEqualTo("RECHECK_DOMAIN");
                assertThat(detail(item)).containsEntry("outcome", "SKIPPED").containsEntry("reason", "LOOKUP_FAILED")
                        .containsEntry("passed", false).containsEntry("failedChecks", 0).doesNotContainKey("method");
            });
            assertThat(counters(skipped)).containsEntry("domainsRechecked", 0L).containsEntry("domainsBroken", 0L);
            for (String host : List.of(HOST, "club.example.test")) {
                assertThat(stored(TARGET, host).status()).as(host).isEqualTo(Club.DomainStatus.VERIFIED);
                assertThat(stored(TARGET, host).failures()).as(host).isZero();
                assertThat(stored(TARGET, host).checkedAt()).as(host + ": still due").isEqualTo(first);
            }
        }
        assertThat(allowed(HOST)).isEqualTo(200);
        assertThat(branding(HOST)).isEqualTo(200);
        assertThat(clubs.findById(TARGET).orElseThrow().version()).isEqualTo(version);
        assertThat(audits(TARGET)).isEmpty();
        assertThat(events(TARGET, "ClubDomainBroken")).isEmpty();

        // The console's verification of the pending domain: a lookup without an answer proves nothing, the same 422 (never a 500).
        dns.fail(FakeDomainDns.Type.CNAME, "pending.example.test"); dns.fail(FakeDomainDns.Type.TXT, "_agilityhub-verify.pending.example.test");
        var refused = console(post("/api/v1/platform/clubs/" + TARGET + "/domains/pending.example.test/verification"));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
        assertThat(mapper.readTree(refused.getContentAsString()).path("code").asText()).isEqualTo("HOST_NOT_VERIFIED");
        assertThat(stored(TARGET, "pending.example.test").status()).isEqualTo(Club.DomainStatus.PENDING);

        // The apex domain's record answers again, but the product host itself resolves to nothing: our DNS, not the club's record.
        dns.publish(FakeDomainDns.Type.A, "club.example.test", List.of("203.0.113.10"));
        dns.publish(FakeDomainDns.Type.A, "clubsadmin.agilitydoghub.com", List.of());
        Instant later = first.plus(Duration.ofDays(35));
        assertThat(domainItems(run(TARGET, false, later)).stream().filter(item -> item.entityId().equals("club.example.test")))
                .singleElement().satisfies(item -> assertThat(detail(item)).containsEntry("outcome", "SKIPPED").containsEntry("reason", "PRODUCT_HOST_UNRESOLVED"));
        assertThat(stored(TARGET, "club.example.test").failures()).isZero();

        // Real misses still count: the CNAME answers without the record three times, and the domain is BROKEN on the third.
        lose(HOST);
        for (int week = 1; week <= 3; week++) {
            int expected = week;
            var missed = run(TARGET, false, later.plus(Duration.ofDays(7L * week)));
            assertThat(domainItems(missed).stream().filter(item -> item.entityId().equals(HOST))).singleElement()
                    .satisfies(item -> assertThat(detail(item)).doesNotContainKey("reason").containsEntry("failedChecks", expected));
        }
        assertThat(stored(TARGET, HOST).status()).isEqualTo(Club.DomainStatus.BROKEN);
        assertThat(allowed(HOST)).isEqualTo(404);
        assertThat(events(TARGET, "ClubDomainBroken")).hasSize(1);
    }

    @Test void T_17_03_R_17_03_aConsoleWriteBuiltBeforeARecheckKeepsTheRecheckState() throws Exception {
        dns.cname(HOST, "clubs.agilitydoghub.com");
        Instant first = VERIFIED.plus(Duration.ofDays(7));
        run(TARGET, false, first);
        // A console write reads the club, then P9 records a failed check (no version bump), then the write lands.
        var stale = clubs.findById(TARGET).orElseThrow();
        lose(HOST);
        Instant second = first.plus(Duration.ofDays(7));
        run(TARGET, false, second);
        assertThat(stored(TARGET, HOST).failures()).isEqualTo(1);
        var added = new Club.Domain("added.example.test", "clubs", Club.DomainStatus.PENDING, null, false, "0123456789abcdef");
        clubs.updateDomains(TARGET, stale.version(), List.of(stale.domains().getFirst(), added), clock.instant());
        assertThat(stored(TARGET, HOST).checkedAt()).isEqualTo(second);
        assertThat(stored(TARGET, HOST).failures()).isEqualTo(1);
        // The next day the domain is not due again: the week runs from the stored check, not from the stale one.
        assertThat(domainItems(run(TARGET, false, second.plus(Duration.ofDays(1))))).isEmpty();
        // A full save (settings, modules, club:apply) built from a stale read keeps it as well.
        var staleAgain = clubs.findById(TARGET).orElseThrow();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(TARGET).and("domains.host").is(HOST)),
                new org.springframework.data.mongodb.core.query.Update().set("domains.$.failedChecks", 2), "clubs");
        clubs.save(staleAgain);
        assertThat(stored(TARGET, HOST).checkedAt()).isEqualTo(second);
        assertThat(stored(TARGET, HOST).failures()).isEqualTo(2);
        // The console's own write over HTTP keeps it too.
        var response = console(post("/api/v1/platform/clubs/" + TARGET + "/domains").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("host", "new.example.test", "app", "clubs"))));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        assertThat(stored(TARGET, HOST).failures()).isEqualTo(2);
        // One more real miss: BROKEN on the third failure in a row, counted across the console writes.
        run(TARGET, false, second.plus(Duration.ofDays(7)));
        assertThat(stored(TARGET, HOST).status()).isEqualTo(Club.DomainStatus.BROKEN);
    }

    @Test void T_17_03_R_17_03_aBreakThatMeetsAConcurrentClubWriteLeavesThePartialRunAndTheDomainDue() throws Exception {
        dns.cname(HOST, "clubs.agilitydoghub.com");
        Instant first = VERIFIED.plus(Duration.ofDays(7));
        run(TARGET, false, first);
        lose(HOST);
        run(TARGET, false, first.plus(Duration.ofDays(7)));
        run(TARGET, false, first.plus(Duration.ofDays(14)));
        assertThat(stored(TARGET, HOST).failures()).isEqualTo(2);
        long version = clubs.findById(TARGET).orElseThrow().version();
        // The third failure's status change: a console write commits on the club from another thread, inside the item's
        // transaction (after the item read the club, before it writes the domains). The real write then meets it.
        var raced = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            if (raced.compareAndSet(false, true)) {
                java.util.concurrent.CompletableFuture.runAsync(() -> mongo.updateFirst(Query.query(Criteria.where("_id").is(TARGET)),
                        new org.springframework.data.mongodb.core.query.Update().inc("version", 1L).set("notes", "a concurrent console write"), "clubs")).join();
            }
            return call.callRealMethod();
        }).when(clubs).updateDomains(eq(TARGET), anyLong(), anyList(), any(), eq(Set.of(HOST)));
        Instant third = first.plus(Duration.ofDays(21));
        var partial = partialRun(TARGET, third);
        assertThat(raced).isTrue();
        assertThat(partial.status()).isEqualTo(JobStatus.PARTIAL);
        assertThat(partial.errors()).singleElement().satisfies(error -> {
            assertThat(error.entityId()).isEqualTo(HOST); assertThat(error.code()).isEqualTo("STALE_VERSION");
        });
        assertThat(counters(partial)).containsEntry("domainsBroken", 0L);
        // Nothing of the item was written: still VERIFIED with two failures and the old check, so it is still due.
        var domain = stored(TARGET, HOST);
        assertThat(domain.status()).isEqualTo(Club.DomainStatus.VERIFIED);
        assertThat(domain.failures()).isEqualTo(2);
        assertThat(domain.checkedAt()).isEqualTo(first.plus(Duration.ofDays(14)));
        assertThat(clubs.findById(TARGET).orElseThrow().version()).isEqualTo(version + 1);
        assertThat(events(TARGET, "ClubDomainBroken")).isEmpty();
        assertThat(audits(TARGET)).isEmpty();
        // The next day's run finds it due and breaks it.
        var retried = run(TARGET, false, third.plus(Duration.ofDays(1)));
        assertThat(domainItems(retried)).singleElement().satisfies(item -> assertThat(detail(item)).containsEntry("outcome", "BROKEN"));
        assertThat(stored(TARGET, HOST).status()).isEqualTo(Club.DomainStatus.BROKEN);
        assertThat(events(TARGET, "ClubDomainBroken")).hasSize(1);
    }

    @Autowired org.springframework.context.ApplicationContext applicationContext;
}

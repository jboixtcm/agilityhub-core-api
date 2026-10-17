package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.domain.DomainChallenge;
import com.agilityhub.core.platform.domain.HostNames;
import com.agilityhub.core.platform.domain.events.ClubDomainBroken;
import com.agilityhub.core.platform.domain.events.ClubDomainVerified;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S17 R-17-02/R-17-03 (E10-T04): the console's club domains. A domain is added `PENDING` with its TXT challenge, verified by
 * a CNAME to the product host of its app or by the TXT record, made the primary one of its app, and removed unless it is that
 * primary one (S17 §5, round 2). Every change bumps the club's version, is audited `CLUB_DOMAIN_CHANGED` and publishes `ClubUpdated`, which
 * evicts the host cache after the commit; a verification publishes `ClubDomainVerified` too.
 */
@Service
public class PlatformDomains {
    public record Change(String id, @AuditField List<Map<String, Object>> domains, Club.Domain domain, Verification verification) { }
    public record Verification(Club.DomainStatus status, Instant checkedAt, String method) { }
    /** What the DNS answered, read before the settlement's transaction (no network call inside a Mongo transaction). */
    public record Check(String host, String method, Instant checkedAt) { }
    /**
     * S15 P9's re-check of one due domain (E10-T03): what the DNS answered, read in the plan outside any transaction, and the
     * outcome it leads to ({@link #CONFIRMED}, {@link #FAILED}, {@link #BROKEN} or {@link #RESTORED}).
     */
    public record Recheck(String host, Club.DomainStatus status, int failedChecks, boolean passed, String method, String outcome) { }
    /** R-17-03: «si falla 3 cops → BROKEN» (a product constant, as S17 states it). */
    public static final int BROKEN_AFTER = 3;
    public static final String CONFIRMED = "CONFIRMED", FAILED = "FAILED", BROKEN = "BROKEN", RESTORED = "RESTORED", SKIPPED = "SKIPPED";
    /** The process named in the system audit entries of the re-check (S14 §3: `SYSTEM` carries `job` in `details`). */
    static final String PROCESS = "cleanup";
    private final ClubRepository clubs; private final PlatformClubChanges changes; private final DomainDns dns;
    private final EventPublisher events; private final AuditActorProvider actors; private final AuditWriter audits; private final Clock clock;
    private final String clubsHost; private final String adminHost;
    public PlatformDomains(ClubRepository clubs, PlatformClubChanges changes, DomainDns dns, EventPublisher events, AuditActorProvider actors,
            AuditWriter audits, Clock clock,
            @Value("${core.platform.product-hosts.clubs}") String clubsHost, @Value("${core.platform.product-hosts.clubs-admin}") String adminHost) {
        this.clubs = clubs; this.changes = changes; this.dns = dns; this.events = events; this.actors = actors; this.audits = audits; this.clock = clock;
        this.clubsHost = clubsHost; this.adminHost = adminHost;
    }

    /** The CNAME target of an app's domains (S17 §2: `clubs.agilitydoghub.com`, or `clubsadmin.…` for `clubs-admin`). */
    public String cnameTarget(String app) { return "clubs-admin".equals(app) ? adminHost : clubsHost; }

    public Change snapshot() {
        var club = changes.club();
        return new Change(club.id(), audit(club.domains()), null, null);
    }

    @Transactional
    @Audited(action = AuditAction.CLUB_DOMAIN_CHANGED, entityType = "'Club'", entity = "#result.id", before = "snapshot()", reason = "'Domain added'")
    public Change add(String host, String app) {
        String normalized = HostNames.normalize(host);
        if (normalized.isEmpty() || !normalized.contains(".")) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "host")); }
        if (DomainChallenge.reserved(normalized)) { throw new ApiException(ErrorCode.HOST_RESERVED); }
        var club = changes.club();
        // A template club is never served (S17 §3): it has no domains, as PATCH already requires.
        if (club.template()) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "template")); }
        if (clubs.findByAnyHost(normalized).isPresent()) { throw new ApiException(ErrorCode.HOST_ALREADY_USED); }
        var added = new Club.Domain(normalized, app, Club.DomainStatus.PENDING, null, false, DomainChallenge.token());
        var domains = new ArrayList<>(club.domains()); domains.add(added);
        var next = write(club, domains, Map.of("added", normalized));
        return new Change(next.id(), audit(next.domains()), added, null);
    }

    @Transactional
    @Audited(action = AuditAction.CLUB_DOMAIN_CHANGED, entityType = "'Club'", entity = "#result.id", before = "snapshot()", reason = "'Domain removed'")
    public Change remove(String host) {
        var club = changes.club(); var domain = domain(club, host);
        // S17 §5 (round 2, E123): a pending, verified or broken domain leaves (clubs change hosts), but never the primary one of its
        // app, which links and e-mails use, until another domain is primary.
        if (domain.primary()) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", domain.status().name(), "primary", true)); }
        var domains = new ArrayList<>(club.domains()); domains.removeIf(item -> item.host().equals(domain.host()));
        var next = write(club, domains, Map.of("removed", domain.host()));
        return new Change(next.id(), audit(next.domains()), null, null);
    }

    @Transactional
    @Audited(action = AuditAction.CLUB_DOMAIN_CHANGED, entityType = "'Club'", entity = "#result.id", before = "snapshot()", reason = "'Primary domain'")
    public Change primary(String host) {
        var club = changes.club(); var domain = domain(club, host);
        // The primary host is the one links and e-mails use (ClubAppUrls): only a verified host can be it.
        if (domain.status() != Club.DomainStatus.VERIFIED) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", domain.status().name())); }
        if (domain.primary()) { return new Change(club.id(), audit(club.domains()), domain, null); }
        var domains = club.domains().stream().map(item -> !item.app().equals(domain.app()) ? item
                : item.withPrimary(item.host().equals(domain.host()))).toList();
        var next = write(club, domains, Map.of("primary", domain.host()));
        return new Change(next.id(), audit(next.domains()), domain(next, host), null);
    }

    /**
     * The DNS half of R-17-03, outside any transaction: (a) a CNAME from the host to the product host of its app, else (b) the
     * TXT challenge. Neither: `422 HOST_NOT_VERIFIED {host, cnameTarget, recordName, recordValue}` (the records it expects; the
     * TXT pair is null without a challenge). A verified domain has nothing to verify: `409 INVALID_STATE`. A `BROKEN` domain was
     * verified once and has no challenge left, so it also passes with the re-check's address proof (E10-T03, an apex domain).
     */
    public Check check(String host) {
        var domain = domain(changes.club(), host);
        if (domain.status() == Club.DomainStatus.VERIFIED) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", domain.status().name())); }
        String target = cnameTarget(domain.app());
        String method = DomainChallenge.cnameMatches(dns.cnames(domain.host()), target) ? "CNAME"
                : domain.verificationToken() != null && DomainChallenge.txtMatches(dns.txt(DomainChallenge.recordName(domain.host())), domain.verificationToken()) ? "TXT"
                : domain.status() == Club.DomainStatus.BROKEN && DomainChallenge.addressesMatch(dns.addresses(domain.host()), dns.addresses(target)) ? "ADDRESS" : null;
        if (method == null) {
            var details = new LinkedHashMap<String, Object>();
            details.put("host", domain.host()); details.put("cnameTarget", target);
            details.put("recordName", domain.verificationToken() == null ? null : DomainChallenge.recordName(domain.host()));
            details.put("recordValue", domain.verificationToken() == null ? null : DomainChallenge.recordValue(domain.verificationToken()));
            throw new ApiException(ErrorCode.HOST_NOT_VERIFIED, details);
        }
        return new Check(domain.host(), method, clock.instant());
    }

    /**
     * The settlement of a passed {@link #check}: `PENDING`/`BROKEN → VERIFIED` (`verifiedAt`, the challenge removed), the primary
     * one of its app when the app has no verified primary yet. A verification that another request settled meanwhile answers
     * the current state; a domain removed meanwhile is `404 NOT_FOUND`.
     */
    @Transactional
    @Audited(action = AuditAction.CLUB_DOMAIN_CHANGED, entityType = "'Club'", entity = "#result.id", before = "snapshot()", reason = "'Domain verified'")
    public Change verify(Check check) {
        var club = changes.club(); var domain = domain(club, check.host());
        if (domain.status() == Club.DomainStatus.VERIFIED) {
            return new Change(club.id(), audit(club.domains()), domain, new Verification(domain.status(), check.checkedAt(), check.method()));
        }
        boolean primary = domain.primary() || club.domains().stream().noneMatch(item -> item.app().equals(domain.app())
                && item.primary() && item.status() == Club.DomainStatus.VERIFIED);
        // A verification resets the periodic re-check (E10-T03): the check just made is its last one, with no failure.
        var verified = new Club.Domain(domain.host(), domain.app(), Club.DomainStatus.VERIFIED, check.checkedAt(), primary, null, check.checkedAt(), 0);
        var domains = club.domains().stream().map(item -> item.host().equals(domain.host()) ? verified
                : primary && item.app().equals(domain.app()) && item.primary() ? item.withPrimary(false) : item).toList();
        var next = write(club, domains, Map.of("verified", domain.host(), "method", check.method()));
        events.publish(new ClubDomainVerified(club.id(), domain.host(), clock.instant(), actors.current().accountId()));
        return new Change(next.id(), audit(next.domains()), verified, new Verification(Club.DomainStatus.VERIFIED, check.checkedAt(), check.method()));
    }

    /**
     * S15 P9's plan (E10-T03; S17 R-17-03 «es reverifica setmanalment»): the open club's `VERIFIED` and `BROKEN` domains whose
     * last re-check (or their verification) is `days` or more before `now`, each with the DNS answer, read here outside any
     * transaction. A domain still reaches its app when its CNAME is the app's product host or, for an apex domain, when it
     * shares one of that host's addresses. `days = 0` turns the re-check off (`platform.domainRecheckDays`).
     */
    public List<Recheck> recheckPlan(Instant now, int days) {
        if (days <= 0) { return List.of(); }
        Instant cutoff = now.minus(java.time.Duration.ofDays(days));
        return changes.club().domains().stream()
                .filter(item -> item.status() == Club.DomainStatus.VERIFIED || item.status() == Club.DomainStatus.BROKEN)
                .filter(item -> { Instant last = item.checkedAt() != null ? item.checkedAt() : item.verifiedAt(); return last == null || !last.isAfter(cutoff); })
                .map(item -> {
                    String target = cnameTarget(item.app());
                    String method = DomainChallenge.cnameMatches(dns.cnames(item.host()), target) ? "CNAME"
                            : DomainChallenge.addressesMatch(dns.addresses(item.host()), dns.addresses(target)) ? "ADDRESS" : null;
                    return new Recheck(item.host(), item.status(), item.failures(), method != null, method, outcome(item, method != null));
                }).toList();
    }

    /**
     * S15 P9's item (E10-T03), inside the run's transaction: a good re-check clears the failures (`CONFIRMED`) or brings a
     * `BROKEN` domain back (`RESTORED`: `VERIFIED`, `verifiedAt`, `ClubDomainVerified`); a failed one counts (`FAILED`), and the
     * third in a row makes a `VERIFIED` domain `BROKEN` (`ClubDomainBroken` → N-43). A status change bumps the club's version,
     * publishes `ClubUpdated` (the host cache is evicted after the commit, so Caddy's `ask` and `/branding` follow at once) and
     * is audited `CLUB_DOMAIN_CHANGED` as the system's, with the process in `details.job`. A domain removed or pending meanwhile is
     * `SKIPPED`. Answers the outcome applied.
     */
    @Transactional
    public String recheck(String host, boolean passed, String method, Instant at) {
        var club = changes.club();
        var current = club.domains().stream().filter(item -> item.host().equals(HostNames.normalize(host))).findFirst().orElse(null);
        if (current == null || current.status() == Club.DomainStatus.PENDING) { return SKIPPED; }
        String outcome = outcome(current, passed);
        int failures = passed ? 0 : current.failures() + 1;
        if (outcome.equals(CONFIRMED) || outcome.equals(FAILED)) {
            clubs.recordDomainCheck(club.id(), current.host(), at, failures);
            return outcome;
        }
        boolean broken = outcome.equals(BROKEN);
        var changed = new Club.Domain(current.host(), current.app(), broken ? Club.DomainStatus.BROKEN : Club.DomainStatus.VERIFIED,
                broken ? current.verifiedAt() : at, current.primary(), null, at, failures);
        var domains = club.domains().stream().map(item -> item.host().equals(current.host()) ? changed : item).toList();
        var next = clubs.updateDomains(club.id(), club.version(), domains, clock.instant());
        var diff = new LinkedHashMap<String, Object>(); diff.put(broken ? "broken" : "restored", current.host());
        if (method != null) { diff.put("method", method); }
        events.publish(new com.agilityhub.core.platform.domain.events.ConsoleEvent("ClubUpdated", club.id(), clock.instant(),
                Map.of("clubId", club.id(), "diff", Map.of("domains", diff)), null, null, com.agilityhub.core.shared.domain.DomainEvent.Origin.SYSTEM));
        events.publish(broken ? new ClubDomainBroken(club.id(), current.host(), clock.instant()) : ClubDomainVerified.restored(club.id(), current.host(), clock.instant()));
        audits.writeAsSystem(new AuditCommand(AuditAction.CLUB_DOMAIN_CHANGED, "Club", club.id(), null, new Change(club.id(), audit(club.domains()), null, null),
                new Change(next.id(), audit(next.domains()), null, null), broken ? "Domain broken" : "Domain restored"), PROCESS);
        return outcome;
    }
    static String outcome(Club.Domain domain, boolean passed) {
        if (passed) { return domain.status() == Club.DomainStatus.BROKEN ? RESTORED : CONFIRMED; }
        return domain.status() == Club.DomainStatus.VERIFIED && domain.failures() + 1 >= BROKEN_AFTER ? BROKEN : FAILED;
    }

    /** S17 WP-17-D, Caddy's `ask`: only a verified club domain gets a certificate. */
    public boolean allowed(String host) {
        String normalized = HostNames.normalize(host);
        return !normalized.isEmpty() && clubs.findByHost(normalized).isPresent();
    }

    private Club write(Club club, List<Club.Domain> domains, Map<String, Object> diff) {
        var next = clubs.updateDomains(club.id(), club.version(), domains, clock.instant());
        changes.publish("ClubUpdated", club.id(), Map.of("domains", diff));
        return next;
    }
    private static Club.Domain domain(Club club, String host) {
        String normalized = HostNames.normalize(host);
        return club.domains().stream().filter(item -> item.host().equals(normalized)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    /** The audited view: host, app, status and primary; never the challenge. */
    private static List<Map<String, Object>> audit(List<Club.Domain> domains) {
        return domains.stream().map(item -> Map.<String, Object>of("host", item.host(), "app", item.app(), "status", item.status().name(), "primary", item.primary())).toList();
    }
}

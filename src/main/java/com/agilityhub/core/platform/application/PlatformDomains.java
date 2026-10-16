package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.domain.DomainChallenge;
import com.agilityhub.core.platform.domain.HostNames;
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
 * a CNAME to the product host of its app or by the TXT record, made the primary one of its app, and removed only while
 * `PENDING` (S17 §5). Every change bumps the club's version, is audited `CLUB_DOMAIN_CHANGED` and publishes `ClubUpdated`, which
 * evicts the host cache after the commit; a verification publishes `ClubDomainVerified` too.
 */
@Service
public class PlatformDomains {
    public record Change(String id, @AuditField List<Map<String, Object>> domains, Club.Domain domain, Verification verification) { }
    public record Verification(Club.DomainStatus status, Instant checkedAt, String method) { }
    /** What the DNS answered, read before the settlement's transaction (no network call inside a Mongo transaction). */
    public record Check(String host, String method, Instant checkedAt) { }
    private final ClubRepository clubs; private final PlatformClubChanges changes; private final DomainDns dns;
    private final EventPublisher events; private final AuditActorProvider actors; private final Clock clock;
    private final String clubsHost; private final String adminHost;
    public PlatformDomains(ClubRepository clubs, PlatformClubChanges changes, DomainDns dns, EventPublisher events, AuditActorProvider actors, Clock clock,
            @Value("${core.platform.product-hosts.clubs}") String clubsHost, @Value("${core.platform.product-hosts.clubs-admin}") String adminHost) {
        this.clubs = clubs; this.changes = changes; this.dns = dns; this.events = events; this.actors = actors; this.clock = clock;
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
        // S17 §5: only a pending domain leaves (`PENDING --> [*]`); a verified one keeps serving its club.
        if (domain.status() != Club.DomainStatus.PENDING) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", domain.status().name())); }
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
                : new Club.Domain(item.host(), item.app(), item.status(), item.verifiedAt(), item.host().equals(domain.host()), item.verificationToken())).toList();
        var next = write(club, domains, Map.of("primary", domain.host()));
        return new Change(next.id(), audit(next.domains()), domain(next, host), null);
    }

    /**
     * The DNS half of R-17-03, outside any transaction: (a) a CNAME from the host to the product host of its app, else (b) the
     * TXT challenge. Neither: `422 HOST_NOT_VERIFIED {host, cnameTarget, recordName, recordValue}` (the records it expects; the
     * TXT pair is null without a challenge). A verified domain has nothing to verify: `409 INVALID_STATE`.
     */
    public Check check(String host) {
        var domain = domain(changes.club(), host);
        if (domain.status() == Club.DomainStatus.VERIFIED) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", domain.status().name())); }
        String target = cnameTarget(domain.app());
        String method = DomainChallenge.cnameMatches(dns.cnames(domain.host()), target) ? "CNAME"
                : domain.verificationToken() != null && DomainChallenge.txtMatches(dns.txt(DomainChallenge.recordName(domain.host())), domain.verificationToken()) ? "TXT" : null;
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
        var verified = new Club.Domain(domain.host(), domain.app(), Club.DomainStatus.VERIFIED, check.checkedAt(), primary, null);
        var domains = club.domains().stream().map(item -> item.host().equals(domain.host()) ? verified
                : primary && item.app().equals(domain.app()) && item.primary()
                        ? new Club.Domain(item.host(), item.app(), item.status(), item.verifiedAt(), false, item.verificationToken()) : item).toList();
        var next = write(club, domains, Map.of("verified", domain.host(), "method", check.method()));
        events.publish(new ClubDomainVerified(club.id(), domain.host(), clock.instant(), actors.current().accountId()));
        return new Change(next.id(), audit(next.domains()), verified, new Verification(Club.DomainStatus.VERIFIED, check.checkedAt(), check.method()));
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

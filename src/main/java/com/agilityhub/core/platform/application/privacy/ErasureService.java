package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ConsoleTransactions;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.platform.domain.events.PrivacyEvent;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequest;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * S14 R-14-14: an erasure request and its cancellation. The checks run first and nothing is created when one fails (`422
 * ERASURE_BLOCKED {reasons}`); a live request is unique per member (`409 ERASURE_ALREADY_REQUESTED`, also under a race through
 * the unique partial index, T-14-24). Each request publishes `AccountErasureRequested` (S01 revokes sessions, S11 sends N-52 unless
 * `source = RETENTION`) and audits `ERASURE_REQUESTED`, in one transaction. PRIV-04 (owner's decision of 13-11-2026): a reason that
 * waits (`SEPA_REFUND_WINDOW`) refuses nothing: the request is created `BLOCKED` with it, `executeAt` no earlier than the day the
 * refund window closes, and the daily run executes it from then on (R-14-15 checks again).
 */
@Service
public class ErasureService {
    public static final String JOB = "erasures";
    private final ErasureRequestRepository requests;
    private final ErasureDirectory directory;
    private final ErasureAccounts accounts;
    private final ErasureEligibility eligibility;
    private final ClubConfigService configs;
    private final EventPublisher events;
    private final AuditWriter audit;
    private final ConsoleTransactions transactions;
    private final ErasureExecutor executor;
    private final ErasureFilesCleanup cleanup;
    private final Clock clock;

    public ErasureService(ErasureRequestRepository requests, ErasureDirectory directory, ErasureAccounts accounts, ErasureEligibility eligibility,
            ClubConfigService configs, EventPublisher events, AuditWriter audit, ConsoleTransactions transactions, ErasureExecutor executor,
            ErasureFilesCleanup cleanup, Clock clock) {
        this.requests = requests; this.directory = directory; this.accounts = accounts; this.eligibility = eligibility;
        this.configs = configs; this.events = events; this.audit = audit; this.transactions = transactions; this.executor = executor;
        this.cleanup = cleanup; this.clock = clock;
    }

    /** `POST /members/{id}/erasure` (ADMIN): `scope = MEMBER`, `source = ADMIN`. */
    public ErasureRequest requestMember(String memberId, String reason) {
        return transactions.write(() -> {
            var member = directory.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (member.erased()) { throw new ApiException(ErrorCode.MEMBER_ERASED); }
            if (requests.live(memberId).isPresent()) { throw new ApiException(ErrorCode.ERASURE_ALREADY_REQUESTED); }
            Instant now = clock.instant();
            var reasons = eligibility.check(List.of(member), now);
            blocked(reasons);
            var request = schedule(member, "ADMIN", reason, CurrentUser.current() == null ? null : CurrentUser.current().accountId(), now,
                    reasons, eligibility.waitsUntil(List.of(member), now).orElse(null));
            audit.write(new AuditCommand(AuditAction.ERASURE_REQUESTED, "ErasureRequest", request.id(), memberId, null, null, reason));
            return request;
        });
    }

    /**
     * R-14-16 (a)/(b): the retention sweep's request, `executeAt = now` (no margin), audited as the system's (`details.job`). Not
     * checked here: the executor checks it at once and a blocked one stays `BLOCKED`, visible to the console.
     */
    public ErasureRequest requestRetention(ErasureMember member, String process) {
        return transactions.write(() -> {
            var request = schedule(member, "RETENTION", null, null, clock.instant(), Set.of(), null);
            audit.writeAsSystem(new AuditCommand(AuditAction.ERASURE_REQUESTED, "ErasureRequest", request.id(), member.id(), null, null, null), process);
            return request;
        });
    }

    /** {@code reasons} the checks gave (only waiting ones are left here); {@code waitsUntil} when they stop holding, or null. */
    private ErasureRequest schedule(ErasureMember member, String source, String reason, String by, Instant now, Set<ErasureBlockReason> reasons,
            Instant waitsUntil) {
        String clubId = TenantContext.require();
        var config = configs.get(clubId);
        ZoneId zone = ZoneId.of(config.club().timeZone());
        int margin = config.get("rgpd.erasureMinDaysAfterLeave", Integer.class);
        Instant executeAt = later(ErasurePolicy.executeAt(now, member, zone, margin, "RETENTION".equals(source)), waitsUntil);
        var waiting = waiting(reasons);
        var request = requests.insert(new ErasureRequest(UUID.randomUUID().toString(), clubId, "MEMBER", member.accountId(), List.of(member.id()),
                List.of(clubId), source, by, now, reason, status(waiting), executeAt, waiting, null, null, null, List.of(), List.of(), false, true, null, 0));
        events.publish(requested(request, clubId, by, now));
        return request;
    }

    /**
     * `POST /accounts/{id}/erasure` (AGILITYHUB_ADMIN, no tenant): `scope = ACCOUNT`, every membership. The checks run in each club
     * of the account's members; `executeAt` is the latest of their margins. An account without any member is executed at once
     * (nothing waits for a margin and no club's daily run would see it): the request answers `ERASING`.
     */
    public ErasureRequest requestAccount(String accountId, String reason) {
        var request = transactions.write(() -> {
            var account = accounts.account(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (account.erased() || requests.liveForAccount(accountId).isPresent()) { throw new ApiException(ErrorCode.ERASURE_ALREADY_REQUESTED); }
            Instant now = clock.instant();
            var reasons = EnumSet.noneOf(ErasureBlockReason.class);
            var memberIds = new ArrayList<String>(); var clubIds = new LinkedHashSet<String>();
            Instant executeAt = now;
            for (String clubId : accounts.memberships(accountId).stream().map(ErasureAccounts.Membership::clubId).distinct().toList()) {
                try (var tenant = TenantContext.open(clubId)) {
                    var members = directory.ofAccount(accountId).stream().filter(member -> !member.erased()).toList();
                    if (members.isEmpty()) { continue; }
                    reasons.addAll(eligibility.check(members, now));
                    executeAt = later(executeAt, eligibility.waitsUntil(members, now).orElse(null));
                    var config = configs.get(clubId);
                    for (var member : members) {
                        memberIds.add(member.id()); clubIds.add(clubId);
                        Instant at = ErasurePolicy.executeAt(now, member, ZoneId.of(config.club().timeZone()), config.get("rgpd.erasureMinDaysAfterLeave", Integer.class), false);
                        if (at.isAfter(executeAt)) { executeAt = at; }
                    }
                }
            }
            if (eligibility.otherMembershipsActive(accountId)) { reasons.add(ErasureBlockReason.OTHER_MEMBERSHIPS_ACTIVE); }
            blocked(reasons);
            for (String memberId : memberIds) { if (requests.live(memberId).isPresent()) { throw new ApiException(ErrorCode.ERASURE_ALREADY_REQUESTED); } }
            String by = CurrentUser.current() == null ? null : CurrentUser.current().accountId();
            var waiting = waiting(reasons);
            var created = requests.insert(new ErasureRequest(UUID.randomUUID().toString(), null, "ACCOUNT", accountId, memberIds, List.copyOf(clubIds),
                    "PLATFORM", by, now, reason, status(waiting), executeAt, waiting, null, null, null, List.of(), List.of(), false, true, null, 0));
            events.publish(requested(created, null, by, now));
            audit.write(new AuditCommand(AuditAction.ERASURE_REQUESTED, "ErasureRequest", created.id(), null, null, null, reason));
            return created;
        });
        return request.clubIds().isEmpty() ? executor.executeAccountOnly(request.id()) : request;
    }

    /** `GET /members/{id}/erasure`: the member's live request, read in its club (another club's member → `404`). */
    public ErasureRequest live(String memberId) {
        directory.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return requests.live(memberId).filter(request -> request.clubIds().contains(TenantContext.require()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * `DELETE /members/{id}/erasure`: `SCHEDULED`/`BLOCKED` → `CANCELLED` (`ERASURE_CANCELLED`); `ERASING` and an account's request
     * (the console's) → `409 INVALID_STATE`.
     */
    public void cancel(String memberId) {
        transactions.write(() -> {
            var request = live(memberId);
            if (!Set.of("SCHEDULED", "BLOCKED").contains(request.status()) || !"MEMBER".equals(request.scope())) {
                throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", request.status(), "scope", request.scope()));
            }
            close(request);
            audit.write(new AuditCommand(AuditAction.ERASURE_CANCELLED, "ErasureRequest", request.id(), memberId, null, null, null));
            return null;
        });
    }

    /**
     * `DELETE /platform/erasure-requests/{id}` (AGILITYHUB_ADMIN, no tenant; S14 §5 «plataforma», E11-T01 round 2): the console's
     * `ACCOUNT` request in `SCHEDULED` or `BLOCKED` → `CANCELLED`, audited `ERASURE_CANCELLED` on the platform, as a club cancels a
     * member's. A request blocked halfway keeps what it already did: the clubs of `erasedClubIds` stay erased (nothing is restored).
     * Another state, or a club's `MEMBER` request (its club cancels it), → `409 INVALID_STATE`; an unknown id → `404`.
     */
    public void cancelAccountRequest(String requestId) {
        transactions.write(() -> {
            var request = requests.findById(requestId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!Set.of("SCHEDULED", "BLOCKED").contains(request.status()) || !"ACCOUNT".equals(request.scope())) {
                throw new ApiException(ErrorCode.INVALID_STATE, Map.of("status", request.status(), "scope", request.scope()));
            }
            close(request);
            audit.write(new AuditCommand(AuditAction.ERASURE_CANCELLED, "ErasureRequest", request.id(), null, null, null, null));
            if (!request.filesPending().isEmpty()) { cleanup.cleanAfterCommit(request.id()); }
            return null;
        });
    }

    private void close(ErasureRequest request) {
        String by = CurrentUser.current() == null ? null : CurrentUser.current().accountId();
        var update = new Update().set("status", "CANCELLED").set("cancelledAt", clock.instant()).unset("live");
        if (by != null) { update.set("cancelledByAccountId", by); }
        if (!requests.update(request, update)) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }

    /** R-14-14: the reasons that refuse a request (`422 ERASURE_BLOCKED`, only those listed); a waiting one (PRIV-04) does not. */
    private static void blocked(Set<ErasureBlockReason> reasons) {
        var refusing = reasons.stream().filter(blockReason -> !blockReason.waits()).map(Enum::name).sorted().toList();
        if (!refusing.isEmpty()) { throw new ApiException(ErrorCode.ERASURE_BLOCKED, Map.of("reasons", refusing)); }
    }
    /** PRIV-04: the waiting reasons a new request carries as its `blockReasons`. */
    private static List<String> waiting(Set<ErasureBlockReason> reasons) {
        return reasons.stream().filter(ErasureBlockReason::waits).map(Enum::name).sorted().toList();
    }
    /** PRIV-04: a request with a waiting reason is created `BLOCKED` (visible on D10 and the console), otherwise `SCHEDULED`. */
    private static String status(List<String> waiting) { return waiting.isEmpty() ? "SCHEDULED" : "BLOCKED"; }
    private static Instant later(Instant executeAt, Instant waitsUntil) { return waitsUntil != null && waitsUntil.isAfter(executeAt) ? waitsUntil : executeAt; }

    static PrivacyEvent requested(ErasureRequest request, String clubId, String by, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", request.id()); payload.put("scope", request.scope());
        if (request.accountId() != null) { payload.put("accountId", request.accountId()); }
        payload.put("memberIds", request.memberIds()); payload.put("clubIds", request.clubIds());
        payload.put("executeAt", request.executeAt().toString()); payload.put("source", request.source());
        return new PrivacyEvent(PrivacyEvent.Kind.AccountErasureRequested, clubId, request.id(), now, payload, by, null,
                by == null ? DomainEvent.Origin.SYSTEM : DomainEvent.Origin.BACKOFFICE);
    }
}

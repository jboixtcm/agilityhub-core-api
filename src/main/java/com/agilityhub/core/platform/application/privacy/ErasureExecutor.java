package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ConsoleTransactions;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.platform.domain.events.PrivacyEvent;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequest;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * S14 R-14-15 `ErasureExecutor`: S15's daily `erasures` run (and the retention sweep) execute a due request one club at a time,
 * each in one Mongo transaction. The R-14-14 checks run again first: a failure moves the request to `BLOCKED{blockReasons}` and
 * the next daily run retries it. Otherwise every context's {@link ErasureStep} pseudonymises its documents of the club's members,
 * `MEMBER_ERASED` is audited per member, and the club joins `erasedClubIds`. The club that completes the request also erases the
 * account when it goes ({@link AccountErasureStep}, `ACCOUNT_ERASED`), moves it to `ERASING` and publishes `AccountErased`, whose
 * consumer ({@link ErasureFilesCleanup}) deletes `filesPending[]` and closes it at `ERASED`. Idempotent: an erased member
 * (`erasedAt`) is not touched again, and each transition is a compare-and-set on `version` (T-14-24: two runs at once execute once).
 */
@Service
public class ErasureExecutor {
    public enum Outcome { ERASED, PARTIAL, BLOCKED, SKIPPED }
    private final ErasureRequestRepository requests;
    private final ErasureDirectory directory;
    private final ErasureAccounts accounts;
    private final ErasureEligibility eligibility;
    private final List<ErasureStep> steps;
    private final List<AccountErasureStep> accountSteps;
    private final ErasurePseudonyms pseudonyms;
    private final ClubConfigService configs;
    private final IcuMessageSource messages;
    private final EventPublisher events;
    private final AuditWriter audit;
    private final ConsoleTransactions transactions;
    private final Clock clock;

    public ErasureExecutor(ErasureRequestRepository requests, ErasureDirectory directory, ErasureAccounts accounts, ErasureEligibility eligibility,
            List<ErasureStep> steps, List<AccountErasureStep> accountSteps, ErasurePseudonyms pseudonyms, ClubConfigService configs,
            IcuMessageSource messages, EventPublisher events, AuditWriter audit, ConsoleTransactions transactions, Clock clock) {
        this.requests = requests; this.directory = directory; this.accounts = accounts; this.eligibility = eligibility;
        this.steps = List.copyOf(steps); this.accountSteps = List.copyOf(accountSteps); this.pseudonyms = pseudonyms; this.configs = configs;
        this.messages = messages; this.events = events; this.audit = audit; this.transactions = transactions; this.clock = clock;
    }

    /** One request's part in the current club, in its own transaction (or the caller's: a job item's). */
    public Outcome execute(String requestId) {
        return transactions.write(() -> {
            String clubId = TenantContext.require();
            var request = requests.findById(requestId).orElse(null);
            if (request == null || !List.of("SCHEDULED", "BLOCKED").contains(request.status())
                    || !request.clubIds().contains(clubId) || request.erasedClubIds().contains(clubId)) { return Outcome.SKIPPED; }
            Instant now = clock.instant();
            var members = new ArrayList<ErasureMember>();
            request.memberIds().forEach(id -> directory.member(id).ifPresent(members::add));
            var reasons = eligibility.check(members, now);
            if ("ACCOUNT".equals(request.scope()) && eligibility.otherMembershipsActive(request.accountId())) {
                reasons.add(ErasureBlockReason.OTHER_MEMBERSHIPS_ACTIVE);
            }
            if (!reasons.isEmpty()) {
                compareAndSet(request, new Update().set("status", "BLOCKED").set("blockReasons", reasons.stream().map(Enum::name).sorted().toList())
                        .set("lastAttemptAt", now));
                return Outcome.BLOCKED;
            }
            var remaining = new HashSet<>(request.clubIds()); remaining.removeAll(request.erasedClubIds()); remaining.remove(clubId);
            boolean completes = remaining.isEmpty();
            var placeholders = placeholders(clubId);
            var erasedAccounts = new LinkedHashSet<String>();
            if (completes && accountGoes(request, clubId)) { erasedAccounts.add(request.accountId()); }
            var subject = new ErasureSubject(request.id(), clubId, members, erasedAccounts, pseudonymsOf(members, request.accountId()), placeholders, now);
            var effect = ErasureEffect.NONE;
            for (var step : steps) { effect = effect.plus(step.erase(subject)); }
            for (var member : members) {
                if (member.erased()) { continue; }
                audit.writeAsSystem(new AuditCommand(AuditAction.MEMBER_ERASED, "Member", member.id(), member.id(), null, null, null), ErasureService.JOB);
            }
            if (completes && !erasedAccounts.isEmpty()) { effect = effect.plus(eraseAccount(request.accountId(), placeholders, now)); }
            var update = new Update().addToSet("erasedClubIds", clubId).set("blockReasons", List.of()).set("lastAttemptAt", now);
            if (!effect.files().isEmpty()) { update.push("filesPending").each(effect.files().toArray()); }
            if (completes) { update.set("status", "ERASING").set("accountErased", !erasedAccounts.isEmpty()); }
            else { update.set("status", "SCHEDULED"); }
            compareAndSet(request, update);
            if (completes) { events.publish(erased(request, clubId, !erasedAccounts.isEmpty(), now)); }
            return completes ? Outcome.ERASED : Outcome.PARTIAL;
        });
    }

    /** An account request without any member (no club to run in): the account part at once, outside any club. */
    public ErasureRequest executeAccountOnly(String requestId) {
        return transactions.write(() -> {
            var request = requests.findById(requestId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!"SCHEDULED".equals(request.status()) || !request.clubIds().isEmpty()) { return request; }
            Instant now = clock.instant();
            var effect = eraseAccount(request.accountId(), placeholders(null), now);
            var update = new Update().set("status", "ERASING").set("accountErased", true).set("lastAttemptAt", now);
            if (!effect.files().isEmpty()) { update.push("filesPending").each(effect.files().toArray()); }
            compareAndSet(request, update);
            events.publish(erased(request, null, true, now));
            return requests.findById(requestId).orElseThrow();
        });
    }

    private ErasureEffect eraseAccount(String accountId, ErasurePlaceholders placeholders, Instant now) {
        var account = accounts.account(accountId).orElse(null);
        if (account == null || account.erased()) { return ErasureEffect.NONE; }
        String pseudonym = pseudonyms.pseudonym(account.email());
        var effect = ErasureEffect.NONE;
        for (var step : accountSteps) { effect = effect.plus(step.eraseAccount(accountId, pseudonym, placeholders, now)); }
        audit.writeAsSystem(new AuditCommand(AuditAction.ACCOUNT_ERASED, "Account", accountId, null, null, null, null), ErasureService.JOB);
        return effect;
    }

    /**
     * R-14-15 exceptions: `scope = ACCOUNT` always erases the account; `scope = MEMBER` keeps it (only its membership goes) when it
     * is linked to Learn or still has a membership in another club that is not erased.
     */
    private boolean accountGoes(ErasureRequest request, String clubId) {
        if (request.accountId() == null) { return false; }
        if ("ACCOUNT".equals(request.scope())) { return true; }
        var account = accounts.account(request.accountId()).orElse(null);
        if (account == null || account.erased() || account.federated()) { return false; }
        return accounts.memberships(request.accountId()).stream().noneMatch(m -> !m.clubId().equals(clubId) && !"ERASED".equals(m.status()));
    }

    private Map<String, String> pseudonymsOf(List<ErasureMember> members, String requestAccount) {
        var ids = new LinkedHashSet<String>();
        if (requestAccount != null) { ids.add(requestAccount); }
        members.stream().map(ErasureMember::accountId).filter(java.util.Objects::nonNull).forEach(ids::add);
        var result = new LinkedHashMap<String, String>();
        for (String id : ids) { accounts.account(id).map(ErasureAccounts.Account::email).map(pseudonyms::pseudonym).ifPresent(value -> result.put(id, value)); }
        return result;
    }

    /** S14 §10: the placeholders are stored in the club's default locale (the platform's, `ca`, outside a club). */
    ErasurePlaceholders placeholders(String clubId) {
        Locale locale = Locale.forLanguageTag(Optional.ofNullable(clubId).map(id -> configs.get(id).club().defaultLocale()).orElse("ca"));
        return new ErasurePlaceholders(text("erasure.placeholder.member", locale), text("erasure.placeholder.account", locale),
                text("erasure.placeholder.dog", locale), text("erasure.placeholder.task", locale));
    }
    private String text(String key, Locale locale) { return messages.getMessage(key, null, locale); }

    private void compareAndSet(ErasureRequest request, Update update) {
        if (!requests.update(request, update)) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }

    static PrivacyEvent erased(ErasureRequest request, String clubId, boolean accountErased, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", request.id()); payload.put("scope", request.scope());
        if (accountErased) { payload.put("accountId", request.accountId()); }
        payload.put("memberIds", request.memberIds()); payload.put("clubIds", request.clubIds());
        return new PrivacyEvent(PrivacyEvent.Kind.AccountErased, clubId, request.id(), now, payload, null, null, null);
    }
}

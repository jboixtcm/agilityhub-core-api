package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.shared.application.MemberOnboardingAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingService {
    private final IdentityService identities;
    private final AccountService profiles;
    private final AccountRepository accounts;
    private final MemberOnboardingAccess members;
    private final ClubConfigService clubs;
    private final ParameterCatalog catalog;
    private final AuthSettings settings;
    private final Environment environment;
    private final Clock clock;

    public OnboardingService(IdentityService identities, AccountService profiles, AccountRepository accounts,
            MemberOnboardingAccess members, ClubConfigService clubs, ParameterCatalog catalog,
            AuthSettings settings, Environment environment, Clock clock) {
        this.identities = identities; this.profiles = profiles; this.accounts = accounts; this.members = members;
        this.clubs = clubs; this.catalog = catalog; this.settings = settings; this.environment = environment; this.clock = clock;
    }

    public State state(String accountId) {
        var session = identities.current(accountId);
        return state(session, policies());
    }

    @Transactional
    @Audited(action = AuditAction.ONBOARDING_COMPLETED, entityType = "'Account'", entity = "#accountId")
    public State complete(String accountId, boolean consentAccepted, String consentVersion,
                          String name, String locale, String phone, Boolean imageConsent) {
        var session = identities.current(accountId);
        var policies = policies();
        var required = required(session.account(), policies);
        if (!consentAccepted || consentVersion == null || consentVersion.isBlank()) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if (required == null ? policies.stream().noneMatch(policy -> policy.version().equals(consentVersion))
                : !required.version().equals(consentVersion)) {
            throw new ApiException(ErrorCode.CONSENT_VERSION_OUTDATED);
        }
        if (phone != null && (phone.isBlank() || phone.length() > 50)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        // Profile writes and their existing AccountLocaleChanged outbox event join this transaction.
        profiles.patch(accountId, locale, name);
        var membership = session.membership();
        if (membership != null && membership.memberId() != null) {
            String clubVersion = policies.getLast().version();
            members.update(membership.memberId(), accountId, phone == null ? null : phone.strip(), imageConsent, clubVersion, clock.instant());
        }
        Account.Consent consent = required == null ? null : new Account.Consent(required.policy(), required.clubId(), required.version(), clock.instant());
        accounts.completeOnboarding(accountId, consent);
        return state(identities.current(accountId), policies);
    }

    @Transactional
    public State postpone(String accountId) {
        var session = identities.current(accountId);
        var policies = policies();
        var required = required(session.account(), policies);
        if (remaining(session.account(), required) > 0) {
            var postponements = new ArrayList<>(session.account().consentPostponements());
            int used = used(session.account(), required);
            postponements.removeIf(value -> matches(required, value.policy(), value.clubId(), value.version()));
            postponements.add(new Account.ConsentPostponement(required.policy(), required.clubId(), required.version(), used + 1));
            accounts.postponeConsent(accountId, postponements);
        }
        return state(identities.current(accountId), policies);
    }

    private State state(IdentityService.Session session, List<Policy> policies) {
        var account = session.account();
        var required = required(account, policies);
        Object configured = TenantContext.current() == null ? catalog.defaultValue("signup.onboardingFields")
                : clubs.get(TenantContext.require()).get("signup.onboardingFields", Object.class);
        String memberId = session.membership() == null ? null : session.membership().memberId();
        var fields = new ArrayList<Field>();
        for (Object item : (List<?>) configured) {
            var definition = (Map<?, ?>) item;
            String key = (String) definition.get("key");
            String value = switch (key) {
                case "name" -> account.name();
                case "locale" -> account.locale();
                case "phone" -> memberId == null ? null : members.phone(memberId, account.id());
                default -> throw new ApiException(ErrorCode.PARAMETER_INVALID);
            };
            fields.add(new Field(key, value, Boolean.TRUE.equals(definition.get("required"))));
        }
        return new State(account.onboardingPending() || required != null, remaining(account, required), required, List.copyOf(fields));
    }

    private List<Policy> policies() {
        var result = new ArrayList<Policy>();
        result.add(new Policy(Account.ConsentPolicy.PLATFORM, null,
                environment.getRequiredProperty("agilityhub.legal.privacyPolicyVersion"),
                environment.getRequiredProperty("agilityhub.legal.privacyPolicyUrl")));
        if (TenantContext.current() != null) {
            var policy = clubs.privacyPolicy(TenantContext.require());
            result.add(new Policy(Account.ConsentPolicy.CLUB, TenantContext.require(), policy.version(), policy.url()));
        }
        return List.copyOf(result);
    }
    private Policy required(Account account, List<Policy> policies) {
        return policies.stream().filter(policy -> account.consents().stream().noneMatch(consent ->
                matches(policy, consent.policy(), consent.clubId(), consent.version()))).findFirst().orElse(null);
    }
    private int remaining(Account account, Policy required) {
        if (required == null || account.onboardingPending()) { return 0; }
        // A4 requires first acceptance. A12 postponements apply only after accepting this policy before.
        boolean renewal = account.consents().stream().anyMatch(consent -> consent.policy() == required.policy()
                && Objects.equals(consent.clubId(), required.clubId()));
        return renewal ? Math.max(0, settings.integer("legal.maxPostpones") - used(account, required)) : 0;
    }
    private int used(Account account, Policy policy) {
        return account.consentPostponements().stream().filter(value -> matches(policy, value.policy(), value.clubId(), value.version()))
                .mapToInt(Account.ConsentPostponement::count).sum();
    }
    private boolean matches(Policy policy, Account.ConsentPolicy kind, String clubId, String version) {
        return policy.policy() == kind && Objects.equals(policy.clubId(), clubId) && policy.version().equals(version);
    }
    public record Policy(Account.ConsentPolicy policy, String clubId, String version, String url) { }
    public record Field(String key, String value, boolean required) { }
    public record State(boolean pending, int postponeRemaining, Policy requiredConsent, List<Field> fields) { }
}

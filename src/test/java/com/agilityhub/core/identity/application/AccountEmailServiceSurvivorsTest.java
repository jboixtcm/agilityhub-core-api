package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.NotificationAccounts;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AccountEmailService}: the audit snapshot lists every visible consent (not a null entry), and
 * `markEmailStatus` (audited ACCOUNT_EMAIL_STATUS_CHANGED) writes the bounce/complaint state. Collaborators are mocks; fictional data.
 */
class AccountEmailServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountEmailService service = new AccountEmailService(accounts);

    /**
     * A signup account (SignupIdentityService.java:26-27: Security(0, null, null, 0), the club consent of the signup) that later
     * accepted the platform policy at onboarding (OnboardingService.java:66,109-111: PLATFORM, clubId null, version
     * `agilityhub.legal.privacyPolicyVersion`, default `v1`). The bounce of a platform (ID) mail carries no club, so the
     * webhook audits without a tenant (SendGridWebhookService.java:63) and only the platform consent is visible
     * (AccountEmailService.java:28-30).
     */
    @Test void E11_T06_theAuditSnapshotListsThePlatformConsentOfTheAccount() {
        var club = new Account.Consent(Account.ConsentPolicy.CLUB, "club-a", "2026-09", NOW.minusSeconds(86_400));
        var platform = new Account.Consent(Account.ConsentPolicy.PLATFORM, null, "v1", NOW);
        when(accounts.findById("acc-1")).thenReturn(Optional.of(new Account("acc-1", "laura@example.test", "Laura Example", "ca", null,
                Set.of(), Account.Status.ACTIVE, new Account.Security(0, null, null, 0), Map.of(), false, NOW.minusSeconds(86_400),
                null, null, Account.Source.SIGNUP, null, null, List.of(club, platform), List.of())));

        @SuppressWarnings("unchecked") var snapshot = (Map<String, Object>) service.load("acc-1");

        var expected = new LinkedHashMap<String, Object>();
        expected.put("policy", Account.ConsentPolicy.PLATFORM); expected.put("clubId", null);
        expected.put("version", "v1"); expected.put("acceptedAt", NOW);
        assertThat((List<Object>) snapshot.get("consents")).containsExactly(expected);
    }

    @Test void E11_T06_markingTheEmailStatusWritesItForTheExpectedAddress() {
        service.markEmailStatus("acc-1", "laura@example.test", NotificationAccounts.EmailStatus.BOUNCED);

        verify(accounts).markEmailStatus("acc-1", "laura@example.test", NotificationAccounts.EmailStatus.BOUNCED);
    }
}

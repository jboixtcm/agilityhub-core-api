package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** S18 R-18-16: account claims survive process crashes and concurrent command invocations. */
@Service
public class MigrationWelcomeDelivery {
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final MagicLinkService links;
    private final SystemNotificationService notifications;
    private final CensusClubSettings clubs;
    private final Clock clock;
    public MigrationWelcomeDelivery(AccountRepository accounts, MembershipRepository memberships, MagicLinkService links,
            SystemNotificationService notifications, CensusClubSettings clubs, Clock clock) {
        this.accounts = accounts; this.memberships = memberships; this.links = links;
        this.notifications = notifications; this.clubs = clubs; this.clock = clock;
    }
    public enum Result { ELIGIBLE, SENT, ALREADY_SENT, INELIGIBLE, SUPPRESSED, REVIEW_REQUIRED }
    public boolean staff(String accountId) {
        return memberships.findByAccountId(accountId).filter(m -> m.status() == Membership.Status.ACTIVE)
                .map(m -> m.roles().contains(Role.ADMIN) || m.roles().contains(Role.INSTRUCTOR)).orElse(false);
    }
    public Result deliver(String memberId, String accountId, String firstName, String gender, boolean dryRun) {
        String clubId = TenantContext.require();
        var account = accounts.findById(accountId).orElse(null);
        var membership = memberships.findByAccountId(accountId).orElse(null);
        if (account == null || account.status() != Account.Status.ACTIVE || membership == null
                || membership.status() != Membership.Status.ACTIVE || !memberId.equals(membership.memberId())) { return Result.INELIGIBLE; }
        if (account.emailStatus() != null) { return Result.SUPPRESSED; }
        var previous = accounts.migrationWelcome(accountId);
        if (previous.sentAt() != null) { return Result.ALREADY_SENT; }
        String deliveryId = UUID.nameUUIDFromBytes(("migration-welcome:" + clubId + ":" + accountId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        if (previous.deliveryId() != null) {
            // Reconcile only this tenant's accepted delivery. QUEUED/FAILED or missing rows need human review:
            // a crash between provider acceptance and persistence cannot be safely auto-retried.
            if (deliveryId.equals(previous.deliveryId()) && notifications.sent(deliveryId)) {
                if (!dryRun) { accounts.migrationWelcomeSent(accountId, deliveryId, clock.instant()); }
                return Result.ALREADY_SENT;
            }
            return Result.REVIEW_REQUIRED;
        }
        if (dryRun) { return Result.ELIGIBLE; }
        String host = clubs.appHost(); // Validate the destination before taking an irreversible delivery claim.
        var variables = Map.<String, Object>of("member_first_name", firstName == null ? "" : firstName,
                "gender", gender == null ? "OTHER" : gender, "club_name", clubs.name(), "variant", "MIGRATED");
        if (!accounts.claimMigrationWelcome(accountId, deliveryId, clock.instant())) { return Result.REVIEW_REQUIRED; }
        links.createAndSend(account.email(), MagicLinkToken.Purpose.WELCOME, "clubs-app", null, host, null, null, deliveryId, variables);
        if (!notifications.sent(deliveryId)) { return Result.REVIEW_REQUIRED; }
        accounts.migrationWelcomeSent(accountId, deliveryId, clock.instant());
        return Result.SENT;
    }
}

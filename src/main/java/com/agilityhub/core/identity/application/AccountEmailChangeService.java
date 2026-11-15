package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditActor;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import java.time.Clock;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

/**
 * AUTH-02 (S03 R-03-06b): the login e-mail change a club ADMIN asked for on D10 takes effect when the link mailed to the new
 * address is used ({@link TokenService#magicLink}), inside that redemption's transaction: `Account.email` becomes the new
 * address, verified, and the suppression mark of the old one (`emailStatus`) is cleared. One `ACCOUNT_EMAIL_CHANGED` entry,
 * the requesting ADMIN's (`origin = BACKOFFICE`), with the change of `email` and `emailStatus`. Nothing is sent to the old address.
 */
@Service
public class AccountEmailChangeService {
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final AuditWriter audits;
    private final AuditActorProvider actors;
    private final Clock clock;
    public AccountEmailChangeService(AccountRepository accounts, MembershipRepository memberships, AuditWriter audits,
            AuditActorProvider actors, Clock clock) {
        this.accounts = accounts; this.memberships = memberships; this.audits = audits; this.actors = actors; this.clock = clock;
    }

    /** Whether the link's new address can still become its account's: it names one, and no other account has taken it since. */
    public boolean applicable(MagicLinkToken token) {
        if (token.purpose() != MagicLinkToken.Purpose.EMAIL_CHANGE || token.email() == null) { return false; }
        return accounts.findByEmail(token.email()).map(holder -> holder.id().equals(token.accountId())).orElse(true);
    }

    /** Applies an {@link #applicable} link, after it has been consumed in the same transaction. */
    public void apply(MagicLinkToken token) {
        Account account = accounts.findById(token.accountId()).orElseThrow();
        if (!accounts.changeEmail(account.id(), token.email(), clock.instant())) {
            throw new IllegalStateException("Login e-mail change of an inactive account");
        }
        var before = new LinkedHashMap<String, Object>();
        before.put("email", account.email()); before.put("emailStatus", account.emailStatus());
        var after = new LinkedHashMap<String, Object>();
        after.put("email", token.email()); after.put("emailStatus", null);
        String memberId = memberships.findByAccountId(account.id()).map(Membership::memberId).orElse(null);
        String requester = token.requestedByAccountId();
        String name = requester == null ? null : accounts.findById(requester).map(Account::name).orElse(null);
        // The entry is the requesting ADMIN's: the redemption request is the new address holder's, so only its trace is kept.
        var actor = new AuditActor(requester, name, "ADMIN", null, null, null, null, actors.current().traceId());
        audits.writeAs(new AuditCommand(AuditAction.ACCOUNT_EMAIL_CHANGED, "Account", account.id(), memberId, before, after, null),
                actor, "BACKOFFICE");
    }
}

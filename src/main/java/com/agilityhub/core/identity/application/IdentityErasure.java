package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.IdentityErasureRepository;
import com.agilityhub.core.platform.application.privacy.AccountErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureAccounts;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasurePlaceholders;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, the identity side (S01 §7 `AccountErased → status ERASED`). Per club: the erased members' memberships go to
 * `ERASED` with no roles, and their club access (refresh tokens, magic links, handoff codes, impersonation grants) is deleted.
 * Globally, for an erased account: e-mail → the pseudonym, name → «Compte suprimit», no password, external ids or verification,
 * `ERASED`, every membership erased and every token, code, grant and OIDC session deleted.
 */
@Component
public class IdentityErasure implements ErasureAccounts, ErasureStep, AccountErasureStep, MemberDataSource {
    private final AccountRepository accounts;
    private final IdentityErasureRepository erasure;
    public IdentityErasure(AccountRepository accounts, IdentityErasureRepository erasure) { this.accounts = accounts; this.erasure = erasure; }

    @Override public Optional<Account> account(String accountId) {
        return accounts.findById(accountId).map(account -> new ErasureAccounts.Account(account.id(), account.email(), account.locale(),
                account.externalIds() != null && account.externalIds().get("learnUserId") != null, account.status() == com.agilityhub.core.identity.persistence.Account.Status.ERASED));
    }
    @Override public List<Membership> memberships(String accountId) {
        return erasure.memberships(accountId).stream().map(membership -> new ErasureAccounts.Membership(membership.clubId(), membership.memberId(),
                membership.status().name())).toList();
    }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        for (String accountId : subject.accountIds()) {
            effect.count("memberships", erasure.eraseMembership(accountId, subject.clubId(), subject.at()));
            effect.count("refresh_tokens+magic_link_tokens+handoff_codes", erasure.deleteClubAccess(accountId, subject.clubId()));
        }
        return effect.count("impersonation_grants", erasure.deleteGrantsFor(subject.clubId(), subject.memberIds())).build();
    }

    @Override public ErasureEffect eraseAccount(String accountId, String pseudonym, ErasurePlaceholders placeholders, Instant at) {
        var effect = ErasureEffect.builder();
        effect.count("accounts", erasure.eraseAccount(accountId, pseudonym, placeholders.account(), at) ? 1 : 0);
        effect.count("memberships", erasure.eraseAllMemberships(accountId, at));
        return effect.count("tokens+grants+oidc", erasure.deleteAccountAccess(accountId)).build();
    }

    /** S14 R-14-13: `account` (e-mail, name, locale, creation, last login) and `memberships` (this club's only: the package is the club's). */
    @Override public void contribute(ErasureMember member, MemberDataPackage data) {
        if (member.accountId() == null) { return; }
        var account = erasure.account(member.accountId());
        if (account == null) { return; }
        var row = new java.util.LinkedHashMap<String, Object>();
        for (String key : List.of("email", "name", "locale", "createdAt", "lastLoginAt")) { row.put(key, account.get(key)); }
        data.section("account", MemberDataPackage.row(row));
        data.section("memberships", erasure.membership(member.accountId(), com.agilityhub.core.shared.application.TenantContext.require()).stream()
                .map(document -> {
                    var kept = new java.util.LinkedHashMap<String, Object>();
                    for (String key : List.of("clubId", "roles", "status", "createdAt", "lastAccessAt")) { kept.put(key, document.get(key)); }
                    var clean = MemberDataPackage.row(kept); clean.put("clubId", document.get("clubId"));
                    return (Object) clean;
                }).toList());
    }
}

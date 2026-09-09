package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformRoleService {
    private final AccountRepository accounts;
    public PlatformRoleService(AccountRepository accounts) { this.accounts = accounts; }

    public Set<String> get(String actorId, String accountId) {
        requireAdmin(actorId);
        return roles(accountId);
    }

    @Transactional
    @Audited(action = AuditAction.PLATFORM_ROLES_CHANGED, entityType = "'Account'", entity = "#accountId")
    public Set<String> replace(String actorId, String accountId, Set<String> roles) {
        accounts.serializePlatformRoles();
        requireAdmin(actorId);
        return update(accountId, roles);
    }

    /** Deployment CLI only; no HTTP controller exposes bootstrap authority. */
    @Transactional
    @Audited(action = AuditAction.PLATFORM_ROLES_CHANGED, entityType = "'Account'", entity = "#accountId")
    public Set<String> grant(String accountId) {
        accounts.serializePlatformRoles();
        return update(accountId, Set.of(Account.PlatformRole.AGILITYHUB_ADMIN.name()));
    }

    private Set<String> update(String accountId, Set<String> roles) {
        if (roles == null || roles.stream().anyMatch(role -> !Account.PlatformRole.AGILITYHUB_ADMIN.name().equals(role))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        var account = account(accountId);
        if (account.status() != Account.Status.ACTIVE) { throw new ApiException(ErrorCode.ACCOUNT_BLOCKED); }
        if (roles.isEmpty() && !account.platformRoles().isEmpty() && accounts.activePlatformAdmins() <= 1) {
            throw new ApiException(ErrorCode.LAST_PLATFORM_ADMIN);
        }
        accounts.platformRoles(accountId, roles.stream().map(Account.PlatformRole::valueOf).collect(java.util.stream.Collectors.toSet()));
        return roles(accountId);
    }
    private void requireAdmin(String actorId) {
        var actor = accounts.findById(actorId).orElse(null);
        if (actor == null || actor.status() != Account.Status.ACTIVE || !actor.platformRoles().contains(Account.PlatformRole.AGILITYHUB_ADMIN)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
    private Account account(String id) { return accounts.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    private Set<String> roles(String id) { return account(id).platformRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()); }
}

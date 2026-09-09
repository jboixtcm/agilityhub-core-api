package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class GrantPlatformAdminCommand implements CoreCommand {
    private final AccountRepository accounts;
    private final PlatformRoleService roles;
    private final IdentityTransactions transactions;
    public GrantPlatformAdminCommand(AccountRepository accounts, PlatformRoleService roles, IdentityTransactions transactions) {
        this.accounts = accounts; this.roles = roles; this.transactions = transactions;
    }
    @Override public String name() { return "identity:grant-platform-admin"; }
    @Override public void run(ApplicationArguments arguments) {
        if (arguments.getNonOptionArgs().size() != 1) {
            throw new IllegalArgumentException("Usage: identity:grant-platform-admin <email>");
        }
        String accountId = accounts.findByEmail(arguments.getNonOptionArgs().getFirst())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).id();
        transactions.run(() -> roles.grant(accountId));
        System.out.println("Platform administrator role ensured.");
    }
}

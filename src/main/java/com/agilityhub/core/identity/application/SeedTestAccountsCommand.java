package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Temporary runner until E0-T10 supplies the CLI dispatcher. Existing accounts are never reset. */
@Component
@Profile("(local | test) & !staging & !prod")
@Order(100)
public class SeedTestAccountsCommand implements ApplicationRunner {
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordHasher passwords;
    private final ClubConfigService clubs;
    private final Clock clock;
    private final String password;
    public SeedTestAccountsCommand(AccountRepository accounts, MembershipRepository memberships, PasswordHasher passwords,
                                   ClubConfigService clubs, Clock clock, @Value("${identity.seed-password}") String password) {
        this.accounts = accounts; this.memberships = memberships; this.passwords = passwords;
        this.clubs = clubs; this.clock = clock; this.password = password;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains("identity:seed-test-accounts")) { return; }
        var slugs = args.getOptionValues("club");
        if (slugs == null || slugs.size() != 1 || password.isBlank()) {
            throw new IllegalArgumentException("identity:seed-test-accounts requires --club=<slug> and SEED_PASSWORD");
        }
        String clubId = clubs.findClubIdBySlug(slugs.getFirst()).orElseThrow(() -> new IllegalArgumentException("Unknown club slug"));
        try (var scope = TenantContext.open(clubId)) {
            String locale = clubs.get(clubId).club().defaultLocale();
            for (Role role : Role.values()) {
                String email = role.name().toLowerCase(java.util.Locale.ROOT) + "@example.test";
                Account account = accounts.findByEmail(email).orElseGet(() -> accounts.save(new Account(UUID.randomUUID().toString(),
                        email, "Example " + role.name(), locale, passwords.hash(password), Set.of(), Account.Status.ACTIVE,
                        new Account.Security(0, null, null, 0), Map.of(), true, clock.instant())));
                if (memberships.findByAccountId(account.id()).isEmpty()) {
                    memberships.insert(new Membership(UUID.randomUUID().toString(), account.id(), clubId, null,
                            role == Role.MEMBER ? Set.of(Role.MEMBER) : Set.of(Role.MEMBER, role), Membership.Status.ACTIVE, role));
                }
            }
        }
    }
}

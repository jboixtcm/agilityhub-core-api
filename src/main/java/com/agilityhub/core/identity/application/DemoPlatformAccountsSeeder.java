package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * E10-T03 step 4 (organizer note E118; web E10-W01 question 3): the `platform` section of a demo seed, fictional AgilityHub
 * team accounts with `platformRoles: [AGILITYHUB_ADMIN]` and **no club role**, so a local stack proves the console's entry
 * without a club (S17 §2 «Entrada»: a product host, a token without `clubId`) with no hand-made grant. Insert-only like every
 * seed account (an existing account keeps its name, language and password and only gains the role); the demo planning run is
 * recorded once, so a second `seed:demo` changes nothing. Local/test only, as the whole demo seed.
 */
@Service
public class DemoPlatformAccountsSeeder implements DemoSeedStep {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountSpec(String email, String name, String locale, String password) {
        @Override public String toString() { return "AccountSpec[redacted]"; }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(List<AccountSpec> accounts) {
        public Spec { accounts = accounts == null ? List.of() : List.copyOf(accounts); }
    }
    private final ObjectMapper mapper; private final AccountService accountService; private final AccountRepository accounts;
    private final PasswordHasher passwords; private final SeedPasswordPolicy policy;
    public DemoPlatformAccountsSeeder(ObjectMapper mapper, AccountService accountService, AccountRepository accounts, PasswordHasher passwords,
            SeedPasswordPolicy policy) {
        this.mapper = mapper; this.accountService = accountService; this.accounts = accounts; this.passwords = passwords; this.policy = policy;
    }
    @Override public int order() { return 5; }
    @Override public Map<String, Integer> apply(Input input) {
        var section = input.specification().get("platform");
        if (section == null) { return Map.of(); }
        var spec = mapper.convertValue(section, Spec.class);
        for (var seed : spec.accounts()) {
            String password = policy.resolve(seed.password(), false);
            // E10-T03 round 2 (review #7): `createdSource` is closed by the model (SIGNUP · IMPORT_LEARN · CONSOLE · MIGRATION, S01
            // §3) and has no seed value. CONSOLE is the closest one: an operator provisions the account, as `club:apply`'s seed
            // accounts and the demo identities do. A `SEED` value is proposed in the task's report; it changes nothing locally.
            var account = accounts.findByEmail(seed.email()).orElseGet(() -> accountService.getOrCreate(seed.email(), seed.name(), seed.locale(),
                    Account.Source.CONSOLE, password == null ? null : passwords.hash(password), false));
            var held = account.platformRoles() == null ? java.util.Set.<Account.PlatformRole>of() : account.platformRoles();
            if (!held.contains(Account.PlatformRole.AGILITYHUB_ADMIN)) {
                var roles = EnumSet.of(Account.PlatformRole.AGILITYHUB_ADMIN); roles.addAll(held);
                accounts.platformRoles(account.id(), roles);
            }
        }
        return Map.of("platformAccounts", spec.accounts().size());
    }
}

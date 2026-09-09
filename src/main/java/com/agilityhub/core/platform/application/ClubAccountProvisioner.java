package com.agilityhub.core.platform.application;

import java.util.Set;

/** Identity seed port. All membership operations use the current tenant. */
public interface ClubAccountProvisioner {
    record SeedAccount(String email, String name, String locale, Set<String> roles,
                       String password, Boolean onboardingPending) {
        @Override public String toString() { return "SeedAccount[redacted]"; }
    }
    void validate(SeedAccount account, boolean allowSeedPasswords);
    boolean needsProvision(SeedAccount account);
    void provision(SeedAccount account, boolean allowSeedPasswords);
    java.util.List<SeedAccount> list();
}

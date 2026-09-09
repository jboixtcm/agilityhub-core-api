package com.agilityhub.core.identity.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class SeedPasswordPolicy {
    private final boolean local;
    private final boolean deployment;
    private final String environmentPassword;

    public SeedPasswordPolicy(Environment environment, @Value("${identity.seed-password}") String environmentPassword) {
        deployment = environment.acceptsProfiles(Profiles.of("staging", "prod"));
        local = environment.acceptsProfiles(Profiles.of("local", "test")) && !deployment;
        this.environmentPassword = environmentPassword;
    }

    public String resolve(String declared, boolean allowSeedPasswords) {
        if (declared == null) { return null; }
        boolean reference = declared.equals("${SEED_PASSWORD}");
        if (!local && !(deployment && reference && allowSeedPasswords)) {
            throw new IllegalArgumentException("Seed passwords require local/test; staging/prod require SEED_PASSWORD and --allow-seed-passwords");
        }
        if (reference && environmentPassword.isBlank()) {
            throw new IllegalArgumentException("Set SEED_PASSWORD or omit accounts[].password for passwordless accounts");
        }
        return reference ? environmentPassword : declared;
    }
}

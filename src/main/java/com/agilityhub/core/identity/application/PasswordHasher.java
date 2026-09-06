package com.agilityhub.core.identity.application;

import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    // OWASP minimum: 19 MiB, two iterations, one lane; 128-bit random salt.
    private final Argon2PasswordEncoder argon = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final String dummy = argon.encode(UUID.randomUUID().toString());

    public String hash(String password) { return argon.encode(password); }

    /** Always performs an adaptive hash verification, including missing/magic-link-only accounts. */
    public boolean verify(String password, String hash) {
        if (hash == null) { argon.matches(password, dummy); return false; }
        try {
            if (hash.startsWith("$argon2id$")) { return argon.matches(password, hash); }
            if (hash.matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")) { return bcrypt.matches(password, hash); }
        } catch (IllegalArgumentException malformedHash) {
            // An unusable imported hash must not reveal an account or escape as a server error.
        }
        argon.matches(password, dummy);
        return false;
    }
}

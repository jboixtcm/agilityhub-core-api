package com.agilityhub.core.identity.application;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * S01 R-01-03: argon2id for new passwords, bcrypt (`$2y$`/`$2a$`/`$2b$`) for the hashes imported from Learn. INC-37 (T-01-02 «temps
 * constant (dummy hash) mesurat ± 20 %»): every verification lasts at least {@link #floor()}, the time of the slowest one the
 * product accepts (a bcrypt hash of {@link #MAX_BCRYPT_COST}, Learn's `BCRYPT_ROUNDS`, S01 §14), measured once at start. So a
 * missing account, an account without a password, an argon2id hash and an imported bcrypt hash cannot be told apart by time.
 */
@Component
public class PasswordHasher {
    /** S01 §14: Learn hashes with `BCRYPT_ROUNDS=12`; older Laravel rows may have fewer rounds, never more (the import refuses them). */
    public static final int MAX_BCRYPT_COST = 12;
    /** The bcrypt hashes the Learn import accepts: the three prefixes, cost 04 to {@link #MAX_BCRYPT_COST}. */
    public static final Pattern IMPORTABLE_BCRYPT = Pattern.compile("\\$2[aby]\\$(0[4-9]|1[0-2])\\$[./A-Za-z0-9]{53}");
    // OWASP minimum: 19 MiB, two iterations, one lane; 128-bit random salt.
    private final Argon2PasswordEncoder argon = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(MAX_BCRYPT_COST);
    private final String dummy = argon.encode(UUID.randomUUID().toString());
    private final String bcryptDummy = bcrypt.encode(UUID.randomUUID().toString());
    private final long floorNanos = calibrate();

    public String hash(String password) { return argon.encode(password); }

    /** Always performs an adaptive hash verification, including missing/magic-link-only accounts, and lasts at least {@link #floor()}. */
    public boolean verify(String password, String hash) {
        long start = System.nanoTime();
        try { return check(password, hash); }
        finally { pad(start); }
    }
    /** INC-37: the shortest time a verification takes. */
    public Duration floor() { return Duration.ofNanos(floorNanos); }

    private boolean check(String password, String hash) {
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
    private void pad(long start) {
        long end = start + floorNanos;
        for (long left = end - System.nanoTime(); left > 0; left = end - System.nanoTime()) { LockSupport.parkNanos(left); }
    }
    /**
     * The slower of the two dummy checks, warmed up once and then the longest of three runs, plus a quarter, so a busy host that
     * slows a real bcrypt check down still finishes it within the floor.
     */
    private long calibrate() {
        long slowest = 0;
        for (int run = 0; run < 4; run++) {
            long start = System.nanoTime();
            bcrypt.matches("calibration", bcryptDummy);
            long bcryptTime = System.nanoTime() - start;
            start = System.nanoTime();
            argon.matches("calibration", dummy);
            long argonTime = System.nanoTime() - start;
            if (run > 0) { slowest = Math.max(slowest, Math.max(bcryptTime, argonTime)); }
        }
        return slowest + slowest / 4;
    }
}

package com.agilityhub.core.identity.application;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {
    // One instance: building one measures the slowest verification (INC-37).
    static final PasswordHasher hasher = new PasswordHasher();

    /** Timed runs of one verification. */
    static final int RUNS = 5;

    /**
     * {@link #RUNS} timed runs of {@code verification}, in nanoseconds, sorted. Every run is asserted to last at least the floor:
     * that bound holds on any host, however busy.
     */
    static long[] times(String name, Runnable verification) {
        long[] times = new long[RUNS];
        for (int run = 0; run < times.length; run++) {
            long start = System.nanoTime();
            verification.run();
            times[run] = System.nanoTime() - start;
            assertThat(times[run]).as("%s, run %d, vs the floor", name, run + 1).isGreaterThanOrEqualTo(hasher.floor().toNanos());
        }
        java.util.Arrays.sort(times);
        return times;
    }

    /**
     * INC-37 (T-01-02 «temps constant (dummy hash) mesurat ± 20 %»): a missing account, an account without a password, an unusable
     * hash, an argon2id hash and Learn's bcrypt hashes (cost 10 and 12), right or wrong, take the same time within 20 %.
     * E11-T09 round 2 (review #1): every run lasts at least {@link PasswordHasher#floor()}, so no case can be measurably faster
     * than another. A busy host (a GC pause, a shared CI runner) only adds time, so the upper bound is measured on the fastest of
     * {@link #RUNS} runs: some run of every case ends within 20 % of the floor, unless a check itself outlasts the floor.
     */
    @Test void T_01_02_INC_37_everyVerificationTakesTheSameTimeWithinTwentyPercent() {
        String argon = hasher.hash("Example password");
        var cost12 = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(
                org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.BCryptVersion.$2Y, 12).encode("Example password");
        var cost10 = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(
                org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.BCryptVersion.$2Y, 10).encode("Example password");
        hasher.verify("warm-up", argon); hasher.verify("warm-up", cost12); hasher.verify("warm-up", null);
        long floor = hasher.floor().toNanos();
        var cases = new java.util.LinkedHashMap<String, Runnable>();
        cases.put("missing account", () -> hasher.verify("Example password", null));
        cases.put("unusable hash", () -> hasher.verify("Example password", "unsupported"));
        cases.put("argon2id, wrong", () -> hasher.verify("wrong", argon));
        cases.put("argon2id, right", () -> hasher.verify("Example password", argon));
        cases.put("bcrypt 12, wrong", () -> hasher.verify("wrong", cost12));
        cases.put("bcrypt 12, right", () -> hasher.verify("Example password", cost12));
        cases.put("bcrypt 10, wrong", () -> hasher.verify("wrong", cost10));
        cases.put("bcrypt 10, right", () -> hasher.verify("Example password", cost10));
        cases.forEach((name, verification) -> assertThat((double) times(name, verification)[0])
                .as("%s, fastest of %d runs, vs the floor (%d ms)", name, RUNS, floor / 1_000_000).isBetween((double) floor, floor * 1.2));
    }
    @Test void T_01_02_learnPhpCost12HashAndAllBcryptPrefixesVerify() throws Exception {
        String php;
        try (var input = getClass().getResourceAsStream("/fixtures/identity/php-bcrypt.txt")) {
            php = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        assertThat(php).startsWith("$2y$12$");
        for (String prefix : new String[]{"$2y$", "$2a$", "$2b$"}) {
            String hash = prefix + php.substring(4);
            assertThat(hasher.verify("Learn-fixture-password", hash)).isTrue();
            assertThat(hasher.verify("wrong", hash)).isFalse();
        }
    }
    @Test void T_01_02_newHashesUseSaltedOwaspArgon2id() {
        String hash = hasher.hash("Example password");
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(hasher.hash("Example password")).isNotEqualTo(hash);
        assertThat(hasher.verify("Example password", hash)).isTrue();
        assertThat(hasher.verify("wrong", hash)).isFalse();
    }
    @Test void T_01_02_missingAccountStillInvokesTheAdaptiveVerifier() {
        try (var construction = org.mockito.Mockito.mockConstruction(
                org.springframework.security.crypto.argon2.Argon2PasswordEncoder.class,
                (encoder, context) -> org.mockito.Mockito.when(encoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("dummy-hash"))) {
            var subject = new PasswordHasher();
            assertThat(subject.verify("unknown-account-password", null)).isFalse();
            org.mockito.Mockito.verify(construction.constructed().getFirst()).matches("unknown-account-password", "dummy-hash");
        }
    }
    @Test void T_01_02_missingAndUnusableHashesFailThroughDummyVerification() {
        assertThat(hasher.verify("anything", null)).isFalse();
        assertThat(hasher.verify("anything", "unsupported")).isFalse();
        assertThat(hasher.verify("anything", "$argon2id$broken")).isFalse();
    }
}

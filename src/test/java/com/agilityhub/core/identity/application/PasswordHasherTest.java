package com.agilityhub.core.identity.application;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {
    final PasswordHasher hasher = new PasswordHasher();
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

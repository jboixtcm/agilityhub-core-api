package com.agilityhub.core.payments.application;

import com.agilityhub.core.migration.application.MigrationBankVault;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Ruling E43 (INC-31, E8-T01): one vault resolves the full IBAN of both origins for the SEPA writer only — a signup or D10
 * member's `iban` in clear, a migrated member's `ibanEncrypted` (S18), which {@link MigrationBankVault} encrypts through this
 * very vault and key (ruling E85: `BILLING_BANK_KEY` is the only bank key). A wrong key, a tampered value or another member's
 * associated data never yields an IBAN, and no failure message carries the value or the key.
 */
class BankAccountVaultTest {
    static final String KEY = Base64.getEncoder().encodeToString("e8-t01-fictional-key-32-bytes!!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    static final String OTHER_KEY = Base64.getEncoder().encodeToString("e8-t01-another-fictional-key-32b".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    static final String IBAN = "ES0000000000000000001234";

    @Test void E43_aMigratedIbanEncryptedByTheMigrationVaultIsResolvedWithTheSameKeyAndAssociatedData() {
        String encrypted = new MigrationBankVault(TestBankVaults.of(KEY)).encrypt(IBAN, "club-a", "member-a");
        var vault = TestBankVaults.of(KEY);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "ibanEncrypted", encrypted, "ibanLast4", "1234"), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "sepa", Map.of("ibanEncrypted", encrypted)), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.decrypt(vault.encrypt(IBAN, "club-a", "member-b"), "club-a", "member-b")).isEqualTo(IBAN);
        assertThat(vault.encrypt(IBAN, "club-a", "member-a")).isNotEqualTo(vault.encrypt(IBAN, "club-a", "member-a")).doesNotContain("1234");
    }

    @Test void E43_aClearIbanIsReturnedAsStoredAndAMemberWithoutOneHasNone() {
        var vault = TestBankVaults.of(KEY);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "iban", IBAN), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "sepa", Map.of("iban", IBAN)), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "iban", " ", "ibanLast4", "1234"), "club-a", "member-a")).isNull();
        assertThat(vault.resolve(Map.of("type", "MANUAL", "channel", "CASH"), "club-a", "member-a")).isNull();
        assertThat(vault.resolve(null, "club-a", "member-a")).isNull();
        // A clear IBAN needs no key: only the encrypted shape reads BILLING_BANK_KEY.
        assertThat(TestBankVaults.of("").resolve(Map.of("iban", IBAN), "club-a", "member-a")).isEqualTo(IBAN);
    }

    /** INC-31 (A37): the census seals what it stores; the stored fields never carry the IBAN, and only this vault reads it back. */
    @Test void INC_31_E43_aSealedAccountKeepsOnlyItsCiphertextAndLastFourAndTheVaultReadsItBack() {
        var vault = TestBankVaults.of(KEY);
        var stored = vault.seal(IBAN, "club-a", "member-a");
        assertThat(stored).containsOnlyKeys("ibanEncrypted", "ibanLast4").containsEntry("ibanLast4", "1234");
        assertThat(stored.toString()).doesNotContain(IBAN);
        assertThat(vault.resolve(stored, "club-a", "member-a")).isEqualTo(IBAN);
        assertThatThrownBy(() -> vault.resolve(stored, "club-a", "member-b")).isInstanceOf(IllegalStateException.class);
        assertThat(vault.configured()).isTrue();
        // A stack without BILLING_BANK_KEY (local only: production Compose requires it) keeps the clear shape.
        var local = TestBankVaults.of("");
        assertThat(local.configured()).isFalse();
        assertThat(local.seal(IBAN, "club-a", "member-a")).containsExactly(Map.entry("iban", IBAN));
    }

    /**
     * INC-31, E11-T02 round 2 (review #8): without `BILLING_BANK_KEY` only the local stack and the tests store an IBAN in clear,
     * with a WARN that names no account; staging and prod refuse it as a misconfiguration, never undoing INC-31: they do not start
     * (E11-T11), and a keyless vault's refusal is `500 INTERNAL_ERROR`. Ruling E139 (INC-74, E11-T13): a run with no active
     * profile is `local` by design (`spring.profiles.default: local` in `application.yml`), so it keeps the clear IBAN with the
     * WARN; that case reads the default profile from the real `application.yml` instead of a bare `MockEnvironment`, whose
     * default profile (`default`) the real configuration never has.
     */
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void INC_31_E43_withoutTheKeyOnlyTheLocalProfileStoresAClearIbanAndItWarns(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        for (var profiles : java.util.List.of(new String[]{"prod"}, new String[]{"staging"})) {
            var environment = new org.springframework.mock.env.MockEnvironment(); environment.setActiveProfiles(profiles);
            // E11-T11: the application does not start without a usable key outside local and test (missing or malformed).
            for (String unusable : new String[]{"", "%%%"}) {
                assertThatThrownBy(() -> new BankAccountVault(unusable, environment)).as(String.join(",", profiles)).isInstanceOf(IllegalStateException.class)
                        .hasMessage("BILLING_BANK_KEY (Base64 of 32 bytes) is required outside the local and test profiles");
            }
            // With the key, a production vault seals as usual.
            assertThat(new BankAccountVault(KEY, environment).seal(IBAN, "club-a", "member-a")).containsOnlyKeys("ibanEncrypted", "ibanLast4");
        }
        // A vault that still has no key refuses with 500 INTERNAL_ERROR and the cause (AGENTS rule 9), not a bare exception.
        assertThatThrownBy(() -> new BankAccountVault("", false).seal(IBAN, "club-a", "member-a")).isInstanceOfSatisfying(
                com.agilityhub.core.shared.domain.ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo(com.agilityhub.core.shared.domain.ErrorCode.INTERNAL_ERROR);
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class)
                            .hasMessage("BILLING_BANK_KEY is required to store a bank account outside the local profile");
                });
        assertThat(output.getAll()).doesNotContain("stored in clear");
        for (String profile : new String[]{"local", "test"}) {
            var environment = new org.springframework.mock.env.MockEnvironment(); environment.setActiveProfiles(profile);
            assertThat(new BankAccountVault("", environment).seal(IBAN, "club-a", "member-a")).containsExactly(Map.entry("iban", IBAN));
        }
        // No active profile, as the real configuration resolves it: `spring.profiles.default` of application.yml, i.e. `local`.
        var yaml = new org.springframework.boot.env.YamlPropertySourceLoader().load("application", new org.springframework.core.io.ClassPathResource("application.yml"));
        String defaults = String.valueOf(yaml.getFirst().getProperty("spring.profiles.default"));
        assertThat(defaults).isEqualTo("local");
        var profileless = new org.springframework.mock.env.MockEnvironment(); profileless.setDefaultProfiles(defaults);
        assertThat(profileless.getActiveProfiles()).isEmpty();
        assertThat(new BankAccountVault("", profileless).seal(IBAN, "club-a", "member-a")).containsExactly(Map.entry("iban", IBAN));
        assertThat(output.getAll()).contains("BILLING_BANK_KEY is not set: a bank account is stored in clear (local profile only) clubId=club-a")
                .doesNotContain(IBAN).doesNotContain("member-a");
    }

    /** INC-31: two stored accounts of a member are compared in the vault, across both shapes and two seals of one IBAN. */
    @Test void INC_31_E43_sameAnswersWhetherTwoStoredAccountsHoldOneIban() {
        var vault = TestBankVaults.of(KEY);
        var first = vault.seal(IBAN, "club-a", "member-a");
        var again = vault.seal(IBAN, "club-a", "member-a");
        assertThat(first).isNotEqualTo(again);
        assertThat(vault.same(first, again, "club-a", "member-a")).isTrue();
        assertThat(vault.same(first, Map.of("iban", IBAN), "club-a", "member-a")).isTrue();
        assertThat(vault.same(first, vault.seal("ES0000000000000000005678", "club-a", "member-a"), "club-a", "member-a")).isFalse();
        assertThat(vault.same(Map.of(), Map.of(), "club-a", "member-a")).isFalse();
    }

    @Test void E43_aWrongKeyAnotherMembersDataATamperedValueOrAMissingKeyFailWithoutLeakingAnything() {
        String encrypted = TestBankVaults.of(KEY).encrypt(IBAN, "club-a", "member-a");
        for (var failure : java.util.List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> TestBankVaults.of(OTHER_KEY).decrypt(encrypted, "club-a", "member-a"),
                () -> TestBankVaults.of(KEY).decrypt(encrypted, "club-a", "member-b"),
                () -> TestBankVaults.of(KEY).decrypt(encrypted, "club-b", "member-a"),
                () -> TestBankVaults.of(KEY).decrypt(encrypted.substring(0, encrypted.length() - 4) + "AAAA", "club-a", "member-a"),
                () -> TestBankVaults.of(KEY).decrypt("not base64 !", "club-a", "member-a"),
                () -> TestBankVaults.of(KEY).decrypt(Base64.getEncoder().encodeToString(new byte[12]), "club-a", "member-a"))) {
            assertThatThrownBy(failure).isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed");
        }
        assertThatThrownBy(() -> TestBankVaults.of("").encrypt(IBAN, "club-a", "member-a")).isInstanceOf(IllegalStateException.class)
                .hasMessage("BILLING_BANK_KEY must be 32 bytes");
        assertThatThrownBy(() -> TestBankVaults.of("%%%").resolve(Map.of("ibanEncrypted", encrypted), "club-a", "member-a"))
                .isInstanceOf(IllegalStateException.class).hasMessage("BILLING_BANK_KEY is not Base64");
        assertThatThrownBy(() -> TestBankVaults.of(Base64.getEncoder().encodeToString(new byte[16])).decrypt(encrypted, "club-a", "member-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(IBAN);
        // E85: the migration refuses bank data without a usable BILLING_BANK_KEY, before writing anything.
        assertThat(TestBankVaults.of(KEY).configured()).isTrue();
        for (String unusable : java.util.List.of("", "%%%", Base64.getEncoder().encodeToString(new byte[16]))) {
            assertThat(TestBankVaults.of(unusable).configured()).as(unusable).isFalse();
        }
    }

    static final String THIRD_KEY = Base64.getEncoder().encodeToString("priv-11-a-third-fictional-key-32".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    static String keyId(String key) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)), 0, 4);
    }

    /** A value as every release sealed it before PRIV-11: the Base64 of nonce and ciphertext, without a key id. */
    static String sealedBeforePriv11(String key, String iban, String clubId, String memberId) {
        return new AesGcmCipher(key, "BILLING_BANK_KEY", "Bank account").encrypt(iban, clubId + ":" + memberId);
    }

    /** PRIV-11: a new value starts with the id of its key (8 hex of its SHA-256); a value sealed before, without one, is still read. */
    @Test void PRIV_11_aNewValueCarriesTheIdOfItsKeyAndAValueSealedBeforeWithoutOneIsStillRead() throws Exception {
        var vault = TestBankVaults.of(KEY);
        assertThat(vault.keyId()).isEqualTo(keyId(KEY)).matches("[0-9a-f]{8}").isNotEqualTo(keyId(OTHER_KEY));
        assertThat(vault.previousKeyId()).isNull();
        String sealed = vault.encrypt(IBAN, "club-a", "member-a");
        assertThat(sealed).startsWith(keyId(KEY) + ":").doesNotContain(IBAN, "1234");
        assertThat(Base64.getDecoder().decode(sealed.substring(9))).hasSizeGreaterThan(12);
        assertThat(vault.decrypt(sealed, "club-a", "member-a")).isEqualTo(IBAN);
        assertThat((String) vault.seal(IBAN, "club-a", "member-a").get("ibanEncrypted")).startsWith(keyId(KEY) + ":");
        String before = sealedBeforePriv11(KEY, IBAN, "club-a", "member-a");
        assertThat(before).doesNotContain(":");
        assertThat(vault.resolve(Map.of("ibanEncrypted", before), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.same(Map.of("ibanEncrypted", before), Map.of("ibanEncrypted", sealed), "club-a", "member-a")).isTrue();
        assertThat(vault.sealedWithCurrentKey(sealed)).isTrue();
        assertThat(vault.sealedWithCurrentKey(before)).isFalse();
        assertThat(vault.sealedWithCurrentKey(TestBankVaults.of(OTHER_KEY).encrypt(IBAN, "club-a", "member-a"))).isFalse();
        assertThat(vault.sealedWithCurrentKey(null)).isFalse();
        // The prefix binds nothing but the key: the associated data is still the member's, and another key id is not read.
        assertThatThrownBy(() -> vault.decrypt(sealed, "club-a", "member-b")).isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed");
        assertThatThrownBy(() -> vault.decrypt("0badc0de" + sealed.substring(8), "club-a", "member-a")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Bank account decryption failed");
    }

    /**
     * PRIV-11: during a rotation `BILLING_BANK_KEY_PREVIOUS` reads what it sealed, with or without a key id, and never seals;
     * {@link BankAccountVault#reseal} seals the same IBAN with the new key, which alone reads it afterwards.
     */
    @Test void PRIV_11_duringARotationThePreviousKeyReadsItsValuesAndResealUsesTheNewKey() throws Exception {
        var old = TestBankVaults.of(KEY);
        String prefixed = old.encrypt(IBAN, "club-a", "member-a"), before = sealedBeforePriv11(KEY, IBAN, "club-a", "member-a");
        var rotating = TestBankVaults.of(OTHER_KEY, KEY);
        assertThat(rotating.keyId()).isEqualTo(keyId(OTHER_KEY));
        assertThat(rotating.previousKeyId()).isEqualTo(keyId(KEY));
        for (String stored : java.util.List.of(prefixed, before)) {
            assertThat(rotating.resolve(Map.of("ibanEncrypted", stored), "club-a", "member-a")).isEqualTo(IBAN);
            assertThat(rotating.sealedWithCurrentKey(stored)).isFalse();
            String resealed = rotating.reseal(stored, "club-a", "member-a");
            assertThat(resealed).startsWith(keyId(OTHER_KEY) + ":").doesNotContain(IBAN).isNotEqualTo(stored);
            assertThat(rotating.sealedWithCurrentKey(resealed)).isTrue();
            // Once the previous key is gone, the new key alone reads the resealed value and no longer the old one.
            var after = TestBankVaults.of(OTHER_KEY);
            assertThat(after.decrypt(resealed, "club-a", "member-a")).isEqualTo(IBAN);
            assertThatThrownBy(() -> after.decrypt(stored, "club-a", "member-a")).isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed");
            assertThatThrownBy(() -> after.reseal(stored, "club-a", "member-a")).isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed");
        }
        // The vault seals with the new key only, and a value of a third key is read by neither.
        assertThat(rotating.encrypt(IBAN, "club-a", "member-a")).startsWith(keyId(OTHER_KEY) + ":");
        assertThat((String) rotating.seal(IBAN, "club-a", "member-a").get("ibanEncrypted")).startsWith(keyId(OTHER_KEY) + ":");
        String third = TestBankVaults.of(THIRD_KEY).encrypt(IBAN, "club-a", "member-a");
        assertThatThrownBy(() -> rotating.reseal(third, "club-a", "member-a")).isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed");
        assertThatThrownBy(() -> rotating.reseal(sealedBeforePriv11(THIRD_KEY, IBAN, "club-a", "member-a"), "club-a", "member-a"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed").hasMessageNotContaining(IBAN);
        // The previous key reads with the member's associated data only.
        assertThatThrownBy(() -> rotating.decrypt(prefixed, "club-a", "member-b")).isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed");
    }

    /** PRIV-11: a previous key that is set must be usable and another key, in every profile, or the application does not start. */
    @Test void PRIV_11_aMalformedOrRepeatedPreviousKeyStopsTheApplication() {
        for (String profile : new String[]{"prod", "local"}) {
            var environment = new org.springframework.mock.env.MockEnvironment(); environment.setActiveProfiles(profile);
            for (String previous : new String[]{"%%%", Base64.getEncoder().encodeToString(new byte[16]), KEY}) {
                assertThatThrownBy(() -> new BankAccountVault(KEY, previous, environment)).as(profile + " " + previous).isInstanceOf(IllegalStateException.class)
                        .hasMessage("BILLING_BANK_KEY_PREVIOUS, when set, must be Base64 of 32 bytes and another key than BILLING_BANK_KEY")
                        .hasMessageNotContaining(KEY);
            }
            assertThat(new BankAccountVault(OTHER_KEY, KEY, environment).previousKeyId()).isEqualTo(TestBankVaults.of(KEY).keyId());
            assertThat(new BankAccountVault(OTHER_KEY, "", environment).previousKeyId()).isNull();
        }
        var local = new org.springframework.mock.env.MockEnvironment(); local.setActiveProfiles("local");
        assertThatThrownBy(() -> new BankAccountVault("", KEY, local)).isInstanceOf(IllegalStateException.class)
                .hasMessage("BILLING_BANK_KEY_PREVIOUS, when set, must be Base64 of 32 bytes and another key than BILLING_BANK_KEY");
    }
}

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
        String encrypted = new MigrationBankVault(new BankAccountVault(KEY)).encrypt(IBAN, "club-a", "member-a");
        var vault = new BankAccountVault(KEY);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "ibanEncrypted", encrypted, "ibanLast4", "1234"), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "sepa", Map.of("ibanEncrypted", encrypted)), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.decrypt(vault.encrypt(IBAN, "club-a", "member-b"), "club-a", "member-b")).isEqualTo(IBAN);
        assertThat(vault.encrypt(IBAN, "club-a", "member-a")).isNotEqualTo(vault.encrypt(IBAN, "club-a", "member-a")).doesNotContain("1234");
    }

    @Test void E43_aClearIbanIsReturnedAsStoredAndAMemberWithoutOneHasNone() {
        var vault = new BankAccountVault(KEY);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "iban", IBAN), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "sepa", Map.of("iban", IBAN)), "club-a", "member-a")).isEqualTo(IBAN);
        assertThat(vault.resolve(Map.of("type", "SEPA_DD", "iban", " ", "ibanLast4", "1234"), "club-a", "member-a")).isNull();
        assertThat(vault.resolve(Map.of("type", "MANUAL", "channel", "CASH"), "club-a", "member-a")).isNull();
        assertThat(vault.resolve(null, "club-a", "member-a")).isNull();
        // A clear IBAN needs no key: only the encrypted shape reads BILLING_BANK_KEY.
        assertThat(new BankAccountVault("").resolve(Map.of("iban", IBAN), "club-a", "member-a")).isEqualTo(IBAN);
    }

    @Test void E43_aWrongKeyAnotherMembersDataATamperedValueOrAMissingKeyFailWithoutLeakingAnything() {
        String encrypted = new BankAccountVault(KEY).encrypt(IBAN, "club-a", "member-a");
        for (var failure : java.util.List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> new BankAccountVault(OTHER_KEY).decrypt(encrypted, "club-a", "member-a"),
                () -> new BankAccountVault(KEY).decrypt(encrypted, "club-a", "member-b"),
                () -> new BankAccountVault(KEY).decrypt(encrypted, "club-b", "member-a"),
                () -> new BankAccountVault(KEY).decrypt(encrypted.substring(0, encrypted.length() - 4) + "AAAA", "club-a", "member-a"),
                () -> new BankAccountVault(KEY).decrypt("not base64 !", "club-a", "member-a"),
                () -> new BankAccountVault(KEY).decrypt(Base64.getEncoder().encodeToString(new byte[12]), "club-a", "member-a"))) {
            assertThatThrownBy(failure).isInstanceOf(IllegalStateException.class).hasMessage("Bank account decryption failed");
        }
        assertThatThrownBy(() -> new BankAccountVault("").encrypt(IBAN, "club-a", "member-a")).isInstanceOf(IllegalStateException.class)
                .hasMessage("BILLING_BANK_KEY must be 32 bytes");
        assertThatThrownBy(() -> new BankAccountVault("%%%").resolve(Map.of("ibanEncrypted", encrypted), "club-a", "member-a"))
                .isInstanceOf(IllegalStateException.class).hasMessage("BILLING_BANK_KEY is not Base64");
        assertThatThrownBy(() -> new BankAccountVault(Base64.getEncoder().encodeToString(new byte[16])).decrypt(encrypted, "club-a", "member-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(IBAN);
        // E85: the migration refuses bank data without a usable BILLING_BANK_KEY, before writing anything.
        assertThat(new BankAccountVault(KEY).configured()).isTrue();
        for (String unusable : java.util.List.of("", "%%%", Base64.getEncoder().encodeToString(new byte[16]))) {
            assertThat(new BankAccountVault(unusable).configured()).as(unusable).isFalse();
        }
    }
}

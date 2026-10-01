package com.agilityhub.core.payments.application;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ruling E43 (INC-31): one vault for the members' IBANs of both origins. A signup or D10 member stores `iban` in clear; a
 * migrated one stores `ibanEncrypted` + `ibanLast4` (S18), which the migration writes through this vault
 * ({@code MigrationBankVault}): ruling E85 makes `BILLING_BANK_KEY` (Base64 of 32 bytes, environment only) the only bank key.
 * The cipher is {@link AesGcmCipher} with the associated data `"{clubId}:{memberId}"`. {@link #resolve} is the only way to the
 * full IBAN and only the SEPA writer calls it (E8-T03, R-12-12): every other reader keeps `maskedAccount` or `ibanLast4`.
 * E11-T02 encrypts the clear ones before the launch (A37). A missing or malformed key, or a value that does not decrypt under
 * it, is a server misconfiguration ({@link IllegalStateException}), never a business error.
 */
@Component
public class BankAccountVault {
    private final AesGcmCipher cipher;
    public BankAccountVault(@Value("${core.billing.bank-key:}") String key) { this.cipher = new AesGcmCipher(key, "BILLING_BANK_KEY", "Bank account"); }

    /** Whether `BILLING_BANK_KEY` is usable (the migration refuses to import bank data without it, E85). */
    public boolean configured() { return cipher.configured(); }

    /**
     * The full IBAN of a member's `paymentMethod`: `iban` (or `sepa.iban`) when stored in clear, else `ibanEncrypted` (or
     * `sepa.ibanEncrypted`) decrypted with the member's club and id as associated data; null when it has neither.
     */
    public String resolve(Map<String, Object> paymentMethod, String clubId, String memberId) {
        if (paymentMethod == null) { return null; }
        var sepa = paymentMethod.get("sepa") instanceof Map<?, ?> nested ? nested : paymentMethod;
        String clear = text(sepa.get("iban"));
        if (clear == null) { clear = text(paymentMethod.get("iban")); }
        if (clear != null) { return clear; }
        String encrypted = text(sepa.get("ibanEncrypted"));
        if (encrypted == null) { encrypted = text(paymentMethod.get("ibanEncrypted")); }
        return encrypted == null ? null : decrypt(encrypted, clubId, memberId);
    }

    public String encrypt(String iban, String clubId, String memberId) { return cipher.encrypt(iban, clubId + ":" + memberId); }

    public String decrypt(String value, String clubId, String memberId) { return cipher.decrypt(value, clubId + ":" + memberId); }

    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
}

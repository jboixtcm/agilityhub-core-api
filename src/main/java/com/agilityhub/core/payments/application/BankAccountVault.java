package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.BankAccounts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ruling E43 (INC-31): one vault for the members' IBANs of both origins. Since E11-T02 (A37) a signup or D10 member's IBAN is
 * sealed here too ({@link BankAccounts#seal}: `ibanEncrypted` + `ibanLast4`), like a migrated one (S18), which the migration writes through this vault
 * ({@code MigrationBankVault}): ruling E85 makes `BILLING_BANK_KEY` (Base64 of 32 bytes, environment only) the only bank key.
 * The cipher is {@link AesGcmCipher} with the associated data `"{clubId}:{memberId}"`. {@link #resolve} is the only way to the
 * full IBAN and only the SEPA writer calls it (E8-T03, R-12-12): every other reader keeps `maskedAccount` or `ibanLast4`.
 * `census:seal-ibans` (E11-T02) seals the clear ones stored before. A missing or malformed key, or a value that does not decrypt under
 * it, is a server misconfiguration ({@link IllegalStateException}), never a business error.
 */
@Component
public class BankAccountVault implements BankAccounts {
    private final AesGcmCipher cipher;
    public BankAccountVault(@Value("${core.billing.bank-key:}") String key) { this.cipher = new AesGcmCipher(key, "BILLING_BANK_KEY", "Bank account"); }

    /** Whether `BILLING_BANK_KEY` is usable (the migration refuses to import bank data without it, E85). */
    @Override public boolean configured() { return cipher.configured(); }

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

    /** {@link BankAccounts#seal}: `{ibanEncrypted, ibanLast4}`, or `{iban}` only where `BILLING_BANK_KEY` is not set. */
    @Override public Map<String, Object> seal(String iban, String clubId, String memberId) {
        var stored = new LinkedHashMap<String, Object>();
        if (!configured()) { stored.put("iban", iban); return stored; }
        stored.put("ibanEncrypted", encrypt(iban, clubId, memberId));
        stored.put("ibanLast4", iban.substring(Math.max(0, iban.length() - 4)));
        return stored;
    }

    /** {@link BankAccounts#same}: both accounts resolved here, compared, and dropped. */
    @Override public boolean same(Map<String, Object> first, Map<String, Object> second, String clubId, String memberId) {
        String a = resolve(first, clubId, memberId), b = resolve(second, clubId, memberId);
        return a != null && Objects.equals(a, b);
    }

    public String decrypt(String value, String clubId, String memberId) { return cipher.decrypt(value, clubId + ":" + memberId); }

    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
}

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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BankAccountVault.class);
    /** The profiles where an IBAN may still be stored in clear without `BILLING_BANK_KEY`: the local stack and the tests. */
    static final org.springframework.core.env.Profiles CLEAR_ALLOWED = org.springframework.core.env.Profiles.of("local", "test");
    private final AesGcmCipher cipher;
    private final boolean clearAllowed;
    /** A vault of the local stack or a test: without a key, {@link #seal} keeps the IBAN in clear (with a WARN). */
    public BankAccountVault(String key) { this(key, true); }
    @org.springframework.beans.factory.annotation.Autowired
    public BankAccountVault(@Value("${core.billing.bank-key:}") String key, org.springframework.core.env.Environment environment) {
        this(key, environment.acceptsProfiles(CLEAR_ALLOWED));
    }
    BankAccountVault(String key, boolean clearAllowed) {
        this.cipher = new AesGcmCipher(key, "BILLING_BANK_KEY", "Bank account"); this.clearAllowed = clearAllowed;
    }

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

    /**
     * {@link BankAccounts#seal}: `{ibanEncrypted, ibanLast4}`. Without `BILLING_BANK_KEY`, E11-T02 round 2 (review #8): the local
     * stack and the tests keep `{iban}` in clear with a WARN; any other profile (staging, prod, none) refuses, a server
     * misconfiguration, so that a deployment without the key never quietly undoes INC-31.
     */
    @Override public Map<String, Object> seal(String iban, String clubId, String memberId) {
        var stored = new LinkedHashMap<String, Object>();
        if (!configured()) {
            if (!clearAllowed) { throw new IllegalStateException("BILLING_BANK_KEY is required to store a bank account outside the local profile"); }
            LOG.warn("BILLING_BANK_KEY is not set: a bank account is stored in clear (local profile only) clubId={}", clubId);
            stored.put("iban", iban); return stored;
        }
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

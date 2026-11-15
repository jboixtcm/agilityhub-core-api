package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.BankAccounts;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
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
 * it, is a server misconfiguration (an {@link IllegalStateException}; {@link #seal}'s refusal is `500 INTERNAL_ERROR`), never a
 * business error.
 *
 * <p>PRIV-11 (owner's decision of 13-11-2026), the key rotation: every value sealed now starts with the id of the key that sealed
 * it, `{keyId}:` ({@link AesGcmCipher#keyId}; a Base64 ciphertext has no `:`), and is read with that key: `BILLING_BANK_KEY`, or
 * during a rotation `BILLING_BANK_KEY_PREVIOUS`. A value sealed before PRIV-11 has no prefix and is read with the current key,
 * then the previous one. `census:rekey-ibans` seals every stored IBAN again with the current key ({@link #reseal}), after which
 * the previous key can go (docs/DEPLOY.md, «Keys and provider rotation»).
 */
@Component
public class BankAccountVault implements BankAccounts {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BankAccountVault.class);
    /** The profiles where an IBAN may still be stored in clear without `BILLING_BANK_KEY`: the local stack and the tests. */
    static final org.springframework.core.env.Profiles CLEAR_ALLOWED = org.springframework.core.env.Profiles.of("local", "test");
    /** PRIV-11: `{keyId}:{Base64}`, a value sealed with a named key; a value without it was sealed before PRIV-11. */
    private static final java.util.regex.Pattern PREFIXED = java.util.regex.Pattern.compile("([0-9a-f]{8}):(.+)", java.util.regex.Pattern.DOTALL);
    private final AesGcmCipher cipher;
    /** PRIV-11: `BILLING_BANK_KEY_PREVIOUS` during a rotation, read-only; null when it is not set. */
    private final AesGcmCipher previous;
    private final boolean clearAllowed;
    /**
     * The application's vault. E11-T11 (review #4 of E11-T02 round 2): outside `local` and `test` a missing or unusable
     * `BILLING_BANK_KEY` stops the application at startup, before any member types an IBAN that could not be stored.
     */
    public BankAccountVault(String key, org.springframework.core.env.Environment environment) { this(key, "", environment); }
    /**
     * PRIV-11: also `BILLING_BANK_KEY_PREVIOUS`, the key a rotation replaces. When it is set, in any profile, it must be usable
     * and another key than `BILLING_BANK_KEY`, or the application does not start.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public BankAccountVault(@Value("${core.billing.bank-key:}") String key, @Value("${core.billing.bank-key-previous:}") String previousKey,
            org.springframework.core.env.Environment environment) {
        this(key, previousKey, environment.acceptsProfiles(CLEAR_ALLOWED));
        if (!clearAllowed && !configured()) {
            throw new IllegalStateException("BILLING_BANK_KEY (Base64 of 32 bytes) is required outside the local and test profiles");
        }
        if (previous != null && (!previous.configured() || !configured() || previous.keyId().equals(cipher.keyId()))) {
            throw new IllegalStateException("BILLING_BANK_KEY_PREVIOUS, when set, must be Base64 of 32 bytes and another key than BILLING_BANK_KEY");
        }
    }
    /** Tests only (their fixture `TestBankVaults`): `clearAllowed` keeps an IBAN in clear without a key, as the local stack does. */
    BankAccountVault(String key, boolean clearAllowed) { this(key, "", clearAllowed); }
    /** Tests only (`TestBankVaults`): with a previous key, as during a rotation (PRIV-11). */
    BankAccountVault(String key, String previousKey, boolean clearAllowed) {
        this.cipher = new AesGcmCipher(key, "BILLING_BANK_KEY", "Bank account");
        this.previous = previousKey == null || previousKey.isBlank() ? null : new AesGcmCipher(previousKey, "BILLING_BANK_KEY_PREVIOUS", "Bank account");
        this.clearAllowed = clearAllowed;
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

    /** PRIV-11: sealed with `BILLING_BANK_KEY` and prefixed with its id, `{keyId}:{Base64}`. */
    public String encrypt(String iban, String clubId, String memberId) { return cipher.keyId() + ":" + cipher.encrypt(iban, clubId + ":" + memberId); }

    /**
     * {@link BankAccounts#seal}: `{ibanEncrypted, ibanLast4}`. Without `BILLING_BANK_KEY`, E11-T02 round 2 (review #8): the local
     * stack and the tests keep `{iban}` in clear with a WARN; any other profile (staging, prod) does not start without it, and a
     * vault that still has no key refuses with `500 INTERNAL_ERROR` (AGENTS rule 9, E11-T11), a server misconfiguration, so that
     * a deployment without the key never quietly undoes INC-31. A run with no active profile is `local` by design
     * (`spring.profiles.default: local`, ruling E139): it starts and keeps the IBAN in clear with the WARN.
     */
    @Override public Map<String, Object> seal(String iban, String clubId, String memberId) {
        var stored = new LinkedHashMap<String, Object>();
        if (!configured()) {
            if (!clearAllowed) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR, new IllegalStateException("BILLING_BANK_KEY is required to store a bank account outside the local profile"));
            }
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

    /**
     * PRIV-11: a prefixed value with the key its id names (the current or the previous one; another id fails); a value sealed before
     * PRIV-11 (no prefix) with the current key, then the previous one. A failure never names the value nor a key.
     */
    public String decrypt(String value, String clubId, String memberId) {
        String associatedData = clubId + ":" + memberId;
        var prefixed = PREFIXED.matcher(value);
        if (prefixed.matches()) {
            String id = prefixed.group(1);
            var key = id.equals(cipher.keyId()) ? cipher : previous != null && id.equals(previous.keyId()) ? previous : null;
            if (key == null) { throw new IllegalStateException("Bank account decryption failed"); }
            return key.decrypt(prefixed.group(2), associatedData);
        }
        try { return cipher.decrypt(value, associatedData); }
        catch (IllegalStateException failure) {
            if (previous == null) { throw failure; }
            return previous.decrypt(value, associatedData);
        }
    }

    /** PRIV-11: the id of `BILLING_BANK_KEY`, the prefix of every value sealed now. */
    @Override public String keyId() { return cipher.keyId(); }

    /** PRIV-11: the id of `BILLING_BANK_KEY_PREVIOUS`, or null when it is not set. */
    @Override public String previousKeyId() { return previous == null ? null : previous.keyId(); }

    /** PRIV-11: whether a stored `ibanEncrypted` is sealed with `BILLING_BANK_KEY` (it carries its id). */
    @Override public boolean sealedWithCurrentKey(String ibanEncrypted) {
        var prefixed = PREFIXED.matcher(ibanEncrypted == null ? "" : ibanEncrypted);
        return prefixed.matches() && prefixed.group(1).equals(cipher.keyId());
    }

    /**
     * PRIV-11, {@link BankAccounts#reseal}: the member's stored `ibanEncrypted` sealed again with `BILLING_BANK_KEY`, read with
     * whichever configured key sealed it. The IBAN stays inside the vault; an unreadable value is an {@link IllegalStateException}.
     */
    @Override public String reseal(String ibanEncrypted, String clubId, String memberId) {
        return encrypt(decrypt(ibanEncrypted, clubId, memberId), clubId, memberId);
    }

    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
}

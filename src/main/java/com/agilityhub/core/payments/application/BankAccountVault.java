package com.agilityhub.core.payments.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ruling E43 (INC-31): one vault for the members' IBANs of both origins. A signup or D10 member stores `iban` in clear; a
 * migrated one stores `ibanEncrypted` + `ibanLast4` (S18, {@code MigrationBankVault}). This vault uses the same cipher and
 * the same associated data (AES-256-GCM, a 12-byte random nonce prefixed to the ciphertext, Base64, AAD
 * `"{clubId}:{memberId}"`) with the key `BILLING_BANK_KEY` (Base64 of 32 bytes, environment only). {@link #resolve} is the
 * only way to the full IBAN and only the SEPA writer calls it (E8-T03, R-12-12): every other reader keeps `maskedAccount`
 * or `ibanLast4`. E11-T02 encrypts the clear ones before the launch (A37). A missing or malformed key, or a value that does
 * not decrypt under it, is a server misconfiguration ({@link IllegalStateException}), never a business error.
 */
@Component
public class BankAccountVault {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final String key;
    private final SecureRandom random = new SecureRandom();
    public BankAccountVault(@Value("${core.billing.bank-key:}") String key) { this.key = key; }

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

    public String encrypt(String iban, String clubId, String memberId) {
        try {
            byte[] nonce = new byte[NONCE_BYTES]; random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, clubId, memberId);
            byte[] encrypted = cipher.doFinal(iban.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Bank account encryption failed"); }
    }

    public String decrypt(String value, String clubId, String memberId) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length <= NONCE_BYTES) { throw new IllegalArgumentException(); }
            var buffer = ByteBuffer.wrap(bytes);
            byte[] nonce = new byte[NONCE_BYTES]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, clubId, memberId).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            // Never the value nor the key in the message: it ends up in the logs.
            throw new IllegalStateException("Bank account decryption failed");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String clubId, String memberId) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD((clubId + ":" + memberId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private byte[] key() {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(key); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("BILLING_BANK_KEY is not Base64"); }
        if (bytes.length != 32) { throw new IllegalStateException("BILLING_BANK_KEY must be 32 bytes"); }
        return bytes;
    }

    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
}

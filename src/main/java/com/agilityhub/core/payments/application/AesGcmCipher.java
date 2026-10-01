package com.agilityhub.core.payments.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cipher of the billing vaults (ruling E43, E8-T01): AES-256-GCM, a 12-byte random nonce prefixed to the ciphertext, Base64,
 * and associated data that binds a value to the place it is stored (a member's IBAN to the member, a provider secret to the
 * club and the field). The key is the Base64 of 32 bytes from the environment. A missing or malformed key, or a value that does
 * not decrypt under it, is a server misconfiguration ({@link IllegalStateException}): the message names the key variable and
 * never carries the value nor the key.
 */
final class AesGcmCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final String key;
    private final String keyName;
    private final String subject;
    private final SecureRandom random = new SecureRandom();

    /** {@code keyName} is the environment variable ({@code BILLING_BANK_KEY}); {@code subject} names the value in the messages. */
    AesGcmCipher(String key, String keyName, String subject) { this.key = key; this.keyName = keyName; this.subject = subject; }

    /** Whether the key is usable (Base64 of 32 bytes). */
    boolean configured() {
        try { key(); return true; }
        catch (IllegalStateException missing) { return false; }
    }

    String encrypt(String plain, String associatedData) {
        byte[] secretKey = key();
        try {
            byte[] nonce = new byte[NONCE_BYTES]; random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, secretKey, nonce, associatedData).doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) { throw new IllegalStateException(subject + " encryption failed"); }
    }

    String decrypt(String value, String associatedData) {
        byte[] secretKey = key();
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length <= NONCE_BYTES) { throw new IllegalArgumentException(); }
            var buffer = ByteBuffer.wrap(bytes);
            byte[] nonce = new byte[NONCE_BYTES]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, secretKey, nonce, associatedData).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            // Never the value nor the key in the message: it ends up in the logs.
            throw new IllegalStateException(subject + " decryption failed");
        }
    }

    private static Cipher cipher(int mode, byte[] secretKey, byte[] nonce, String associatedData) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(secretKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private byte[] key() {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(key == null ? "" : key); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException(keyName + " is not Base64"); }
        if (bytes.length != 32) { throw new IllegalStateException(keyName + " must be 32 bytes"); }
        return bytes;
    }
}

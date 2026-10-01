package com.agilityhub.core.payments.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADR-009, S12 §3: a club's payment-provider secrets (`paymentProviders.STRIPE.secretKeyEnc`, `webhookSecretEnc`) are stored
 * encrypted with a server key from the environment, `BILLING_SECRETS_KEY` (Base64 of 32 bytes; E8-T04 step 2 names it: neither
 * the bank key nor any other key encrypts them). The cipher is {@link AesGcmCipher}, the bank vault's; the associated data
 * `"{clubId}:{provider}.{field}"` binds a value to its club and field, so a ciphertext copied to another club or field does not
 * decrypt. A secret is decrypted only where it is used (the webhook's signature check, E8-T01 round 2; the provider calls of
 * E8-T04), never returned, logged or audited.
 */
@Component
public class ProviderSecretVault {
    private final AesGcmCipher cipher;
    public ProviderSecretVault(@Value("${core.billing.secrets-key:}") String key) { this.cipher = new AesGcmCipher(key, "BILLING_SECRETS_KEY", "Provider secret"); }

    public String encrypt(String secret, String clubId, String provider, String field) { return cipher.encrypt(secret, associatedData(clubId, provider, field)); }

    public String decrypt(String value, String clubId, String provider, String field) { return cipher.decrypt(value, associatedData(clubId, provider, field)); }

    private static String associatedData(String clubId, String provider, String field) { return clubId + ":" + provider + "." + field; }
}

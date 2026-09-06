package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/** A single compare-and-set key ring keeps all API and CLI processes on the same signing key. */
@Service
public class SigningKeys {
    private final SigningKeyRepository repository;
    private final Clock clock;
    private final SecretKeySpec master;
    private SigningKeyRing ephemeral;
    public SigningKeys(SigningKeyRepository repository, Clock clock, Environment environment,
                       @Value("${core.oidc.master-key:}") String value) {
        this.repository = repository; this.clock = clock;
        boolean local = environment.acceptsProfiles(Profiles.of("local", "test")) && !environment.acceptsProfiles(Profiles.of("staging", "prod"));
        if (value.isBlank() && !local) { throw new IllegalStateException("OIDC_MASTER_KEY is required outside local/test"); }
        byte[] bytes = value.isBlank() ? new byte[32] : Base64.getDecoder().decode(value);
        if (value.isBlank()) { new SecureRandom().nextBytes(bytes); }
        if (bytes.length != 32) { throw new IllegalArgumentException("OIDC_MASTER_KEY must encode exactly 32 bytes"); }
        master = new SecretKeySpec(bytes, "AES");
        if (value.isBlank()) { ephemeral = initial(); }
        else if (repository.findById("active").isEmpty()) { repository.initialize(initial()); }
        keys(); // Fail startup when the supplied master key cannot decrypt the persisted ring.
    }
    private SigningKeyRing initial() {
        return new SigningKeyRing("active", encrypt(new JWKSet(List.of(generate(), generate())).toString(false)), 0, clock.instant());
    }
    private SigningKeyRing ring() { return ephemeral != null ? ephemeral : repository.findById("active").orElseThrow(); }
    public List<JWK> keys() {
        try { return JWKSet.parse(decrypt(ring().encryptedKeys())).getKeys(); }
        catch (java.text.ParseException failure) { throw new IllegalStateException("Invalid signing key ring"); }
    }
    public boolean persistent() { return ephemeral == null; }
    public RSAKey current() { return (RSAKey) keys().getFirst(); }
    public JWKSet publicKeys() { return new JWKSet(keys().stream().map(JWK::toPublicJWK).toList()); }
    public synchronized void rotate() {
        var old = ring();
        // Never retire a key while one of its access/ID tokens can still be valid.
        if (old.generation() > 0 && clock.instant().isBefore(old.rotatedAt().plus(TokenService.ACCESS_TTL))) {
            throw new ApiException(ErrorCode.INVALID_STATE, Map.of("reason", "Previous signing key still in use"));
        }
        var next = new SigningKeyRing("active", encrypt(new JWKSet(List.of(generate(), keys().getFirst())).toString(false)),
                old.generation() + 1, clock.instant());
        if (ephemeral != null) { ephemeral = next; }
        else if (!repository.rotate(next)) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
    private static RSAKey generate() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE).algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).generate(); }
        catch (com.nimbusds.jose.JOSEException failure) { throw new IllegalStateException("Could not generate signing key"); }
    }
    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Could not encrypt signing keys"); }
    }
    private String decrypt(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            return new String(cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(bytes, 0, 12))
                    .doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException failure) { throw new IllegalStateException("Could not decrypt signing keys"); }
    }
    private Cipher cipher(int mode, byte[] iv) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, master, new GCMParameterSpec(128, iv));
        cipher.updateAAD("agilityhub:signing_keys:active:v1".getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}

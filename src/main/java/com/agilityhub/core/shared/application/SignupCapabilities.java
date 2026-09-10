package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Server-key capabilities and encrypted anonymous replay responses. No capability is stored in clear. */
@Component
public class SignupCapabilities {
    private final byte[] key;
    private final Clock clock;
    public SignupCapabilities(Environment env, Clock clock) {
        this.clock = clock;
        String value = env.getProperty("signup.capability-key", "");
        if (value.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("staging", "prod"))) { throw new IllegalStateException("Missing signup.capability-key"); }
            key = new SecureRandom().generateSeed(32);
        } else {
            try { key = Base64.getDecoder().decode(value); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid signup.capability-key"); }
            if (key.length != 32) { throw new IllegalStateException("signup.capability-key must contain 32 bytes"); }
        }
    }
    public String issue(String memberId) {
        String payload = TenantContext.require() + "|" + memberId + "|" + clock.instant().plus(Duration.ofHours(24)).getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }
    public void require(String memberId, String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII))) { throw new IllegalArgumentException(); }
            String[] values = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 3 || !TenantContext.require().equals(values[0]) || !memberId.equals(values[1])
                    || Long.parseLong(values[2]) <= clock.instant().getEpochSecond()) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
    }
    public String fingerprint(String value) { return sign("fingerprint|" + value); }
    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    public byte[] seal(byte[] response, String scope) {
        byte[] nonce = new SecureRandom().generateSeed(12);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, response, scope, nonce);
        return ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
    }
    public byte[] open(byte[] response, String scope) {
        if (response == null || response.length < 28) { throw new ApiException(ErrorCode.INVALID_STATE); }
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(response,12,response.length),scope,Arrays.copyOf(response,12));
    }
    private byte[] crypt(int mode, byte[] bytes, String scope, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8)); return cipher.doFinal(bytes);
        } catch (GeneralSecurityException failure) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }
}

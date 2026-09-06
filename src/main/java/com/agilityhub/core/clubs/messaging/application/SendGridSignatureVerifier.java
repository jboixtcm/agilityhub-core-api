package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class SendGridSignatureVerifier {
    private final PublicKey publicKey;
    public SendGridSignatureVerifier(String key) {
        if (key.isBlank()) { publicKey = null; return; }
        try {
            String encoded = key.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception invalid) { throw new IllegalStateException("SENDGRID_WEBHOOK_PUBLIC_KEY must be a valid ECDSA public key"); }
    }
    public void verify(byte[] body, String timestamp, String signature) {
        try {
            if (publicKey == null || timestamp == null || !timestamp.matches("[0-9]{1,20}") || signature == null) { throw new IllegalArgumentException(); }
            var verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(body);
            if (verifier.verify(Base64.getDecoder().decode(signature))) { return; }
        } catch (Exception invalid) {
            // Fail closed, without logging request data or signature material.
        }
        throw new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
    }
}

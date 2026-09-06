package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.shared.domain.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SendGridSignatureVerifierTest {
    @Test void T_11_22_publicKeyFormatsAndMissingMalformedInputs() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(256); var keys = generator.generateKeyPair();
        var body = "[{\"event\":\"bounce\",\"text\":\"català\"}]".getBytes(StandardCharsets.UTF_8);
        var signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(keys.getPrivate());
        signature.update("123".getBytes(StandardCharsets.UTF_8)); signature.update(body);
        String encodedSignature = Base64.getEncoder().encodeToString(signature.sign());
        String encodedKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        for (String key : java.util.List.of(encodedKey, "-----BEGIN PUBLIC KEY-----\n" + encodedKey + "\n-----END PUBLIC KEY-----")) {
            var verifier = new SendGridSignatureVerifier(key); verifier.verify(body, "123", encodedSignature);
            for (String timestamp : java.util.Arrays.asList(null, "not-a-timestamp", "")) {
                assertThatThrownBy(() -> verifier.verify(body, timestamp, encodedSignature)).isInstanceOf(ApiException.class);
            }
            assertThatThrownBy(() -> verifier.verify(body, "123", null)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> new SendGridSignatureVerifier("invalid")).hasMessage("SENDGRID_WEBHOOK_PUBLIC_KEY must be a valid ECDSA public key");
        assertThatThrownBy(() -> new SendGridSignatureVerifier("").verify(body, "123", encodedSignature)).isInstanceOf(ApiException.class);
    }
}

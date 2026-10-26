package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Brevo supports a configured bearer secret, not SendGrid's ECDSA payload signature. HTTPS is mandatory at ingress. */
public final class BrevoWebhookAuthentication {
    private final byte[] expected;
    public BrevoWebhookAuthentication(String token) {
        expected = token.isBlank() ? null : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }
    public void verify(String authorization) {
        if (expected == null || authorization == null || authorization.length() > 4096
                || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
    }
}

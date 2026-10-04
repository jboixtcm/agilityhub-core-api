package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

/** Parsing is called only after the endpoint authenticated the raw bytes with its injected clock. */
public final class PaymentWebhookParser {
    private PaymentWebhookParser() { }
    public static PaymentProvider.WebhookEvent authenticate(String payload, String signature, String secret, java.time.Clock clock) {
        if (secret == null || !StripeWebhookSignatures.valid(signature, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), secret, clock.instant())) {
            throw new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        return parse(payload, signature, secret);
    }
    public static PaymentProvider.WebhookEvent parse(String payload, String signature, String secret) {
        try {
            var node = new ObjectMapper().readTree(payload);
            if (!node.path("id").isTextual() || !node.path("type").isTextual() || !node.path("data").path("object").isObject()) {
                throw new IllegalArgumentException();
            }
            return new PaymentProvider.WebhookEvent(node.path("id").asText(), node.path("type").asText(),
                    Instant.ofEpochSecond(node.path("created").asLong()), node.path("data").path("object"));
        } catch (Exception invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
}

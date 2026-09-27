package com.agilityhub.core.clubs.messaging.application.integrations;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;

/**
 * S11 §6 `interface PushSender { PushResult send(PushSubscription s, PushPayload p); }`: `WebPushSender` (VAPID,
 * `aes128gcm`) and `FakePushSender` (profiles `test`/`local` without VAPID keys). Never throws for a provider failure.
 */
public interface PushSender {
    PushResult send(PushSubscription subscription, PushPayload payload);
    /** The VAPID public key the browsers subscribe with (`GET /branding.pushPublicKey`, S02); `null` without one. */
    String publicKey();
}

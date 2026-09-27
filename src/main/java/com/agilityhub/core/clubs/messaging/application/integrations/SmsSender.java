package com.agilityhub.core.clubs.messaging.application.integrations;

/**
 * S11 §6 `interface SmsSender { SendResult send(SmsMessage m); }`: `TwilioSmsSender` (Messages API), `LogSmsSender`
 * (profile `local`) and `FakeSmsSender` (tests), selected like the `EmailSender`. Implementations never throw for a
 * provider or transport failure: they answer a retryable or a final {@link SendResult}.
 */
public interface SmsSender {
    SendResult send(SmsMessage message);
}

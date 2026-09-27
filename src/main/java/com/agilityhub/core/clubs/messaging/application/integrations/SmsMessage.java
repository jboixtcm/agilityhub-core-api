package com.agilityhub.core.clubs.messaging.application.integrations;

import java.util.Map;
import java.util.Objects;

/**
 * One SMS (R-11-06): the E.164 destination, the final text (transliterated and cut by `SmsText`), the sender id
 * (`messaging.sms.senderId`) and provider tags (`clubId`, `notificationId`). {@link #toString()} never prints the phone
 * or the text (R-14-18).
 */
public record SmsMessage(String to, String body, String senderId, Map<String, String> tags) {
    public SmsMessage {
        Objects.requireNonNull(to); Objects.requireNonNull(body);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
    @Override public String toString() { return "SmsMessage[redacted]"; }
}

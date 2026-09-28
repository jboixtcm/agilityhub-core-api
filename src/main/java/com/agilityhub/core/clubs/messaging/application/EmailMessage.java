package com.agilityhub.core.clubs.messaging.application;

import java.util.Locale;
import java.util.Map;

/**
 * One e-mail. `headers` carries the extra MIME headers of the engine's mails (R-11-08: `List-Unsubscribe` only on
 * `CLUB_NEWS`, pointing at the club app's unsubscribe page); the E1 SYSTEM mails have none.
 */
public record EmailMessage(String to, String subject, String html, String text, Address from,
                           String replyTo, Locale locale, Map<String, String> tags, Map<String, String> headers) {
    public EmailMessage { tags = Map.copyOf(tags); headers = headers == null ? Map.of() : Map.copyOf(headers); }
    public EmailMessage(String to, String subject, String html, String text, Address from, String replyTo, Locale locale, Map<String, String> tags) {
        this(to, subject, html, text, from, replyTo, locale, tags, Map.of());
    }
    public record Address(String email, String name) { }
    // Identity emails contain credentials; never include their content in diagnostic output.
    @Override public String toString() { return "EmailMessage[redacted]"; }
}

package com.agilityhub.core.clubs.messaging.application;

import java.util.Locale;
import java.util.Map;

public record EmailMessage(String to, String subject, String html, String text, Address from,
                           String replyTo, Locale locale, Map<String, String> tags) {
    public EmailMessage { tags = Map.copyOf(tags); }
    public record Address(String email, String name) { }
    // Identity emails contain credentials; never include their content in diagnostic output.
    @Override public String toString() { return "EmailMessage[redacted]"; }
}

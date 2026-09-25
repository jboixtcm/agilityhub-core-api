package com.agilityhub.core.clubs.messaging.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory mailbox, wired only in the test profile. */
public final class FakeEmailSender implements EmailSender {
    private final List<EmailMessage> mailbox = new CopyOnWriteArrayList<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    @Override public SendResult send(EmailMessage message) {
        if (failing.remove(message.to().toLowerCase(Locale.ROOT))) { throw new IllegalStateException("Fictional transient provider failure"); }
        mailbox.add(message);
        return SendResult.sent("fake-" + java.util.UUID.randomUUID());
    }
    /** The next message to {@code email} throws, as a provider outage would (E3-T12: a delivery retried by the outbox). */
    public void failNextTo(String email) { failing.add(email.toLowerCase(Locale.ROOT)); }
    public List<EmailMessage> messages() { return List.copyOf(mailbox); }
    public EmailMessage lastTo(String email) {
        return mailbox.reversed().stream().filter(message -> message.to().equalsIgnoreCase(email)).findFirst().orElseThrow();
    }
    public void clear() { mailbox.clear(); failing.clear(); }
}

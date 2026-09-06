package com.agilityhub.core.clubs.messaging.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory mailbox, wired only in the test profile. */
public final class FakeEmailSender implements EmailSender {
    private final List<EmailMessage> mailbox = new CopyOnWriteArrayList<>();
    @Override public SendResult send(EmailMessage message) {
        mailbox.add(message);
        return SendResult.sent("fake-" + java.util.UUID.randomUUID());
    }
    public List<EmailMessage> messages() { return List.copyOf(mailbox); }
    public EmailMessage lastTo(String email) {
        return mailbox.reversed().stream().filter(message -> message.to().equalsIgnoreCase(email)).findFirst().orElseThrow();
    }
    public void clear() { mailbox.clear(); }
}

package com.agilityhub.core.clubs.messaging.application;

/** Internal review mode: no provider, mailbox, message log, or delayed send. */
public final class DisabledEmailSender implements EmailSender {
    public static final String DISABLED = "Email delivery disabled for internal review";
    @Override public boolean deliveryDisabled() { return true; }
    @Override public SendResult send(EmailMessage message) { return SendResult.failed(DISABLED); }
}

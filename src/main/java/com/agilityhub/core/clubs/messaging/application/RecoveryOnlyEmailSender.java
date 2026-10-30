package com.agilityhub.core.clubs.messaging.application;

import java.util.Objects;

/** Password recovery is explicitly enabled while ordinary notifications remain terminal skips. */
public final class RecoveryOnlyEmailSender implements EmailSender {
    private final EmailSender recovery;
    public RecoveryOnlyEmailSender(EmailSender recovery) { this.recovery = Objects.requireNonNull(recovery); }
    @Override public boolean deliveryDisabled() { return true; }
    @Override public SendResult send(EmailMessage message) { return SendResult.failed(DisabledEmailSender.DISABLED); }
    @Override public EmailSender forPasswordRecovery() { return recovery; }
}

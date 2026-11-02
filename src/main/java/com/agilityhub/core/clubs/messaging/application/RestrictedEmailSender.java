package com.agilityhub.core.clubs.messaging.application;

/** Only explicitly enabled flows may use the provider while general delivery is disabled. */
public record RestrictedEmailSender(EmailSender provider, boolean recoveryEnabled, String signupClubId) implements EmailSender {
    @Override public boolean deliveryDisabled() { return true; }
    @Override public SendResult send(EmailMessage message) { return SendResult.failed(DisabledEmailSender.DISABLED); }
    @Override public EmailSender forPasswordRecovery() { return recoveryEnabled ? provider : this; }
    @Override public EmailSender forSignupApplicant(String clubId) {
        return !signupClubId.isBlank() && signupClubId.equals(clubId) ? provider : this;
    }
}

package com.agilityhub.core.clubs.messaging.application;

public interface EmailSender {
    SendResult send(EmailMessage message);
    /** A deployment-wide stop: callers persist a terminal skip instead of queuing email for later. */
    default boolean deliveryDisabled() { return false; }
    /** Only the identity RESET flow may select the separately enabled recovery transport. */
    default EmailSender forPasswordRecovery() { return this; }
    /** Receipt and rejection of an applicant’s own signup, selected by the event dispatcher. */
    default EmailSender forSignupApplicant(String clubId) { return this; }
    /** An administrator explicitly selected this member for a welcome and access email. */
    default EmailSender forAdminWelcome(String clubId) { return this; }
    record SendResult(boolean sent, String providerMessageId, String error) {
        public static SendResult sent(String id) { return new SendResult(true, id, null); }
        public static SendResult failed(String error) { return new SendResult(false, null, error); }
    }
}

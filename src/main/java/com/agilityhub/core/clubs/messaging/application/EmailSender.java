package com.agilityhub.core.clubs.messaging.application;

public interface EmailSender {
    SendResult send(EmailMessage message);
    /** A deployment-wide stop: callers persist a terminal skip instead of queuing email for later. */
    default boolean deliveryDisabled() { return false; }
    record SendResult(boolean sent, String providerMessageId, String error) {
        public static SendResult sent(String id) { return new SendResult(true, id, null); }
        public static SendResult failed(String error) { return new SendResult(false, null, error); }
    }
}

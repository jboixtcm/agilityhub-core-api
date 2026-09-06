package com.agilityhub.core.clubs.messaging.application;

public interface EmailSender {
    SendResult send(EmailMessage message);
    record SendResult(boolean sent, String providerMessageId, String error) {
        public static SendResult sent(String id) { return new SendResult(true, id, null); }
        public static SendResult failed(String error) { return new SendResult(false, null, error); }
    }
}

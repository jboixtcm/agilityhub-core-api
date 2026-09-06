package com.agilityhub.core.clubs.messaging.application;

import org.slf4j.LoggerFactory;

/** Local-only sink: no recipient addresses, links, subjects or credentials in logs. */
public final class LogEmailSender implements EmailSender {
    @Override public SendResult send(EmailMessage message) {
        String id = "local-" + java.util.UUID.randomUUID();
        LoggerFactory.getLogger(LogEmailSender.class).info("Local email accepted notificationId={}", message.tags().get("notificationId"));
        return SendResult.sent(id);
    }
}

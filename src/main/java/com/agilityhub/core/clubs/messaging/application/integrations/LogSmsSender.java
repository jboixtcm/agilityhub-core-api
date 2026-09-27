package com.agilityhub.core.clubs.messaging.application.integrations;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Profile `local`: every SMS is accepted and logged as a line without the phone or the text (R-14-18), only the length
 * and the notification id. `SENT` is the terminal SMS state at R1 (no delivery callback, S11 §13-10).
 */
public final class LogSmsSender implements SmsSender {
    private static final Logger LOG = LoggerFactory.getLogger(LogSmsSender.class);

    @Override public SendResult send(SmsMessage message) {
        String ref = "log-" + UUID.randomUUID();
        LOG.info("SMS accepted locally ref={} notificationId={} length={}", ref, message.tags().get("notificationId"), message.body().length());
        return SendResult.accepted(ref);
    }
}

package com.agilityhub.core.configuration;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/** Spring Boot structured logging: a closed field set excludes arbitrary MDC, headers, URLs and payloads. */
public final class PrivacyLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private final JsonWriter<ILoggingEvent> writer = JsonWriter.of(members -> {
        members.add("timestamp", event -> event.getInstant().toString());
        members.add("level", event -> event.getLevel().toString());
        members.add("logger", ILoggingEvent::getLoggerName);
        members.add("message", event -> LogPrivacy.scrub(event.getFormattedMessage()));
        for (String id : java.util.List.of("traceId", "clubId", "accountId")) {
            members.add(id, event -> LogPrivacy.identifier(event.getMDCPropertyMap().getOrDefault(id, "-")));
        }
        members.add("exception", event -> LogPrivacy.stack(event.getThrowableProxy()));
    });
    @Override public String format(ILoggingEvent event) { return writer.writeToString(event) + "\n"; }
}

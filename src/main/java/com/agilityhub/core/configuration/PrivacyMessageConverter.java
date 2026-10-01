package com.agilityhub.core.configuration;

/** The readable local console applies the same exclusions as production JSON. */
public final class PrivacyMessageConverter extends ch.qos.logback.classic.pattern.ClassicConverter {
    @Override public String convert(ch.qos.logback.classic.spi.ILoggingEvent event) {
        return LogPrivacy.scrub(event.getFormattedMessage());
    }
}

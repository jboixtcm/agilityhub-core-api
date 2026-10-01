package com.agilityhub.core.configuration;

public final class PrivacyExceptionConverter extends ch.qos.logback.classic.pattern.ThrowableHandlingConverter {
    @Override public String convert(ch.qos.logback.classic.spi.ILoggingEvent event) { return LogPrivacy.stack(event.getThrowableProxy()); }
}

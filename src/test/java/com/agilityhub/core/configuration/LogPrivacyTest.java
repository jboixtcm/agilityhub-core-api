package com.agilityhub.core.configuration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

class LogPrivacyTest {
    static final String EMAIL = "privacy@example.test", PHONE = "+34 600 123 456", IBAN = "ES00 0000 0000 0000 0000 0000";
    static final String IP = "203.0.113.123", IPV6 = "2001:db8::1234", BODY = "unlogged-request-body";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"+1 (202) 555-0100", "(202) 555-0100", "+34 600 00 00 00"})
    void T_14_30_phoneFormatsAreExcludedFromConsoleAndSentry(String phone) throws Exception {
        var log = new LoggingEvent(); log.setTimeStamp(1790000000000L);
        log.setLevel(Level.INFO); log.setLoggerName("fixture"); log.setMessage("phone=" + phone);
        log.setMDCPropertyMap(Map.of());
        String output = new ObjectMapper().readTree(new PrivacyLogFormatter().format(log)).path("message").asText();
        assertThat(output).doesNotContain(phone, "202", "0100", "600").contains("[redacted]");
        var event = new SentryEvent();
        var message = new Message(); message.setFormatted("phone=" + phone); event.setMessage(message);
        var safe = new SentryPrivacyConfiguration().scrubSentryEvent().execute(event, new io.sentry.Hint());
        assertThat(safe.getMessage().getFormatted()).doesNotContain(phone, "202", "0100", "600").contains("[redacted]");
    }

    @Test void T_14_30_jsonHasOnlySafeFieldsAndThreeIds() throws Exception {
        var event = new LoggingEvent();
        event.setTimeStamp(1790000000000L); event.setLevel(Level.ERROR); event.setLoggerName("fixture");
        event.setMessage("failure " + EMAIL + " " + PHONE + " " + IBAN + " " + IP + " " + IPV6);
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException(BODY + " " + EMAIL)));
        event.setMDCPropertyMap(Map.of("traceId", "trace-example", "clubId", "club-example", "accountId", "account-example", "requestBody", BODY));
        String output = new PrivacyLogFormatter().format(event);
        assertThat(output).doesNotContain(EMAIL, PHONE, IBAN, IP, IPV6, BODY);
        var json = new ObjectMapper().readTree(output);
        assertThat(json.path("traceId").asText()).isEqualTo("trace-example");
        assertThat(json.path("clubId").asText()).isEqualTo("club-example");
        assertThat(json.path("accountId").asText()).isEqualTo("account-example");
        assertThat(json.path("exception").asText()).contains("IllegalStateException", "LogPrivacyTest");
        assertThat(LogPrivacy.scrub("time 08:45:16")).isEqualTo("time 08:45:16");
        assertThat(LogPrivacy.scrub("2001:0db8:0000:0000:0000:ff00:0042:8329")).isEqualTo("[redacted]");
        assertThat(LogPrivacy.scrub(null)).isNull();
        assertThat(LogPrivacy.stack(null)).isEmpty();
    }

    @Test void T_14_30_trustedUuidIdsStayByteIdenticalWhileNonIdsAreScrubbed() throws Exception {
        String trace = "88ae4f16-856e-44cf-bf72-93ab871003a1";
        var event = new LoggingEvent();
        event.setTimeStamp(1790000000000L); event.setLevel(Level.INFO); event.setLoggerName("fixture");
        event.setMessage("safe message");
        event.setMDCPropertyMap(Map.of("traceId", trace, "clubId", trace, "accountId", trace));
        var json = new ObjectMapper().readTree(new PrivacyLogFormatter().format(event));
        for (String id : java.util.List.of("traceId", "clubId", "accountId")) {
            assertThat(json.path(id).asText()).isEqualTo(trace);
            MDC.put(id, trace);
        }
        try {
            var sentry = new SentryPrivacyConfiguration().scrubSentryEvent().execute(new SentryEvent(), new io.sentry.Hint());
            for (String id : java.util.List.of("traceId", "clubId", "accountId")) { assertThat(sentry.getTag(id)).isEqualTo(trace); }
        } finally { MDC.clear(); }
        assertThat(LogPrivacy.identifier(EMAIL)).doesNotContain(EMAIL);
        assertThat(LogPrivacy.identifier(null)).isNull();
    }

    @Test void T_14_30_sentryDropsRequestUserBreadcrumbsAndScrubsMessagesBeforeSending() throws Exception {
        var event = new SentryEvent();
        var message = new Message(); message.setFormatted(EMAIL + " " + PHONE + " " + IBAN + " " + IP + " " + IPV6);
        event.setMessage(message);
        var request = new Request(); request.setData(BODY); request.setHeaders(Map.of("Authorization", "Bearer fictional"));
        event.setRequest(request);
        var user = new User(); user.setEmail(EMAIL); user.setIpAddress(IP); event.setUser(user);
        event.setExtra("body", BODY); event.addBreadcrumb(BODY);
        var exception = new io.sentry.protocol.SentryException(); exception.setType("IllegalArgumentException");
        exception.setValue(BODY); event.setExceptions(java.util.List.of(exception));
        MDC.put("traceId", "trace-example"); MDC.put("clubId", "club-example"); MDC.put("accountId", "account-example");
        try {
            var safe = new SentryPrivacyConfiguration().scrubSentryEvent().execute(event, new io.sentry.Hint());
            var output = new java.io.StringWriter();
            new io.sentry.JsonSerializer(new SentryOptions()).serialize(safe, output);
            assertThat(output.toString()).doesNotContain(EMAIL, PHONE, IBAN, IP, IPV6, BODY, "Bearer fictional")
                    .contains("trace-example", "club-example", "account-example", "IllegalArgumentException");
            assertThat(safe.getEventId()).isEqualTo(event.getEventId());
        } finally { MDC.clear(); }
    }
}

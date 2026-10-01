package com.agilityhub.core.configuration;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SentryPrivacyConfiguration {
    @Bean SentryOptions.BeforeSendCallback scrubSentryEvent() {
        return (event, hint) -> {
            // Closed copy: exclude request (body, headers, query, cookies), user, IP, arbitrary contexts and breadcrumbs.
            var safe = new SentryEvent();
            safe.setEventId(event.getEventId());
            safe.setLevel(event.getLevel());
            safe.setRelease(event.getRelease());
            safe.setEnvironment(event.getEnvironment());
            safe.setPlatform("java");
            if (event.getMessage() != null) {
                var message = new Message();
                message.setFormatted(LogPrivacy.scrub(event.getMessage().getFormatted()));
                safe.setMessage(message);
            }
            if (event.getExceptions() != null) {
                var exceptions = new java.util.ArrayList<io.sentry.protocol.SentryException>();
                for (var original : event.getExceptions()) {
                    var error = new io.sentry.protocol.SentryException();
                    error.setType(original.getType()); error.setModule(original.getModule());
                    // An exception's value can be a full request or database document: never collect it.
                    if (original.getStacktrace() != null && original.getStacktrace().getFrames() != null) {
                        var frames = new java.util.ArrayList<io.sentry.protocol.SentryStackFrame>();
                        for (var originalFrame : original.getStacktrace().getFrames()) {
                            var frame = new io.sentry.protocol.SentryStackFrame();
                            frame.setModule(originalFrame.getModule()); frame.setFunction(originalFrame.getFunction());
                            frame.setFilename(originalFrame.getFilename()); frame.setLineno(originalFrame.getLineno());
                            frames.add(frame);
                        }
                        error.setStacktrace(new io.sentry.protocol.SentryStackTrace(frames));
                    }
                    exceptions.add(error);
                }
                safe.setExceptions(exceptions);
            }
            for (String id : java.util.List.of("traceId", "clubId", "accountId")) {
                String value = org.slf4j.MDC.get(id);
                if (value != null) { safe.setTag(id, LogPrivacy.identifier(value)); }
            }
            return safe;
        };
    }
}

package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.api.RequestLocaleResolver;
import com.agilityhub.core.shared.api.RequestTraceFilter;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.sentry.*;
import io.sentry.spring.boot.jakarta.SentryAutoConfiguration;
import io.sentry.transport.ITransport;
import java.util.ArrayList;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class SentryCaptureTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"telemetry-failure,IllegalStateException", "telemetry-framework,ResponseStatusException",
            "telemetry-answered,ApiException"})
    void T_14_30_handled500ReachesTransportOnceWithResponseTraceAndNoPrivateData(String path, String type) {
        var events = new ArrayList<SentryEvent>();
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        WebMvcAutoConfiguration.class, SentryAutoConfiguration.class))
                .withUserConfiguration(Fixture.class)
                .withPropertyValues("sentry.dsn=https://fictional@localhost/1", "sentry.traces-sample-rate=0",
                        "sentry.enable-logs=false", "sentry.send-default-pii=false")
                .withBean(ITransportFactory.class, () -> (options, details) -> new ITransport() {
                    public void send(SentryEnvelope envelope, Hint hint) throws java.io.IOException {
                        for (var item : envelope.getItems()) {
                            if (item.getHeader().getType() == SentryItemType.Event) {
                                try { events.add(item.getEvent(options.getSerializer())); }
                                catch (Exception error) { throw new java.io.IOException(error); }
                            }
                        }
                    }
                    public void flush(long timeout) { }
                    public io.sentry.transport.RateLimiter getRateLimiter() { return null; }
                    public void close() { }
                    public void close(boolean restart) { }
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    var mvc = webAppContextSetup(context).addFilters(new RequestTraceFilter()).build();
                    var response = mvc.perform(get("/" + path).header("Authorization", "Bearer fictional-private-value"))
                            .andExpect(status().isInternalServerError()).andReturn().getResponse();
                    String trace = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString())
                            .path("traceId").asText();
                    assertThat(events).singleElement().satisfies(event -> {
                        assertThat(event.getTag("traceId")).isEqualTo(trace);
                        assertThat(event.getRequest()).isNull();
                        assertThat(event.getUser()).isNull();
                        // The answered 500 keeps its cause in the chain; no exception value (message) is ever sent.
                        assertThat(event.getExceptions()).extracting(io.sentry.protocol.SentryException::getType).contains(type);
                        assertThat(event.getExceptions()).allSatisfy(error -> assertThat(error.getValue()).isNull());
                    });
                    var encoded = new java.io.StringWriter();
                    new JsonSerializer(new SentryOptions()).serialize(events.getFirst(), encoded);
                    assertThat(encoded.toString()).doesNotContain("privacy@example.test", "fictional-private-value", "private-body");
                    mvc.perform(get("/telemetry-rejected")).andExpect(status().isBadRequest());
                    mvc.perform(get("/telemetry-missing")).andExpect(status().isNotFound());
                    // A 501 stub and an expired SSE request are expected answers, not internal errors.
                    mvc.perform(get("/telemetry-stub")).andExpect(status().isNotImplemented());
                    mvc.perform(get("/telemetry-stream-timeout"));
                    mvc.perform(get("/telemetry-stream-gone"));
                    mvc.perform(get("/telemetry-download-aborted"));
                    // An SSE stream that already sent events ends without a JSON error appended to it.
                    assertThat(mvc.perform(get("/telemetry-stream-committed")).andReturn().getResponse().getContentAsString())
                            .isEqualTo("data: fictional\n\n");
                    assertThat(events).hasSize(1);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SentryPrivacyConfiguration.class, ApiExceptionHandler.class, Endpoints.class})
    static class Fixture {
        @Bean IcuMessageSource messages() throws java.io.IOException { return new IcuMessageSource(); }
        @Bean RequestLocaleResolver locales(IcuMessageSource messages) {
            return new RequestLocaleResolver(messages, ignored -> java.util.Optional.empty());
        }
    }
    @RestController static class Endpoints {
        @GetMapping("/telemetry-failure") void failure() { throw new IllegalStateException("private-body privacy@example.test"); }
        @GetMapping("/telemetry-framework") void framework() {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "private-body privacy@example.test");
        }
        @GetMapping("/telemetry-answered") void answered() {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, new IllegalStateException("private-body privacy@example.test"));
        }
        @GetMapping("/telemetry-rejected") void rejected() { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        @GetMapping("/telemetry-stub") void stub() { throw new ApiException(ErrorCode.NOT_IMPLEMENTED); }
        @GetMapping("/telemetry-stream-timeout") void streamTimeout() {
            throw new org.springframework.web.context.request.async.AsyncRequestTimeoutException();
        }
        /** An SSE stream that timed out after its first event: the response is already committed. */
        @GetMapping("/telemetry-stream-committed") void streamCommitted(jakarta.servlet.http.HttpServletResponse response)
                throws java.io.IOException {
            response.getOutputStream().write("data: fictional\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            response.flushBuffer();
            throw new org.springframework.web.context.request.async.AsyncRequestTimeoutException();
        }
        /** A client that closed the connection during a download (Tomcat's ClientAbortException). */
        @GetMapping("/telemetry-download-aborted") void downloadAborted() throws java.io.IOException {
            throw new org.apache.catalina.connector.ClientAbortException("fictional client closed the download");
        }
        @GetMapping("/telemetry-stream-gone") void streamGone() throws java.io.IOException {
            throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException("fictional client went away");
        }
    }
}

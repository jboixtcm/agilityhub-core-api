package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import com.agilityhub.core.shared.application.IcuMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    private final IcuMessageSource messages = new IcuMessageSource();
    private final RequestLocaleResolver locales = new RequestLocaleResolver(messages, ignored -> java.util.Optional.empty());
    private final ApiExceptionHandler advice = new ApiExceptionHandler(messages, locales);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TestController()).setControllerAdvice(advice).addFilters(new RequestTraceFilter()).build();

    ApiExceptionHandlerTest() throws java.io.IOException { }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', textBlock = """
        ca | S'ha produït un error inesperat.
        es | Se ha producido un error inesperado.
        en | An unexpected error occurred.
        """)
    void E3_T06_INC02_unhandledFailureLogsOneFullExceptionWithTheResponseTrace(String locale, String prefix) throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = mvc.perform(get("/unexpected").header("Accept-Language", locale))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.details").isEmpty()).andReturn().getResponse();
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
            String traceId = body.path("traceId").asText();
            assertThat(traceId).isNotBlank();
            assertThat(body.path("message").asText()).startsWith(prefix).contains(traceId).doesNotContain("{traceId}");
            assertThat(response.getContentAsString()).doesNotContain("IllegalStateException", "fictional failure", "cause");
            assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("traceId=" + traceId);
                        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
                        assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
                        assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("fictional cause");
                    });
            appender.list.clear();
            mvc.perform(get("/business")).andExpect(status().isConflict());
            mvc.perform(get("/denied")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(get("/stub")).andExpect(status().isNotImplemented());
            assertThat(appender.list).noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void E0_T04_businessErrorsResolveMessagesAndPreserveDetails() throws Exception {
        mvc.perform(get("/business").header("Accept-Language", "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.code").value("BOOKING_LIMIT_REACHED"))
                .andExpect(jsonPath("$.message").value("The booking limit has been reached."))
                .andExpect(jsonPath("$.details.limit").value(2))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    @Test void E0_T04_validationIncludesFieldCodeAndMessage() throws Exception {
        mvc.perform(post("/validation").contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.details.fieldErrors[0].code").value("NotBlank"))
                .andExpect(jsonPath("$.details.fieldErrors[0].message").isNotEmpty());
        mvc.perform(post("/validation").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void E0_T04_constraintViolationsUseTheSameContract() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new Input(""));
            var response = advice.validation(new jakarta.validation.ConstraintViolationException(violations),
                    new MockHttpServletRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().details().get("fieldErrors")).asList().hasSize(1);
        }
    }
    @Test void E0_T04_traceIsStableWithinARequestAndUniqueAcrossRequests() {
        var first = new MockHttpServletRequest();
        String id = RequestTraceFilter.traceId(first);
        assertThat(RequestTraceFilter.traceId(first)).isEqualTo(id);
        assertThat(RequestTraceFilter.traceId(new MockHttpServletRequest())).isNotEqualTo(id);
        try {
            MDC.put("traceId", "0123456789abcdef0123456789abcdef");
            assertThat(RequestTraceFilter.traceId(new MockHttpServletRequest())).isEqualTo(MDC.get("traceId"));
        } finally { MDC.remove("traceId"); }
    }

    @RestController static class TestController {
        @GetMapping("/unexpected") void unexpected() {
            throw new IllegalStateException("fictional failure", new IllegalArgumentException("fictional cause"));
        }
        @GetMapping("/denied") void denied() { throw new org.springframework.security.access.AccessDeniedException("denied"); }
        @GetMapping("/stub") void stub() { throw new UnsupportedOperationException(); }
        @GetMapping("/business") void business() {
            throw new ApiException(ErrorCode.BOOKING_LIMIT_REACHED, Map.of("limit", 2));
        }
        @PostMapping("/validation") Input validation(@Valid @RequestBody Input input) { return input; }
    }
    record Input(@NotBlank String name) { }
}

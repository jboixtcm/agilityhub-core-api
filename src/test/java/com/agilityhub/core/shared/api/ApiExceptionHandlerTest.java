package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    private final StaticMessageSource messages = new StaticMessageSource();
    private final ApiExceptionHandler advice = new ApiExceptionHandler(messages);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TestController()).setControllerAdvice(advice).addFilters(new RequestTraceFilter()).build();

    @Test void E0_T04_businessErrorsResolveMessagesAndPreserveDetails() throws Exception {
        messages.addMessage("error.BOOKING_LIMIT_REACHED", Locale.ENGLISH, "Booking limit reached");
        mvc.perform(get("/business").locale(Locale.ENGLISH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.code").value("BOOKING_LIMIT_REACHED"))
                .andExpect(jsonPath("$.message").value("Booking limit reached"))
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
        @GetMapping("/business") void business() {
            throw new ApiException(ErrorCode.BOOKING_LIMIT_REACHED, Map.of("limit", 2));
        }
        @PostMapping("/validation") Input validation(@Valid @RequestBody Input input) { return input; }
    }
    record Input(@NotBlank String name) { }
}

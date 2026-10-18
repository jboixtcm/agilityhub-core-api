package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.HealthController;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigurationTest {
    @Test void E0_T12_customizerCoversExplicitErrorsAndPreservesSuccessAndExtensions() throws Exception {
        var success = new ApiResponse().description("Created");
        var operation = new Operation().responses(new ApiResponses().addApiResponse("201", success)
                .addApiResponse("503", new ApiResponse().description("Unavailable").content(new Content()
                        .addMediaType("application/problem+json", new MediaType().schema(new StringSchema())))));
        operation.addExtension("x-filterable", java.util.List.of("status"));
        var result = new OpenApiConfiguration().contextAndErrors().customize(operation, new HandlerMethod(new com.agilityhub.core.shared.api.RequestTraceFilter(),
                com.agilityhub.core.shared.api.RequestTraceFilter.class.getMethod("traceId", jakarta.servlet.http.HttpServletRequest.class)));
        assertThat(result.getResponses().get("201")).isSameAs(success);
        assertThat(result.getResponses().get("503").getDescription()).isEqualTo("Unavailable");
        assertThat(result.getResponses().get("503").getContent()).containsOnlyKeys("application/json");
        assertThat(result.getResponses().get("503").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ApiError");
        assertThat(result.getExtensions()).containsEntry("x-filterable", java.util.List.of("status"));
        assertThat(result.getTags()).containsExactly("shared");
        assertThat(OpenApiConfiguration.withErrors(new Operation()).getResponses()).containsKeys("4XX", "5XX");
    }

    /** E11-T07: the shared error texts are the contract's, not the framework's (Spring 7 says "Unprocessable Content"). */
    @Test void E11_T07_sharedErrorDescriptionsKeepThePublishedTexts() {
        var responses = OpenApiConfiguration.withErrors(new Operation()).getResponses();
        var descriptions = new java.util.LinkedHashMap<String, String>();
        responses.forEach((status, response) -> descriptions.put(status, response.getDescription()));
        assertThat(descriptions).containsExactly(java.util.Map.entry("400", "Bad Request"), java.util.Map.entry("401", "Unauthorized"),
                java.util.Map.entry("403", "Forbidden"), java.util.Map.entry("404", "Not Found"), java.util.Map.entry("409", "Conflict"),
                java.util.Map.entry("422", "Unprocessable Entity"), java.util.Map.entry("429", "Too Many Requests"),
                java.util.Map.entry("500", "Internal Server Error"), java.util.Map.entry("501", "Not Implemented"),
                java.util.Map.entry("4XX", "Client error"), java.util.Map.entry("5XX", "Server error"));
    }

    /**
     * E11-T02 round 2 (review #4, INC-47): a keyed route's 409 references the shared StaleVersion response; the route's own codes
     * stay in the description, STALE_VERSION is added once, and the generic «Conflict» becomes STALE_VERSION alone. INC-43: a 416
     * keeps no body.
     */
    @Test void INC_47_aKeyedRoutesConflictReferencesTheSharedStaleVersionResponseWithItsOwnCodes() {
        for (var expected : java.util.Map.of("Conflict", "STALE_VERSION", "INVALID_STATE, IDEMPOTENCY_KEY_REUSED", "INVALID_STATE, IDEMPOTENCY_KEY_REUSED, STALE_VERSION",
                "STALE_VERSION, INVALID_STATE", "STALE_VERSION, INVALID_STATE").entrySet()) {
            var operation = new Operation().responses(new ApiResponses().addApiResponse("409", new ApiResponse().description(expected.getKey())));
            OpenApiConfiguration.declareKeyedConflict(operation);
            var conflict = operation.getResponses().get("409");
            assertThat(conflict.get$ref()).isEqualTo("#/components/responses/StaleVersion");
            assertThat(conflict.getDescription()).isEqualTo(expected.getValue());
            assertThat(conflict.getContent()).isNull();
        }
        var none = new Operation().responses(new ApiResponses());
        OpenApiConfiguration.declareKeyedConflict(none);
        assertThat(none.getResponses().get("409").getDescription()).isEqualTo("STALE_VERSION");
        var download = OpenApiConfiguration.withErrors(new Operation().responses(new ApiResponses().addApiResponse("416", new ApiResponse().description("no body"))));
        assertThat(download.getResponses().get("416").getContent()).isNull();
        assertThat(download.getResponses().get("409").getContent()).containsOnlyKeys("application/json");
    }

    /** E5-T27 step 5 (INC-01, A7-07): the health's 503 DOWN keeps the health envelope; its other errors stay ApiError. */
    @Test void E5_T27_theHealthRoutes503IsItsOwnEnvelope() throws Exception {
        var health = new OpenApiConfiguration().contextAndErrors().customize(new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse())),
                new HandlerMethod(new HealthController(null, null), HealthController.class.getMethod("health", jakarta.servlet.http.HttpServletRequest.class)));
        assertThat(health.getResponses().get("503").getContent().get("application/json").getSchema().get$ref()).isEqualTo("#/components/schemas/HealthResponse");
        assertThat(health.getResponses().get("500").getContent().get("application/json").getSchema().get$ref()).isEqualTo("#/components/schemas/ApiError");
    }
}

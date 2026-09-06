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
        var result = new OpenApiConfiguration().contextAndErrors().customize(operation,
                new HandlerMethod(new HealthController(null), HealthController.class.getMethod("health")));
        assertThat(result.getResponses().get("201")).isSameAs(success);
        assertThat(result.getResponses().get("503").getDescription()).isEqualTo("Unavailable");
        assertThat(result.getResponses().get("503").getContent()).containsOnlyKeys("application/json");
        assertThat(result.getResponses().get("503").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ApiError");
        assertThat(result.getExtensions()).containsEntry("x-filterable", java.util.List.of("status"));
        assertThat(result.getTags()).containsExactly("shared");
        assertThat(OpenApiConfiguration.withErrors(new Operation()).getResponses()).containsKeys("4XX", "5XX");
    }
}

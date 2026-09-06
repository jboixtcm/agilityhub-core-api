package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean OpenAPI coreOpenApi() {
        var components = new Components().schemas(ModelConverters.getInstance(true).read(ApiError.class))
                .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                        .scheme("bearer").bearerFormat("JWT"));
        var api = new OpenAPI().openapi("3.1.0")
                .info(new Info().title("AgilityHub Core API").version("v1"))
                .servers(List.of(new Server().url("https://core.agilitydoghub.com"),
                        new Server().url("http://localhost:8080")))
                .components(components).addSecurityItem(new SecurityRequirement().addList("bearer"));
        return api;
    }

    @Bean OperationCustomizer contextAndErrors() {
        return (operation, handler) -> {
            String packageName = handler.getBeanType().getPackageName();
            String prefix = "com.agilityhub.core.";
            int apiLayer = packageName.indexOf(".api", prefix.length());
            if (packageName.startsWith(prefix) && apiLayer > 0) {
                operation.setTags(List.of(packageName.substring(prefix.length(), apiLayer)));
            }
            var result = withErrors(operation);
            if (result.getResponses().containsKey("429")) {
                result.getResponses().get("429").addHeaderObject("Retry-After",
                        new io.swagger.v3.oas.models.headers.Header().description("Seconds before retrying")
                                .schema(new io.swagger.v3.oas.models.media.IntegerSchema()));
            }
            return result;
        };
    }

    static Operation withErrors(Operation operation) {
        if (operation.getResponses() == null) { operation.setResponses(new ApiResponses()); }
        for (int status : new int[]{400, 401, 403, 404, 409, 422, 429, 500, 501}) {
            operation.getResponses().putIfAbsent(Integer.toString(status),
                    new ApiResponse().description(HttpStatus.valueOf(status).getReasonPhrase()));
        }
        operation.getResponses().putIfAbsent("4XX", new ApiResponse().description("Client error"));
        operation.getResponses().putIfAbsent("5XX", new ApiResponse().description("Server error"));
        operation.getResponses().forEach((status, response) -> {
            if (status.matches("[45](?:[0-9]{2}|XX)")) {
                response.setContent(json(new Schema<>().$ref("#/components/schemas/ApiError")));
            }
        });
        return operation;
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }
}

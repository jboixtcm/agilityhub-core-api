package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.ApiError;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
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
            var list = handler.getMethodAnnotation(ListContract.class);
            if (list != null) {
                operation.addExtension("x-filterable", java.util.Arrays.stream(list.filterable())
                        .map(field -> field.split("\\(")[0].trim()).toList());
                var operators = new java.util.LinkedHashMap<String, List<String>>();
                for (String field : list.filterable()) {
                    if (field.contains("(")) {
                        operators.put(field.substring(0, field.indexOf('(')).trim(),
                                List.of(field.substring(field.indexOf('(') + 1, field.indexOf(')'))));
                    }
                }
                operation.addExtension("x-filter-operators", operators);
                operation.addExtension("x-sortable", List.of(list.sortable()));
                operation.addExtension("x-columns", java.util.Arrays.stream(list.columns()).map(column -> {
                    var definition = new java.util.LinkedHashMap<String, Object>();
                    definition.put("key", column.split("[*@#]")[0]);
                    definition.put("defaultVisible", column.contains("*"));
                    if (column.contains("@")) { definition.put("module", column.substring(column.indexOf('@') + 1)); }
                    if (column.contains("#")) { definition.put("parameter", column.substring(column.indexOf('#') + 1)); }
                    return definition;
                }).toList());
                operation.addExtension("x-exportable", list.exportable());
                if (list.paged()) { addListParameters(operation); }
            }
            var errors = handler.getMethodAnnotation(ContractErrors.class);
            if (errors != null) {
                if (operation.getResponses() == null) { operation.setResponses(new ApiResponses()); }
                java.util.Arrays.stream(errors.value()).collect(java.util.stream.Collectors.groupingBy(
                        code -> Integer.toString(code.httpStatus()), java.util.TreeMap::new,
                        java.util.stream.Collectors.mapping(Enum::name, java.util.stream.Collectors.joining(", "))))
                        .forEach((status, codes) -> operation.getResponses().addApiResponse(status, new ApiResponse().description(codes)));
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

    private static void addListParameters(Operation operation) {
        if (operation.getParameters() == null) { operation.setParameters(new java.util.ArrayList<>()); }
        for (String name : List.of("page", "size", "sort", "q", "filter", "fields")) {
            if (operation.getParameters().stream().anyMatch(parameter -> name.equals(parameter.getName()))) { continue; }
            Schema<?> schema = switch (name) {
                case "page" -> new io.swagger.v3.oas.models.media.IntegerSchema()._default(0).minimum(java.math.BigDecimal.ZERO);
                case "size" -> new io.swagger.v3.oas.models.media.IntegerSchema()._default(50).minimum(java.math.BigDecimal.ONE);
                case "sort", "filter" -> new io.swagger.v3.oas.models.media.ArraySchema()
                        .items(new io.swagger.v3.oas.models.media.StringSchema());
                default -> new io.swagger.v3.oas.models.media.StringSchema();
            };
            var parameter = new io.swagger.v3.oas.models.parameters.QueryParameter().name(name).schema(schema)
                    .description(switch (name) {
                        case "filter" -> "Repeat field:op:value; operators eq, ne, in, nin, lt, lte, gt, gte, contains, startsWith, exists, between. Only x-filterable fields; otherwise INVALID_FILTER.";
                        case "sort" -> "Repeat field,asc or field,desc; only x-sortable fields.";
                        case "fields" -> "Comma-separated response column keys.";
                        case "q" -> "Free-text search within the caller's permitted projection.";
                        case "page" -> "Zero-based page index.";
                        default -> "Requested page size.";
                    });
            if (name.equals("sort") || name.equals("filter")) { parameter.style(io.swagger.v3.oas.models.parameters.Parameter.StyleEnum.FORM).explode(true); }
            operation.addParametersItem(parameter);
        }
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

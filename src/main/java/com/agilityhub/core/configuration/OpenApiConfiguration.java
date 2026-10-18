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
import org.springdoc.core.converters.ModelConverterRegistrar;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfiguration {
    @Bean RequiredPropertiesModelConverter requiredProperties(ObjectMapperProvider provider) {
        provider.jsonMapper().registerModule(requiredArraysModule()).registerModule(nullableEnumsModule());
        provider.yamlMapper().registerModule(requiredArraysModule()).registerModule(nullableEnumsModule());
        return new RequiredPropertiesModelConverter(provider.jsonMapper());
    }

    /**
     * E3-T10 round 2: a nullable schema (`type` lists `null`) with an `enum` lists `null` in the `enum` too; in JSON Schema
     * 2020-12 the `enum` alone decides which values pass, so without it a strict validator rejects the `null` the type allows.
     * Applied where the document is written, so it reaches every schema: the model converters', the customizers' and inline ones.
     */
    static com.fasterxml.jackson.databind.Module nullableEnumsModule() {
        return new com.fasterxml.jackson.databind.module.SimpleModule("openapi-nullable-enums")
                .setSerializerModifier(new com.fasterxml.jackson.databind.ser.BeanSerializerModifier() {
                    @Override public List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> changeProperties(
                            com.fasterxml.jackson.databind.SerializationConfig config,
                            com.fasterxml.jackson.databind.BeanDescription bean,
                            List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> properties) {
                        if (Schema.class.isAssignableFrom(bean.getBeanClass())) {
                            properties.replaceAll(property -> property.getName().equals("enum")
                                    ? new com.fasterxml.jackson.databind.ser.BeanPropertyWriter(property) {
                                        @Override public void serializeAsField(Object value, com.fasterxml.jackson.core.JsonGenerator generator,
                                                com.fasterxml.jackson.databind.SerializerProvider provider) throws Exception {
                                            var schema = (Schema<?>) value;
                                            if (nullable(schema) && schema.getEnum() != null && !schema.getEnum().isEmpty()
                                                    && schema.getEnum().stream().noneMatch(java.util.Objects::isNull)) {
                                                generator.writeArrayFieldStart("enum");
                                                for (Object item : schema.getEnum()) { provider.defaultSerializeValue(item, generator); }
                                                generator.writeNull();
                                                generator.writeEndArray();
                                            } else { super.serializeAsField(value, generator, provider); }
                                        }
                                    } : property);
                        }
                        return properties;
                    }
                });
    }

    private static boolean nullable(Schema<?> schema) {
        return "null".equals(schema.getType()) || schema.getTypes() != null && schema.getTypes().contains("null");
    }

    /** Keep empty required arrays for models with only explicitly optional properties. */
    static com.fasterxml.jackson.databind.Module requiredArraysModule() {
        return new com.fasterxml.jackson.databind.module.SimpleModule("openapi-required-arrays")
                .setSerializerModifier(new com.fasterxml.jackson.databind.ser.BeanSerializerModifier() {
                    @Override public List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> changeProperties(
                            com.fasterxml.jackson.databind.SerializationConfig config,
                            com.fasterxml.jackson.databind.BeanDescription bean,
                            List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> properties) {
                        if (Schema.class.isAssignableFrom(bean.getBeanClass())) {
                            properties.replaceAll(property -> property.getName().equals("required")
                                    ? new com.fasterxml.jackson.databind.ser.BeanPropertyWriter(property) {
                                        @Override public void serializeAsField(Object value, com.fasterxml.jackson.core.JsonGenerator generator,
                                                com.fasterxml.jackson.databind.SerializerProvider provider) throws Exception {
                                            var schema = (Schema<?>) value;
                                            if ((schema.getRequired() == null || schema.getRequired().isEmpty()) && schema.getProperties() != null) {
                                                generator.writeArrayFieldStart("required");
                                                generator.writeEndArray();
                                            } else { super.serializeAsField(value, generator, provider); }
                                        }
                                    } : property);
                        }
                        return properties;
                    }
                });
    }

    @Bean OpenAPI coreOpenApi(ModelConverterRegistrar converters) {
        var components = new Components().schemas(ModelConverters.getInstance(true).read(ApiError.class))
                .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                        .scheme("bearer").bearerFormat("JWT"))
                .addResponses(KEYED_CONFLICT, new ApiResponse().description("STALE_VERSION: the keyed write met a transient transaction "
                        + "conflict; its effects were rolled back and the key released. Read again and retry (CONVENCIONS_API §7, INC-47).")
                        .content(json(new Schema<>().$ref("#/components/schemas/ApiError"))));
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
                if (list.fields().length > 0) { operation.addExtension("x-fields", List.of(list.fields())); }
                operation.addExtension("x-columns", java.util.Arrays.stream(list.columns()).map(column -> {
                    var definition = new java.util.LinkedHashMap<String, Object>();
                    definition.put("key", column.split("[*@#]")[0]);
                    definition.put("defaultVisible", column.contains("*"));
                    if (column.contains("@")) { definition.put("module", column.substring(column.indexOf('@') + 1)); }
                    if (column.contains("#")) { definition.put("parameter", column.substring(column.indexOf('#') + 1)); }
                    return definition;
                }).toList());
                operation.addExtension("x-exportable", list.exportable());
                if (list.paged()) { addListParameters(operation, list.acceptsFields(), list.searchable(), list.maxSize()); }
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
            if (handler.getBeanType() == com.agilityhub.core.shared.api.HealthController.class) {
                // E5-T27 step 5 (INC-01, A7-07): the health's 503 answers its own envelope {status: DOWN, version, builtAt}, not an ApiError.
                result.getResponses().addApiResponse("503", new ApiResponse().description("HealthResponse with status DOWN: the database did not answer a ping within 1 s")
                        .content(json(new Schema<>().$ref("#/components/schemas/HealthResponse"))));
            }
            if (errors != null) {
                for (int status : errors.omit()) {
                    if (java.util.Arrays.stream(errors.value()).noneMatch(code -> code.httpStatus() == status)) {
                        result.getResponses().remove(Integer.toString(status));
                    }
                }
            }
            if (com.agilityhub.core.shared.api.KeyedRoutes.keyed(handler)) { declareKeyedConflict(result); }
            if (result.getResponses().containsKey("429")) {
                result.getResponses().get("429").addHeaderObject("Retry-After",
                        new io.swagger.v3.oas.models.headers.Header().description("Seconds before retrying")
                                .schema(new io.swagger.v3.oas.models.media.IntegerSchema()));
            }
            return result;
        };
    }

    /** The shared 409 of the keyed routes (`#/components/responses/StaleVersion`). */
    static final String KEYED_CONFLICT = "StaleVersion";

    /**
     * E11-T02 round 2 (review #4, INC-47): a keyed write answers `409 STALE_VERSION` when a transient transaction conflict rolls it
     * back and releases its key. Its 409 is the shared component, with the route's own codes, and STALE_VERSION, as the reference's
     * description (OpenAPI 3.1 lets a reference override it).
     */
    static void declareKeyedConflict(Operation operation) {
        var current = operation.getResponses().get("409");
        String codes = current == null || current.getDescription() == null || current.getDescription().equals("Conflict") ? "" : current.getDescription();
        if (!List.of(codes.split(", ")).contains("STALE_VERSION")) { codes = codes.isEmpty() ? "STALE_VERSION" : codes + ", STALE_VERSION"; }
        var reference = new ApiResponse().description(codes);
        reference.set$ref("#/components/responses/" + KEYED_CONFLICT);
        operation.getResponses().addApiResponse("409", reference);
    }

    private static void addListParameters(Operation operation, boolean fields, boolean searchable, int maxSize) {
        var allowed = com.agilityhub.core.shared.application.lists.ListQuery.SIZES.stream().filter(size -> size <= maxSize).toList();
        if (operation.getParameters() == null) { operation.setParameters(new java.util.ArrayList<>()); }
        for (String name : List.of("page", "size", "sort", "q", "filter", "fields")) {
            if (name.equals("fields") && !fields) { continue; }
            // CONVENCIONS_API §4 (E75): a list without search does not declare q.
            if (name.equals("q") && !searchable) { continue; }
            if (operation.getParameters().stream().anyMatch(parameter -> name.equals(parameter.getName()))) { continue; }
            Schema<?> schema = switch (name) {
                case "page" -> new io.swagger.v3.oas.models.media.IntegerSchema()._default(0).minimum(java.math.BigDecimal.ZERO);
                case "size" -> { var sizes = new io.swagger.v3.oas.models.media.IntegerSchema()._default(50);
                    sizes.setEnum(new java.util.ArrayList<Number>(allowed)); yield sizes; }
                case "sort", "filter" -> new io.swagger.v3.oas.models.media.ArraySchema()
                        .items(new io.swagger.v3.oas.models.media.StringSchema());
                default -> new io.swagger.v3.oas.models.media.StringSchema();
            };
            var parameter = new io.swagger.v3.oas.models.parameters.QueryParameter().name(name).schema(schema)
                    .description(switch (name) {
                        case "filter" -> "Repeat field:op:value; operators eq, ne, in, nin, lt, lte, gt, gte, contains, startsWith, exists, between. Only x-filterable fields; otherwise INVALID_FILTER.";
                        case "sort" -> "Repeat field,asc or field,desc; only x-sortable fields.";
                        case "fields" -> "Comma-separated keys of this operation's x-fields; any other key is INVALID_FILTER. A list sends the row id "
                                + "and the requested keys only. An export takes the requested keys that are columns (x-columns) as its columns "
                                + "when columns is absent; none of them is INVALID_FILTER.";
                        case "q" -> "Free-text search within the caller's permitted projection.";
                        case "page" -> "Zero-based page index.";
                        default -> maxSize < 1000
                                ? "Requested page size: " + allowed.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(" or "))
                                        + " (CONVENCIONS_API §4; this list's pages hold at most " + maxSize + " rows); any other value is 400 INVALID_FILTER."
                                : "Requested page size: 20, 50, 200 or 1000 (CONVENCIONS_API §4); any other value is 400 INVALID_FILTER.";
                    });
            if (name.equals("sort") || name.equals("filter")) { parameter.style(io.swagger.v3.oas.models.parameters.Parameter.StyleEnum.FORM).explode(true); }
            operation.addParametersItem(parameter);
        }
    }

    /**
     * The contract's own texts for the shared error responses. E11-T07: Spring Framework 7 renamed 422's reason phrase to RFC
     * 9110's "Unprocessable Content"; the published contract keeps the texts it had, independent of the framework.
     */
    private static final List<java.util.Map.Entry<String, String>> ERROR_DESCRIPTIONS = List.of(java.util.Map.entry("400", "Bad Request"),
            java.util.Map.entry("401", "Unauthorized"), java.util.Map.entry("403", "Forbidden"), java.util.Map.entry("404", "Not Found"),
            java.util.Map.entry("409", "Conflict"), java.util.Map.entry("422", "Unprocessable Entity"),
            java.util.Map.entry("429", "Too Many Requests"), java.util.Map.entry("500", "Internal Server Error"),
            java.util.Map.entry("501", "Not Implemented"));

    static Operation withErrors(Operation operation) {
        if (operation.getResponses() == null) { operation.setResponses(new ApiResponses()); }
        for (var error : ERROR_DESCRIPTIONS) {
            operation.getResponses().putIfAbsent(error.getKey(), new ApiResponse().description(error.getValue()));
        }
        operation.getResponses().putIfAbsent("4XX", new ApiResponse().description("Client error"));
        operation.getResponses().putIfAbsent("5XX", new ApiResponse().description("Server error"));
        operation.getResponses().forEach((status, response) -> {
            // INC-43: a download's 416 has no body, only `Content-Range: bytes */size` (ByteRanges).
            if (status.matches("[45](?:[0-9]{2}|XX)") && !status.equals("416")) {
                response.setContent(json(new Schema<>().$ref("#/components/schemas/ApiError")));
            }
        });
        return operation;
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }
}

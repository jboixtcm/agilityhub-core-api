package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
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
        // Spring Security filters have no MVC HandlerMethod for springdoc to discover.
        api.path("/oauth2/token", new PathItem().post(tokenOperation()));
        api.path("/oauth2/jwks", new PathItem().get(jwksOperation("jwks")));
        api.path("/.well-known/jwks.json", new PathItem().get(jwksOperation("wellKnownJwks")));
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
            return withErrors(operation);
        };
    }

    static Operation withErrors(Operation operation) {
        if (operation.getResponses() == null) { operation.setResponses(new ApiResponses()); }
        for (int status : new int[]{400, 401, 403, 404, 409, 422, 429, 500}) {
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

    private Operation tokenOperation() {
        var password = grant("password").addProperty("username", new StringSchema().format("email"))
                .addProperty("password", new StringSchema().format("password").writeOnly(true))
                .required(List.of("grant_type", "username", "password"));
        var refresh = grant("refresh_token")
                .addProperty("refresh_token", new StringSchema().writeOnly(true))
                .required(List.of("grant_type", "refresh_token"));
        var response = new ObjectSchema()
                .addProperty("access_token", new StringSchema())
                .addProperty("token_type", new StringSchema()._enum(List.of("Bearer")))
                .addProperty("expires_in", new IntegerSchema().format("int64"))
                .addProperty("refresh_token", new StringSchema())
                .required(List.of("access_token", "token_type", "expires_in", "refresh_token"));
        return withErrors(new Operation().operationId("token").tags(List.of("identity")).security(List.of())
                .summary("Issue or refresh a tenant-scoped access token")
                .description("The request host selects the club. Password and refresh grants accept public clients; "
                        + "Authorization, client_secret and scope are rejected. Refresh tokens rotate on use.")
                .requestBody(new RequestBody().required(true).content(new Content().addMediaType(
                        "application/x-www-form-urlencoded", new MediaType().schema(
                                new ComposedSchema().oneOf(List.of(password, refresh))))))
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse()
                        .description("Token issued").content(json(response)))));
    }

    private Schema<?> grant(String type) {
        return new ObjectSchema().addProperty("grant_type", new StringSchema()._enum(List.of(type)))
                .addProperty("client_id", new StringSchema()._default("clubs-app")
                        ._enum(List.of("clubs-app", "clubs-admin")));
    }

    private Operation jwksOperation(String id) {
        var key = new ObjectSchema().addProperty("kty", new StringSchema()._enum(List.of("RSA")))
                .addProperty("kid", new StringSchema()).addProperty("use", new StringSchema())
                .addProperty("alg", new StringSchema()).addProperty("n", new StringSchema())
                .addProperty("e", new StringSchema()).required(List.of("kty", "kid", "n", "e"));
        return withErrors(new Operation().operationId(id).tags(List.of("identity")).security(List.of())
                .summary("Get public JWT verification keys")
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("Public JWK set")
                        .content(json(new ObjectSchema().addProperty("keys", new ArraySchema().items(key))
                                .required(List.of("keys")))))));
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }
}

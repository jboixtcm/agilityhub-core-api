package com.agilityhub.core.configuration;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Public S04 operations which also accept a member's bearer token; the E3 schemas' nullable references. */
@Configuration(proxyBeanMethods = false)
public class E3ContractConfiguration {
    /**
     * E3-T16 round 2: the E3 schemas with a nullable reference. `Member` (the D2 view, S04) sends an optional object without
     * a value as `null`; `ClubSummary.legalAddress` (`/branding`, R-02-02) is `null` without a street or postal code.
     */
    static final List<String> NULLABLE_REFERENCES = List.of("Member", "ClubSummary");

    @Bean OpenApiCustomizer optionalSignupAuthentication() {
        return api -> {
            var optionalBearer = List.of(new SecurityRequirement(), new SecurityRequirement().addList("bearer"));
            api.getPaths().get("/api/v1/signup").getGet().setSecurity(optionalBearer);
            api.getPaths().get("/api/v1/signup/upload-urls").getPost().setSecurity(optionalBearer);
            api.getPaths().get("/api/v1/checkout-sessions").getPost().setSecurity(optionalBearer);
        };
    }

    /** OpenAPI 3.1 uses a union, not a `null` type beside a `$ref` (which no value satisfies), as E4 and E5 do. */
    @Bean OpenApiCustomizer e3NullableReferences() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            for (String name : NULLABLE_REFERENCES) {
                Schema<?> model = schemas.get(name);
                if (model != null && model.getProperties() != null) { model.getProperties().replaceAll((field, property) -> nullableReference(property)); }
            }
        };
    }

    private static Schema<?> nullableReference(Schema<?> property) {
        if (property.get$ref() == null || !("null".equals(property.getType())
                || property.getTypes() != null && property.getTypes().contains("null"))) { return property; }
        var union = new Schema<>();
        union.setDescription(property.getDescription());
        union.addAnyOfItem(new Schema<>().$ref(property.get$ref()));
        union.addAnyOfItem(new Schema<>().types(Set.of("null")));
        return union;
    }
}

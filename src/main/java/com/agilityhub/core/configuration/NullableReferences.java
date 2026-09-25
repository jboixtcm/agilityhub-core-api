package com.agilityhub.core.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * The shared nullable-reference customizer of the contract configurations (E5-T16, review E3-T16 #5). A nullable
 * property that is a `$ref` is published as the OpenAPI 3.1 union `anyOf [$ref, {type: null}]` (CONVENCIONS_API, INC-08):
 * swagger writes a `null` type beside the `$ref` (`nullable = true`, or `types = {"object", "null"}`), which a JSON Schema
 * 2020-12 validator still checks against the `$ref`, so no value, not even `null`, satisfies it; openapi-typescript
 * keeps the `null` of the union. Only the properties of the named schemas are rewritten; a schema missing from the
 * document (a slice without its controller) is skipped.
 */
record NullableReferences(List<String> schemas) implements OpenApiCustomizer {
    NullableReferences { schemas = List.copyOf(schemas); }

    @Override public void customise(OpenAPI api) {
        var components = api.getComponents().getSchemas();
        for (String name : schemas) {
            Schema<?> model = components.get(name);
            if (model != null && model.getProperties() != null) { model.getProperties().replaceAll((field, property) -> union(property)); }
        }
    }

    /** The union for a `$ref` with a `null` type; any other property is returned unchanged. */
    static Schema<?> union(Schema<?> property) {
        if (property.get$ref() == null || !("null".equals(property.getType())
                || property.getTypes() != null && property.getTypes().contains("null"))) { return property; }
        var union = new Schema<>();
        union.setDescription(property.getDescription());
        union.addAnyOfItem(new Schema<>().$ref(property.get$ref()));
        union.addAnyOfItem(new Schema<>().types(Set.of("null")));
        return union;
    }
}

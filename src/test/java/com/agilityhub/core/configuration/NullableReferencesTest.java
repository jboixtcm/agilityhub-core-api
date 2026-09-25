package com.agilityhub.core.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E5-T16 step 4 (review E3-T16 #5): the one nullable-reference customizer the E2, E3, E4, E5 and E6 contract configurations
 * share (INC-08). {@code OpenApiSnapshotTest} proves the generated document did not change with the move.
 */
class NullableReferencesTest {
    static Schema<?> reference(String name) { return new Schema<>().$ref("#/components/schemas/" + name); }

    @Test void INC_08_aNullableReferenceBecomesAUnionAndEveryOtherPropertyIsKept() {
        var swagger = reference("TrainingOverride").description("Impersonation only");
        swagger.setType("null");
        var house = reference("RiskReview").types(Set.of("object", "null"));
        var plain = reference("DashboardWeek");
        var text = new Schema<>().types(Set.of("string", "null"));
        var model = new Schema<>().addProperty("swagger", swagger).addProperty("house", house).addProperty("plain", plain).addProperty("text", text);
        var untouched = new Schema<>().addProperty("house", reference("RiskReview").types(Set.of("object", "null")));
        var api = new OpenAPI().components(new Components().addSchemas("Listed", model).addSchemas("Other", untouched)
                .addSchemas("Empty", new Schema<>()));
        new NullableReferences(List.of("Listed", "Empty", "Missing")).customise(api);
        for (String field : List.of("swagger", "house")) {
            Schema<?> union = (Schema<?>) model.getProperties().get(field);
            assertThat(union.get$ref()).as(field).isNull(); assertThat(union.getType()).as(field).isNull(); assertThat(union.getTypes()).as(field).isNull();
            assertThat(union.getAnyOf()).as(field).hasSize(2);
            assertThat(union.getAnyOf().get(0).get$ref()).as(field).isEqualTo(field.equals("swagger") ? "#/components/schemas/TrainingOverride" : "#/components/schemas/RiskReview");
            assertThat(union.getAnyOf().get(1).getTypes()).as(field).containsExactly("null");
        }
        assertThat(((Schema<?>) model.getProperties().get("swagger")).getDescription()).isEqualTo("Impersonation only");
        assertThat(model.getProperties().get("plain")).isSameAs(plain);
        assertThat(model.getProperties().get("text")).isSameAs(text);
        assertThat(((Schema<?>) untouched.getProperties().get("house")).get$ref()).as("a schema that is not listed").isNotNull();
    }
}

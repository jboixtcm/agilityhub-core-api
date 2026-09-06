package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.catalogs.api.CatalogResponses;
import com.agilityhub.core.clubs.census.api.CensusResponses;
import com.agilityhub.core.shared.application.contract.ApiContracts;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the role-specific projections used by the same list operation. */
@Configuration(proxyBeanMethods = false)
public class E2ContractConfiguration {
    @Bean OpenApiCustomizer e2ReaderProjections() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            for (Class<?> type : List.of(ApiContracts.ListPage.class, CensusResponses.MemberInstructorView.class,
                    CatalogResponses.LevelReaderView.class, CatalogResponses.RingReaderView.class,
                    CatalogResponses.InstructorReaderView.class, CatalogResponses.PlanReaderView.class,
                    CatalogResponses.FaqReaderView.class)) {
                ModelConverters.getInstance(true).readAll(type).forEach(schemas::putIfAbsent);
            }
            for (var entry : java.util.Map.of(
                    "ListPageMemberListItem", List.of("MemberListItem", "MemberInstructorView"),
                    "CatalogItemsLevel", List.of("Level", "LevelReaderView"),
                    "CatalogItemsRing", List.of("Ring", "RingReaderView"),
                    "CatalogItemsInstructor", List.of("Instructor", "InstructorReaderView"),
                    "CatalogItemsPlan", List.of("Plan", "PlanReaderView"),
                    "CatalogItemsFaqEntry", List.of("FaqEntry", "FaqReaderView")).entrySet()) {
                var wrapper = schemas.get(entry.getKey());
                if (wrapper == null || wrapper.getProperties() == null) {
                    throw new IllegalStateException("Missing E2 list schema " + entry.getKey());
                }
                var union = new Schema<>();
                entry.getValue().forEach(name -> union.addAnyOfItem(new Schema<>().$ref("#/components/schemas/" + name)));
                union.setDescription("Role-selected projection: ADMIN first, reduced reader second. Exactly one projection is returned per request.");
                ((Schema<?>) wrapper.getProperties().get("items")).setItems(union);
            }
            api.getComponents().addSecuritySchemes("clubApiKey", new io.swagger.v3.oas.models.security.SecurityScheme()
                    .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY)
                    .in(io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER).name("X-Api-Key"));
            api.getPaths().get("/api/v1/public/{clubSlug}/plans").getGet()
                    .setSecurity(List.of(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("clubApiKey")));
        };
    }
}

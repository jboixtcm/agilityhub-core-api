package com.agilityhub.core.configuration;

import com.agilityhub.core.courses.api.CourseContracts;
import com.agilityhub.core.courses.domain.CourseParts;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** E9-T01: impersonation precedes role checks; JSON schemas are loaded locally, never from their ids. */
@Configuration(proxyBeanMethods = false)
public class E9ContractConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var user = CurrentUser.current();
                if (user != null && user.impersonation() != null && handler instanceof HandlerMethod method
                        && method.getBeanType().getPackageName().equals("com.agilityhub.core.courses.api")
                        && !method.hasMethodAnnotation(AllowsImpersonation.class)) {
                    throw new ApiException(ErrorCode.IMPERSONATION_DENIED);
                }
                return true;
            }
        }).order(-10);
    }
    @Bean OpenApiCustomizer e9Schemas() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            for (Class<?> type : List.of(CourseContracts.CourseModelInvalidDetails.class, CourseContracts.PlacementBlockedDetails.class,
                    CourseContracts.MemberRingSetup.class)) {
                ModelConverters.getInstance(true).readAll(type).forEach(schemas::putIfAbsent);
            }
            // Inline the exact planner shapes, with their $ids. Neither schema contains a remote reference.
            schemas.put("CourseDataV1", schema("course-data.v1.schema.json"));
            var exportSchema = schema("build-session-export.v1.schema.json");
            schemas.put("BuildSessionExportV1", exportSchema);
            // The warning is course-core JSON too: keep its required nullable fields and strict property set verbatim.
            Schema<?> placementSchema = exportSchema.getProperties().get("placement");
            Schema<?> warningsSchema = placementSchema.getProperties().get("warnings_json");
            schemas.put("PlacementWarning", warningsSchema.getItems());
            // Jackson's JsonNode would otherwise be published as an untyped object.
            for (String name : List.of("Course", "CourseRequest", "CoursePatchRequest")) {
                var model = schemas.get(name);
                if (model != null) {
                    model.getProperties().put("normalizedJson", new Schema<>().addAnyOfItem(new Schema<>().$ref("#/components/schemas/CourseDataV1"))
                            .addAnyOfItem(new Schema<>().types(java.util.Set.of("null"))));
                }
            }
            var owned = new java.util.ArrayList<String>();
            for (Class<?> owner : List.of(CourseContracts.class, CourseParts.class, com.agilityhub.core.courses.api.CourseRequests.class)) {
                for (Class<?> nested : owner.getDeclaredClasses()) { owned.add(nested.getSimpleName()); }
            }
            new NullableReferences(owned).customise(api);
        };
    }
    private static Schema<?> schema(String name) {
        try (var input = new ClassPathResource("schemas/" + name).getInputStream()) {
            return Json31.mapper().readValue(input, Schema.class);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Unreadable course schema " + name, invalid); }
    }
}

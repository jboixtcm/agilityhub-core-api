package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.scheduling.api.SchedulingContracts;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class E4ContractConfiguration implements WebMvcConfigurer {
    static final List<String> NULLABLE_REFERENCES = List.of("Activity", "ActivityPatchRequest", "ActivityRegistration", "ActivityRegistrationSummary",
            "ClassSession", "ClassSessionMemberView", "MemberActivityDetail");
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var user = CurrentUser.current();
                if (user == null || user.impersonation() == null || !(handler instanceof HandlerMethod method)) { return true; }
                String context = method.getMethod().getDeclaringClass().getPackageName();
                String path = request.getRequestURI();
                boolean scheduling = context.equals("com.agilityhub.core.clubs.scheduling.api");
                boolean activities = context.equals("com.agilityhub.core.clubs.activities.api")
                        || path.equals("/api/v1/activity-registrations/export");
                boolean memberScheduling = request.getMethod().equals("GET") && (path.matches("/api/v1/class-sessions/[^/]+")
                        || path.equals("/api/v1/day-grid") && !"instructor".equals(request.getParameter("view")));
                boolean memberActivities = path.equals("/api/v1/activity-registrations") && request.getMethod().equals("POST")
                        || path.matches("/api/v1/activity-registrations/[^/]+(?:/cancellation)?") && !path.endsWith("/export")
                        || path.equals("/api/v1/me/activities") || path.matches("/api/v1/me/activities/[^/]+");
                if (scheduling && !memberScheduling || activities && !memberActivities) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
                return true;
            }
        }).order(-10);
    }
    @Bean OpenApiCustomizer e4ContractProjections() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            ModelConverters.getInstance(true).readAll(SchedulingContracts.RingBlockMemberView.class).forEach(schemas::putIfAbsent);
            var wrapper = schemas.get("ListPageRingBlock");
            var union = new Schema<>().description("ADMIN/INSTRUCTOR receive RingBlock; MEMBER receives RingBlockMemberView without note/createdByName.");
            union.addAnyOfItem(new Schema<>().$ref("#/components/schemas/RingBlock"));
            union.addAnyOfItem(new Schema<>().$ref("#/components/schemas/RingBlockMemberView"));
            ((Schema<?>) wrapper.getProperties().get("items")).setItems(union);
            Schema<?> empty = schemas.get("EmptyRequest");
            empty.setProperties(new java.util.LinkedHashMap<>());
            empty.setRequired(new java.util.ArrayList<>());
            var tiers = (Schema<?>) schemas.get("Activity").getProperties().get("priceTiers");
            tiers.setMaxItems(0); tiers.setMinItems(0);
            for (String path : List.of("/api/v1/public/{clubSlug}/activities", "/api/v1/public/{clubSlug}/activities/{slug}")) {
                api.getPaths().get(path).getGet().setSecurity(List.of(new SecurityRequirement().addList("clubApiKey")));
            }
        };
    }
    /** OpenAPI 3.1 uses a union, not a null-only sibling constraint on a $ref. */
    @Bean OpenApiCustomizer e4NullableReferences() { return new NullableReferences(NULLABLE_REFERENCES); }
}

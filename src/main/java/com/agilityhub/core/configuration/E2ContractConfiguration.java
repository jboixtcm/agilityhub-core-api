package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.catalogs.api.CatalogResponses;
import com.agilityhub.core.clubs.census.api.CensusResponses;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.contract.ApiContracts;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the role-specific projections used by the same list operation; the E2 schemas' nullable references. E11-T10
 * (INC-38): the E2/E3 staff routes, those whose role check refuses the `imp` claim, answer IMPERSONATION_DENIED to a real
 * impersonation token before that check (R-03-05, T-03-35, S05 §11, S14 §6), as E5–E8 do; their member routes keep serving it.
 */
@Configuration(proxyBeanMethods = false)
public class E2ContractConfiguration implements WebMvcConfigurer {
    static final Set<String> CONTROLLERS = Set.of("com.agilityhub.core.clubs.census.api.MembersController",
            "com.agilityhub.core.clubs.census.api.DogsController", "com.agilityhub.core.clubs.census.api.FamilyGroupsController",
            "com.agilityhub.core.clubs.census.api.SignupController", "com.agilityhub.core.clubs.common.api.SavedViewsController",
            "com.agilityhub.core.clubs.common.api.ExportsController", "com.agilityhub.core.clubs.catalogs.api.CatalogsController",
            "com.agilityhub.core.clubs.catalogs.api.PricesController", "com.agilityhub.core.clubs.catalogs.api.TeamController",
            "com.agilityhub.core.platform.api.SettingsController", "com.agilityhub.core.platform.api.AuditController");
    static final String REFUSES_IMPERSONATION = "principal.claims['imp'] != true";

    /** A staff route of E2/E3: its role check refuses the impersonation claim. */
    static boolean refusesImpersonation(HandlerMethod method) {
        var rule = method.getMethodAnnotation(PreAuthorize.class);
        return CONTROLLERS.contains(method.getMethod().getDeclaringClass().getName()) && rule != null && rule.value().contains(REFUSES_IMPERSONATION);
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var user = CurrentUser.current();
                if (user != null && user.impersonation() != null && handler instanceof HandlerMethod method && refusesImpersonation(method)) {
                    throw new ApiException(ErrorCode.IMPERSONATION_DENIED);
                }
                return true;
            }
        }).order(-10);
    }
    /** E5-T16 (review E3-T16 #1): `GET /club` sends an unset `address`, `pwa`, `legal` or `lastChange` as `null`. */
    static final List<String> NULLABLE_REFERENCES = List.of("ClubSettings");

    @Bean OpenApiCustomizer e2NullableReferences() { return new NullableReferences(NULLABLE_REFERENCES); }

    @Bean OpenApiCustomizer e2ReaderProjections() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            for (Class<?> type : List.of(ApiContracts.ListPage.class, CensusResponses.MemberInstructorView.class, CensusResponses.MemberInstructorListItem.class,
                    CatalogResponses.LevelReaderView.class, CatalogResponses.RingReaderView.class,
                    CatalogResponses.InstructorReaderView.class, CatalogResponses.PlanReaderView.class,
                    CatalogResponses.FaqReaderView.class)) {
                ModelConverters.getInstance(true).readAll(type).forEach(schemas::putIfAbsent);
            }
            for (var entry : java.util.Map.of(
                    "ListPageMemberListItem", List.of("MemberListItem", "MemberInstructorListItem"),
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
            // E5-T14: the plans route declares `@SecurityRequirement(name = "clubApiKey")` itself, like the pages route.
        };
    }
}

package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.api.LifecycleContracts;
import com.agilityhub.core.payments.api.BillingContracts;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * E8 contract (S12, S13). Of the billing, inactivity and leave routes only the member ones marked {@link AllowsImpersonation}
 * (`/me/*`, S12 R-12-27 and S13 R-13-18) accept the impersonation token; every other one answers IMPERSONATION_DENIED before
 * its role check. The signup checkout (E3-T03, `POST /checkout-sessions`) keeps its own rules; its new `GET` accepts the
 * caller's own session. The Stripe webhook is signed, not authenticated by a bearer (`stripeSignature`).
 */
@Configuration(proxyBeanMethods = false)
public class E8ContractConfiguration implements WebMvcConfigurer {
    static final Set<String> CONTROLLERS = Set.of("com.agilityhub.core.payments.api.BillingController",
            "com.agilityhub.core.payments.api.RemittancesController", "com.agilityhub.core.payments.api.InvoicesController",
            "com.agilityhub.core.payments.api.MyBillingController", "com.agilityhub.core.payments.api.UpfrontPaymentsController",
            "com.agilityhub.core.payments.api.PackBalancesController", "com.agilityhub.core.payments.api.MemberBillingController",
            "com.agilityhub.core.payments.api.MemberPlanChangeController",
            "com.agilityhub.core.clubs.census.api.InactivityController", "com.agilityhub.core.clubs.census.api.LeaveController");
    /** The E8 forms with a nullable object or enum reference: published as the union `anyOf [$ref, null]`. */
    static final List<String> NULLABLE_REFERENCES = List.of("InvoicePaymentMethod", "BillingRunResult", "BillingPeriod", "UpfrontPayment",
            "UpfrontPaymentProvider", "InactivityPeriod", "InactivityPeriodListItem", "MeInactivityPeriod", "MeInactivityContext", "LeaveMember",
            "LeaveRequest", "MeLeaveRequest", "MeLeaveContext", "MemberListItem", "MemberOverview");
    static final List<Class<?>> DETAILS = List.of(BillingContracts.RunNotRollbackableDetails.class, BillingContracts.CollectionDateTooSoonDetails.class,
            BillingContracts.MaxAttemptsDetails.class, LifecycleContracts.InactivityDeadlineDetails.class, LifecycleContracts.InactivityOverlapDetails.class,
            LifecycleContracts.MemberLeavingDetails.class);
    static final String STRIPE_WEBHOOK = "/webhooks/stripe/{clubId}";

    static boolean e8(HandlerMethod method) { return CONTROLLERS.contains(method.getMethod().getDeclaringClass().getName()); }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var user = CurrentUser.current();
                if (user == null || user.impersonation() == null || !(handler instanceof HandlerMethod method) || !e8(method)) { return true; }
                if (!method.hasMethodAnnotation(AllowsImpersonation.class)) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
                return true;
            }
        }).order(-10);
    }

    @Bean OpenApiCustomizer e8ContractProjections() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            // Error details (CATALEG_ERRORS §3 rule 2) are published for the generated client even though no operation returns them as a body.
            for (Class<?> type : DETAILS) { ModelConverters.getInstance(true).readAll(type).forEach(schemas::putIfAbsent); }
            // S12 R-12-21: Stripe signs the raw body with the club's webhook secret (HMAC-SHA256, `t=…,v1=…`); no bearer, no host tenant.
            api.getComponents().addSecuritySchemes("stripeSignature", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER).name("Stripe-Signature")
                    .description("Stripe's signature of the raw request body with the club's webhook secret (S12 R-12-21)."));
            api.getPaths().get(STRIPE_WEBHOOK).getPost().setSecurity(List.of(new SecurityRequirement().addList("stripeSignature")));
            // The return screen polls its session anonymously with the signup capability, or with the member's or admin's bearer.
            api.getPaths().get("/api/v1/checkout-sessions/{id}").getGet()
                    .setSecurity(List.of(new SecurityRequirement(), new SecurityRequirement().addList("bearer")));
        };
    }

    /** OpenAPI 3.1 uses a union, not a null-only sibling constraint on a $ref. */
    @Bean OpenApiCustomizer e8NullableReferences() { return new NullableReferences(NULLABLE_REFERENCES); }
}

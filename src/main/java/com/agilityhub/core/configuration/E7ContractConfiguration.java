package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.api.MessagingContracts;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * E7 contract (S11). Of the messaging routes only the ones marked {@link AllowsImpersonation} — feed 11 (`/me/notifications*`)
 * and the preferences of 12 (`/me/notification-preferences`) — accept the impersonation token (T-11-28); every other one
 * answers IMPERSONATION_DENIED before its role check. The SendGrid webhook (E1-T03) is untouched.
 */
@Configuration(proxyBeanMethods = false)
public class E7ContractConfiguration implements WebMvcConfigurer {
    static final Set<String> CONTROLLERS = Set.of("com.agilityhub.core.clubs.messaging.api.MessageTemplatesController",
            "com.agilityhub.core.clubs.messaging.api.NotificationsController", "com.agilityhub.core.clubs.messaging.api.NotificationPreferencesController",
            "com.agilityhub.core.clubs.messaging.api.PushSubscriptionsController", "com.agilityhub.core.clubs.messaging.api.EmailUnsubscribesController");
    static final List<String> NULLABLE_REFERENCES = List.of("MessageTemplateDetail", "TemplatePreview", "MeNotification", "MessageTemplateUpdateRequest",
            "TemplatePreviewRequest", "NotificationPreferencesRequest", "NotificationListItem", "NotificationDetail");
    static final List<Class<?>> DETAILS = List.of(MessagingContracts.MissingVariablesDetails.class, MessagingContracts.ChannelNotAllowedDetails.class,
            MessagingContracts.TemplateFieldDetails.class);

    static boolean e7(HandlerMethod method) { return CONTROLLERS.contains(method.getMethod().getDeclaringClass().getName()); }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var user = CurrentUser.current();
                if (user == null || user.impersonation() == null || !(handler instanceof HandlerMethod method) || !e7(method)) { return true; }
                if (!method.hasMethodAnnotation(AllowsImpersonation.class)) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
                return true;
            }
        }).order(-10);
    }

    @Bean OpenApiCustomizer e7ContractProjections() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            // Error details (CATALEG_ERRORS §3 rule 2) are published for the generated client even though no operation returns them as a body.
            for (Class<?> type : DETAILS) { ModelConverters.getInstance(true).readAll(type).forEach(schemas::putIfAbsent); }
        };
    }

    /** OpenAPI 3.1 uses a union, not a null-only sibling constraint on a $ref. */
    @Bean OpenApiCustomizer e7NullableReferences() { return new NullableReferences(NULLABLE_REFERENCES); }
}

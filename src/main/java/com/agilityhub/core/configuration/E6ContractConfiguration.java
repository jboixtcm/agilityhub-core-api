package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.api.InstructorContracts;
import com.agilityhub.core.clubs.followup.api.FollowupContracts;
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
 * E6 contract (S10). The attendance/instructor routes live in `clubs.bookings.api` and are covered by
 * {@link E5ContractConfiguration}; here the S10 follow-up controllers: only the routes marked {@link AllowsImpersonation}
 * (`GET /tasks`, the completion) accept the impersonation token, the rest answer IMPERSONATION_DENIED (S10 §6).
 * `AttachmentsController` decides per entity in code (R-10-11: the member's INSTRUCTOR_NOTE accepts it).
 */
@Configuration(proxyBeanMethods = false)
public class E6ContractConfiguration implements WebMvcConfigurer {
    static final Set<String> CONTROLLERS = Set.of("com.agilityhub.core.clubs.followup.api.TasksController",
            "com.agilityhub.core.clubs.followup.api.FollowupController");
    static final List<String> NULLABLE_REFERENCES = List.of("InstructorDayClass", "SheetClassSession", "AttendanceRow", "InstructorCard", "TasksBlock",
            "HistoryItem", "Task");
    static final List<Class<?>> DETAILS = List.of(InstructorContracts.StaleAttendanceDetails.class, InstructorContracts.AttendanceBookingNotActiveDetails.class,
            InstructorContracts.AttendanceWindowClosedDetails.class, FollowupContracts.FileTooLargeDetails.class, FollowupContracts.AttachmentLimitReachedDetails.class);

    static boolean e6(HandlerMethod method) { return CONTROLLERS.contains(method.getMethod().getDeclaringClass().getName()); }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var user = CurrentUser.current();
                if (user == null || user.impersonation() == null || !(handler instanceof HandlerMethod method) || !e6(method)) { return true; }
                if (!method.hasMethodAnnotation(AllowsImpersonation.class)) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
                return true;
            }
        }).order(-10);
    }

    @Bean OpenApiCustomizer e6ContractProjections() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            // Error details (CATALEG_ERRORS §3 rule 2) are published for the generated client even though no operation returns them as a body.
            for (Class<?> type : DETAILS) { ModelConverters.getInstance(true).readAll(type).forEach(schemas::putIfAbsent); }
        };
    }

    /** OpenAPI 3.1 uses a union, not a null-only sibling constraint on a $ref. */
    @Bean OpenApiCustomizer e6NullableReferences() { return new NullableReferences(NULLABLE_REFERENCES); }
}

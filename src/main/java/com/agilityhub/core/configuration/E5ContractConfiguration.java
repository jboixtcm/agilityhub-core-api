package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.api.BookingContracts;
import com.agilityhub.core.clubs.training.api.TrainingContracts;
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
 * E5 contract (S08, S09, S15): only the member routes marked {@link AllowsImpersonation} accept the impersonation
 * token (R-08-19, R-09-16); every other E5 route answers IMPERSONATION_DENIED (R-15-09), as in E4.
 */
@Configuration(proxyBeanMethods = false)
public class E5ContractConfiguration implements WebMvcConfigurer {
    static final Set<String> PACKAGES = Set.of("com.agilityhub.core.clubs.bookings.api", "com.agilityhub.core.clubs.training.api");
    static final Set<String> CONTROLLERS = Set.of("com.agilityhub.core.clubs.common.api.JobsController",
            "com.agilityhub.core.platform.api.PlatformJobsController");
    /** E5-T16 (review E3-T16 #1): `TrainingBookingRequest.override` is a `TrainingOverride` or `null` (R-09-16). */
    static final List<String> NULLABLE_REFERENCES = List.of("MeHome", "BookableClasses", "BookableClass", "SeatHoldResponse", "Booking",
            "TrainingRing", "SlotCell", "TrainingSummary", "TrainingBooking", "JobRun", "JobSummary", "PlatformJobCell", "TrainingBookingRequest");
    static final List<Class<?>> DETAILS = List.of(BookingContracts.BookingLimitReachedDetails.class, BookingContracts.ClassFullDetails.class,
            BookingContracts.NotYetOpenDetails.class, BookingContracts.WaitlistLimitDetails.class, BookingContracts.InactivityPeriodDetails.class,
            TrainingContracts.TrainingLimitReachedDetails.class, TrainingContracts.SlotTakenDetails.class, TrainingContracts.SlotOutOfWindowDetails.class,
            TrainingContracts.TrainingCancelTooLateDetails.class, TrainingContracts.RingHasBookingsDetails.class);

    static boolean e5(HandlerMethod method) {
        var type = method.getMethod().getDeclaringClass();
        return PACKAGES.contains(type.getPackageName()) || CONTROLLERS.contains(type.getName());
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var user = CurrentUser.current();
                if (user == null || user.impersonation() == null || !(handler instanceof HandlerMethod method) || !e5(method)) { return true; }
                if (!method.hasMethodAnnotation(AllowsImpersonation.class)) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
                return true;
            }
        }).order(-10);
    }

    @Bean OpenApiCustomizer e5ContractProjections() {
        return api -> {
            var schemas = api.getComponents().getSchemas();
            // Error details (CATALEG_ERRORS §3 rule 2) are published for the generated client even though no operation returns them as a body.
            for (Class<?> type : DETAILS) { ModelConverters.getInstance(true).readAll(type).forEach(schemas::putIfAbsent); }
            // The .ics link authenticates with its signed token; the club comes from the host.
            api.getPaths().get("/api/v1/bookings/{id}/calendar.ics").getGet().setSecurity(List.of());
        };
    }

    /** OpenAPI 3.1 uses a union, not a null-only sibling constraint on a $ref. */
    @Bean OpenApiCustomizer e5NullableReferences() { return new NullableReferences(NULLABLE_REFERENCES); }
}

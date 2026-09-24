package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.dashboard.application.ports.*;
import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.domain.WeekCalendarRules;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class SchedulingDashboard {
    @Bean ClassOccupancyQuery schedulingOccupancy(ClassSessionRepository sessions) {
        return (club,from,to) -> {
            requireTenant(club);
            return sessions.between(from,to).stream().filter(c -> !c.startsAt().isBefore(from) && (c.state()==ClassState.ACTIVE || c.state()==ClassState.FINISHED))
                    .map(c -> new ClassOccupancyQuery.Session(c.startsAt(),c.state().name(),c.capacity(),c.counters().booked(),c.counters().waiting())).toList();
        };
    }
    /**
     * S14 D1 risk card = the S15 §6 form-A rows of {@link RiskReviewQuery} (statuses, recount, names), localized in the
     * three product languages; a cancelled row's `reviewAt` is the review that cancelled it (S14 requires the field).
     */
    @Bean RiskReviewSource schedulingRiskReview(SessionProjection projection,RiskReviewQuery risk) {
        return (club,today) -> {
            requireTenant(club); var rows=risk.rows(today); var reviewTime=LocalTime.parse(rows.reviewTime());
            return rows.rows().stream().map(r -> {
                var c=r.session();
                var reviewAt=r.reviewAt()!=null?r.reviewAt():WeekCalendarRules.resolve(c.date(),reviewTime,projection.zone()).instant();
                return new RiskReviewSource.Row(c.id(),c.date(),LocalTime.parse(c.startTime()),projection.descriptions(c),
                        projection.ringNames(r.ringName()),r.bookedCount(),r.status(),names(r.notified()),reviewAt);
            }).toList();
        };
    }
    private static List<com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified> names(List<RiskReviewQuery.Notified> notified) {
        return notified.stream().map(n -> new com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified(n.memberName(),n.gender(),n.dogName())).toList();
    }
    private static void requireTenant(String club) { if(!TenantContext.require().equals(club)) throw new ApiException(ErrorCode.TENANT_MISMATCH); }
}

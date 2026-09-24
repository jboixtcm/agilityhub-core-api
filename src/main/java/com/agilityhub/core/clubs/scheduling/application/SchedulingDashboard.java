package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.dashboard.application.ports.*;
import com.agilityhub.core.clubs.scheduling.domain.ClassState;
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
    /** S14 D1 risk card: the notified names come from the same S15 resolution as `GET /risk-review` (no double left). */
    @Bean ClassSessionsQuery schedulingSessions(ClassSessionRepository sessions,SessionProjection projection,PlanningContext context,RiskReviewQuery risk) {
        return (club,from,through) -> {
            requireTenant(club); var catalog=context.catalog();
            return sessions.between(from.atStartOfDay(projection.zone()).toInstant(),through.plusDays(1).atStartOfDay(projection.zone()).toInstant()).stream().map(c -> {
                var descriptions=new LinkedHashMap<String,String>(); for(String tag:List.of("ca","es","en")) descriptions.put(tag,projection.description(c,Locale.forLanguageTag(tag)));
                var ring=catalog.rings().stream().filter(r -> r.id().equals(c.ringId())).map(r -> r.name()).findFirst().orElse("—");
                boolean autoCancelled=c.state()==ClassState.CANCELLED && c.cancellation()!=null && c.cancellation().reason()==com.agilityhub.core.clubs.scheduling.domain.ClassCancellationReason.RISK_REVIEW;
                var riskNotified=c.state()==ClassState.ACTIVE?names(risk.riskNotified(c)):List.<com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified>of();
                var cancellationNotified=autoCancelled?names(risk.cancellationNotified(c)):List.<com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified>of();
                return new ClassSessionsQuery.Session(c.id(),c.date(),LocalTime.parse(c.startTime()),new LocalizedText(descriptions,"ca"),new LocalizedText(Map.of("ca",ring),"ca"),
                        c.state().name(),c.cancellation()==null?null:c.cancellation().reason().name(),c.risk().exempt(),c.counters().booked(),
                        c.risk().adminNotifiedAt()!=null || !riskNotified.isEmpty(),cancellationNotified,riskNotified);
            }).toList();
        };
    }
    private static List<com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified> names(List<RiskReviewQuery.Notified> notified) {
        return notified.stream().map(n -> new com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified(n.memberName(),n.gender(),n.dogName())).toList();
    }
    @Bean RiskEvaluator schedulingRisk(ClassSessionRepository sessions,SessionProjection projection) {
        return session -> sessions.findById(session.id()).map(c -> projection.risk(c).atRisk()).orElse(false);
    }
    private static void requireTenant(String club) { if(!TenantContext.require().equals(club)) throw new ApiException(ErrorCode.TENANT_MISMATCH); }
}

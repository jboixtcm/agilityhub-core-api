package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.dashboard.application.ports.PendingRequestsQuery;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriodRepository;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequestRepository;
import com.agilityhub.core.clubs.census.domain.LeaveRequestState;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.context.annotation.*;

@Configuration
public class LifecycleDashboardConfiguration {
    @Bean PendingRequestsQuery lifecyclePendingRequests(InactivityPeriodRepository periods, LeaveRequestRepository leaves, ClubConfigService configs) {
        return club -> { try (var t = TenantContext.open(club)) {
            int inactivity = configs.get(club).modules().contains(Module.INACTIVITY) ? periods.inStates("REQUESTED").size() : 0;
            return new PendingRequestsQuery.Counts(inactivity, (int) leaves.findAll().stream().filter(r -> r.state() == LeaveRequestState.PENDING).count());
        } };
    }
}

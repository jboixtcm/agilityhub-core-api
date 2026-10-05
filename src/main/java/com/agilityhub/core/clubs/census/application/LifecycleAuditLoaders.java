package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriodRepository;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequestRepository;
import com.agilityhub.core.platform.application.audit.AuditableLoader;
import org.springframework.context.annotation.*;

@Configuration
public class LifecycleAuditLoaders {
    @Bean AuditableLoader inactivityAuditLoader(InactivityPeriodRepository periods) {
        return new AuditableLoader() {
            public String entityType() { return "InactivityPeriod"; }
            public Object load(String id) { return periods.findById(id).orElse(null); }
        };
    }
    @Bean AuditableLoader leaveAuditLoader(LeaveRequestRepository requests) {
        return new AuditableLoader() {
            public String entityType() { return "LeaveRequest"; }
            public Object load(String id) { return requests.findById(id).orElse(null); }
        };
    }
}

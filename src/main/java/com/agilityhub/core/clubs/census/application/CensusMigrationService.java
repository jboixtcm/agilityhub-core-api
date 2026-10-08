package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusMigrationRepository;
import com.agilityhub.core.clubs.census.persistence.leave.*;
import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

/** Internal import boundary; public signup validators intentionally do not apply to legacy census data. */
@Service
public class CensusMigrationService {
    private final CensusMigrationRepository repository;
    private final LeaveRequestRepository leaves;
    private final Clock clock;
    public CensusMigrationService(CensusMigrationRepository repository, LeaveRequestRepository leaves, Clock clock) {
        this.repository=repository; this.leaves=leaves; this.clock=clock;
    }
    public List<Map<String,Object>> snapshot(String entity) { return repository.snapshot(entity); }
    public void lock() { repository.lock(); }
    public void member(String id, Map<String,Object> fields) { repository.write("members",id,fields); }
    public void dog(String id, Map<String,Object> fields) { repository.write("dogs",id,fields); }
    public void group(String id, Map<String,Object> fields) { repository.write("family_groups",id,fields); }
    /** R-18-11: one silent import record, covered by the enclosing MIGRATION_APPLIED audit and census transaction. */
    public void plannedLeave(String id, String memberId, String effectiveDate) {
        if (leaves.findById(id).isPresent()) { return; }
        var now = clock.instant();
        leaves.insert(new LeaveRequest(id, TenantContext.require(), memberId, LeaveSource.MIGRATED, LifecycleOrigin.SYSTEM, now,
                null, effectiveDate, null, null, null, LeaveRequestState.APPROVED,
                new LeaveRequest.Decision(now, null, LifecycleDecision.APPROVED, effectiveDate, null),
                null, null, null, null, List.of(), null, null, now, now));
    }
}

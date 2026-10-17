package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.CensusMigrationRepository;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequest;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequestRepository;
import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * INC-57 (E11-T02; review of E8-T13 #3): a migrated planned leave (S18 R-18-11) is a leave write like the others, so the
 * dashboard's menu counters are dropped once it commits (S14 T-14-29, «every write»), on insert and on update alike.
 */
class CensusMigrationServiceTest {
    final LeaveRequestRepository leaves = mock(LeaveRequestRepository.class);
    final DashboardQuery dashboard = mock(DashboardQuery.class);
    final CensusMigrationService service = new CensusMigrationService(mock(CensusMigrationRepository.class), leaves,
            Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC), dashboard);

    @Test void T_14_29_INC_57_aMigratedPlannedLeaveDropsTheMenuCountersOnInsertAndUpdate() {
        try (var tenant = TenantContext.open("club-a")) {
            when(leaves.findById("leave-1")).thenReturn(Optional.empty());
            when(leaves.insert(any(LeaveRequest.class))).thenAnswer(call -> call.getArgument(0));
            service.plannedLeave("leave-1", "member-1", "2026-11-30");
            verify(dashboard).invalidateCountersAfterCommit("club-a");

            // The stored import (as Mongo keeps it, version 0): a new date updates it.
            var at = Instant.parse("2026-10-16T08:00:00Z");
            var stored = new LeaveRequest("leave-1", "club-a", "member-1", LeaveSource.MIGRATED, LifecycleOrigin.SYSTEM, at, null, "2026-11-30", null, null, null,
                    LeaveRequestState.APPROVED, new LeaveRequest.Decision(at, null, LifecycleDecision.APPROVED, "2026-11-30", null), null, null, null, null,
                    java.util.List.of(), null, 0L, at, at);
            when(leaves.findById("leave-1")).thenReturn(Optional.of(stored));
            when(leaves.findOwn("leave-1", "member-1")).thenReturn(Optional.of(stored));
            when(leaves.save(any(LeaveRequest.class), anyLong())).thenAnswer(call -> call.getArgument(0));
            service.plannedLeave("leave-1", "member-1", "2026-12-31");
            verify(leaves).save(any(LeaveRequest.class), eq(0L));
            verify(dashboard, times(2)).invalidateCountersAfterCommit("club-a");
            // The same date again writes nothing, so nothing is dropped.
            service.plannedLeave("leave-1", "member-1", "2026-11-30");
            verify(dashboard, times(2)).invalidateCountersAfterCommit("club-a");
        }
    }
}

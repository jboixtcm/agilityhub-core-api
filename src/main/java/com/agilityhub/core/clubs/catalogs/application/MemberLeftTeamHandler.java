package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.events.MemberStatusChanged;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component("teamMemberLeft")
public class MemberLeftTeamHandler implements DomainEventHandler<MemberStatusChanged> {
    private final RoleAssignmentService roles;
    private final ClubClock clock;
    public MemberLeftTeamHandler(RoleAssignmentService roles, ClubClock clock) { this.roles = roles; this.clock = clock; }
    @Override public String eventType() { return "MemberStatusChanged"; }
    @Override public Class<MemberStatusChanged> eventClass() { return MemberStatusChanged.class; }
    @Override public void handle(String eventId, MemberStatusChanged event) {
        if (!"LEFT".equals(event.payload().get("after"))) { return; }
        // S13 also publishes scheduled leave dates; it emits the effective transition when due.
        Object date = event.payload().get("effectiveDate");
        if (date != null && LocalDate.parse(date.toString()).isAfter(clock.today(event.clubId()))) { return; }
        try (var tenant = TenantContext.open(event.clubId())) { roles.memberLeft((String) event.payload().get("memberId")); }
    }
}

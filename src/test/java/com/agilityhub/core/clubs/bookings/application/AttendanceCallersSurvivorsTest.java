package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AttendanceCallers} (S10 R-10-01, T-10-09): without an instructor profile the caller is
 * named by the request's user name. The staff endpoints always run inside the {@link CurrentUser} that
 * `CurrentUserFilter` opens for a bearer token, whose `name` claim the issuer always sets (`TokenService`).
 */
class AttendanceCallersSurvivorsTest {
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final AttendanceCallers callers = new AttendanceCallers(catalogs);

    @BeforeEach void setUp() {
        when(catalogs.instructorRefs()).thenReturn(List.of(new PlanningCatalogAccess.InstructorRef("instructor-marc", "Marc", "member-marc", true),
                new PlanningCatalogAccess.InstructorRef("instructor-neus", "Neus", "member-neus", true)));
    }

    @Test void T_10_09_withoutAProfileTheCallerIsTheUsersName() {
        // A club ADMIN provisioned without a census member (MembershipService#setRoles keeps memberId null): no `memberId`
        // nor `instructorId` claim; CurrentUserFilter gives an ADMIN token the BACKOFFICE origin.
        try (var user = CurrentUser.open(new CurrentUser("account-anna", "Anna Admin", null, DomainEvent.Origin.BACKOFFICE))) {
            var named = callers.resolve("account-anna", null, null, true);
            assertThat(named.displayName()).isEqualTo("Anna Admin");
            assertThat(named.instructorId()).isNull();
        }
    }
}

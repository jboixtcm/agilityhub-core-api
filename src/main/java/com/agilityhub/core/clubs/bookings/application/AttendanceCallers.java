package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.shared.application.CurrentUser;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@link AttendanceCaller} of a staff request. The caller's `Instructor` is the token's `instructorId`
 * (`Membership.instructorId`, R-10-01) when it names an instructor of the club, otherwise the instructor whose census
 * member is the caller's; the name is that instructor's `shortName`, or the account's name without a profile.
 */
@Service
public class AttendanceCallers {
    private final PlanningCatalogAccess catalogs;
    public AttendanceCallers(PlanningCatalogAccess catalogs) { this.catalogs = catalogs; }

    public AttendanceCaller resolve(String accountId, String memberId, String instructorIdClaim, boolean admin) {
        var instructors = catalogs.instructorRefs();
        var own = instructors.stream().filter(i -> Objects.equals(i.id(), instructorIdClaim)).findFirst()
                .or(() -> memberId == null ? java.util.Optional.empty() : instructors.stream().filter(i -> memberId.equals(i.memberId()))
                        .min(Comparator.comparing(PlanningCatalogAccess.InstructorRef::id)));
        var user = CurrentUser.current();
        String name = own.map(PlanningCatalogAccess.InstructorRef::shortName).orElse(user == null || user.name() == null ? accountId : user.name());
        return new AttendanceCaller(accountId, name, admin, own.map(PlanningCatalogAccess.InstructorRef::id).orElse(null));
    }
}

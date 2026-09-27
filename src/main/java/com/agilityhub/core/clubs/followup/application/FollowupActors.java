package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.persistence.Task;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The author of a follow-up write (`createdBy`, `doneBy`, `deletedBy`, D14 `authorName`, R-10-10). Staff: the ADMIN or
 * INSTRUCTOR role and the name the sheets use (R-10-01): the caller's `Instructor.shortName` — the token's `instructorId`,
 * or the instructor whose census member is the caller's — or the account's name without a profile. A member (also
 * impersonated, as the member): the owner's first name and gender («feta per la Laura», §6).
 */
@Service
public class FollowupActors {
    private final PlanningCatalogAccess catalogs; private final FollowupCensusAccess census;
    public FollowupActors(PlanningCatalogAccess catalogs, FollowupCensusAccess census) { this.catalogs = catalogs; this.census = census; }

    public Task.Actor actor(FollowupContractAccess.Caller caller, String memberClaim, String instructorClaim) {
        var user = CurrentUser.current();
        if (user == null) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        if (!caller.staff()) {
            var member = caller.memberId() == null ? null : census.members(List.of(caller.memberId())).get(caller.memberId());
            return new Task.Actor(user.accountId(), AuthorRole.MEMBER, member == null ? user.name() : member.firstName(), member == null ? null : member.gender());
        }
        return new Task.Actor(user.accountId(), admin() ? AuthorRole.ADMIN : AuthorRole.INSTRUCTOR,
                instructor(memberClaim, instructorClaim).map(PlanningCatalogAccess.InstructorRef::shortName).orElse(user.name() == null ? user.accountId() : user.name()));
    }
    private Optional<PlanningCatalogAccess.InstructorRef> instructor(String memberClaim, String instructorClaim) {
        var instructors = catalogs.instructorRefs();
        return instructors.stream().filter(i -> Objects.equals(i.id(), instructorClaim)).findFirst()
                .or(() -> memberClaim == null ? Optional.empty() : instructors.stream().filter(i -> memberClaim.equals(i.memberId()))
                        .min(Comparator.comparing(PlanningCatalogAccess.InstructorRef::id)));
    }
    static boolean admin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(granted -> granted.getAuthority().equals("ROLE_ADMIN"));
    }
}

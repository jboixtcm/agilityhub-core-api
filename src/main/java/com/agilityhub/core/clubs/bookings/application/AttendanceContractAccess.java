package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.SchedulingContractAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListDefinition.Field;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * Read-only tenant, resource and list guards of the reserved S10 attendance, instructor and history operations
 * (E6-T01); no side effects. E6-T02 serves the operations behind them.
 */
@Service
public class AttendanceContractAccess {
    /** `GET /attendances` (S10 §6): the universal list allowlist of CONVENCIONS_API §4. */
    public static final ListDefinition ATTENDANCES = new ListDefinition("attendances",
            Map.of("dogId", new Field("dogId", Type.TEXT), "memberId", new Field("memberId", Type.TEXT),
                    "classSessionId", new Field("classSessionId", Type.TEXT), "classDate", new Field("classDate", Type.DATE),
                    "state", new Field("state", Type.TEXT)),
            Map.of("classStartsAt", "classStartsAt", "classDate", "classDate"), List.of(),
            List.of("classStartsAt", "classDate", "dogName", "memberName", "state", "markedAt", "markedByName"),
            List.of("classStartsAt", "dogName", "memberName", "state"), List.of("classStartsAt,desc"),
            Set.of("id", "bookingId", "classSessionId", "classDate", "classStartsAt", "dogId", "dogName", "memberId", "memberName",
                    "state", "markedAt", "markedByName"));
    private final SchedulingContractAccess scheduling; private final BookingMemberAccess census; private final PlanningCatalogAccess catalogs;
    public AttendanceContractAccess(SchedulingContractAccess scheduling, BookingMemberAccess census, PlanningCatalogAccess catalogs) {
        this.scheduling = scheduling; this.census = census; this.catalogs = catalogs;
    }
    public void tenant() { TenantContext.require(); }
    public void classSession(String id) { scheduling.classSession(id); }
    /** Staff read every dog of the club (MATRIU rule 1); another club's dog is 404. */
    public void dog(String id) { census.dog(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    /** `/me/history?dogId`: own or family-group dog, otherwise 404 DOG_NOT_ACCESSIBLE (R-10-14). */
    public void accessibleDog(String memberId, String dogId) {
        if (dogId == null) { return; }
        var dog = census.dog(dogId).orElse(null);
        if (dog == null || memberId == null || !census.canAccess(memberId, dog)) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
    }
    /** An instructor or ring filter of another club (or unknown) is 404; `me` is the caller's own profile. */
    public void filters(String instructorId, String ringId) {
        var snapshot = catalogs.snapshot();
        if (instructorId != null && !instructorId.equals("me") && snapshot.instructors().stream().noneMatch(i -> i.id().equals(instructorId))) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        if (ringId != null && snapshot.rings().stream().noneMatch(r -> r.id().equals(ringId))) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    /** An undeclared filter, operator or sort is 400 INVALID_FILTER before anything else (T-10-21). */
    public void attendances(MultiValueMap<String, String> params) { ListQuery.parse(ATTENDANCES, params); }
}

package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.SchedulingContractAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E6-T01 guards of the S10 attendance, instructor and history stubs; read-only (T-10-21, T-10-22 contract halves). */
class AttendanceContractAccessTest {
    private final SchedulingContractAccess scheduling = mock(SchedulingContractAccess.class);
    private final BookingMemberAccess census = mock(BookingMemberAccess.class);
    private final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    private final AttendanceContractAccess access = new AttendanceContractAccess(scheduling, census, catalogs);
    private static final BookingMemberAccess.Dog DOG = new BookingMemberAccess.Dog("dog-a", "Example", "FEMALE", "member-a", null, "ACTIVE");

    @Test void T_10_22_resourcesOfAnotherClubAreNotFoundAndHistoryDogsMustBeAccessible() {
        try (var tenant = TenantContext.open("club-a")) { access.tenant(); }
        assertThatThrownBy(access::tenant).isInstanceOf(ApiException.class);
        access.classSession("class-a"); verify(scheduling).classSession("class-a");
        when(census.dog("dog-a")).thenReturn(Optional.of(DOG));
        when(census.dog("dog-b")).thenReturn(Optional.empty());
        access.dog("dog-a");
        assertThatThrownBy(() -> access.dog("dog-b")).hasMessage("NOT_FOUND");
        access.accessibleDog("member-a", null);
        when(census.canAccess("member-a", DOG)).thenReturn(true);
        access.accessibleDog("member-a", "dog-a");
        assertThatThrownBy(() -> access.accessibleDog("member-b", "dog-a")).hasMessage("DOG_NOT_ACCESSIBLE");
        assertThatThrownBy(() -> access.accessibleDog(null, "dog-a")).hasMessage("DOG_NOT_ACCESSIBLE");
        assertThatThrownBy(() -> access.accessibleDog("member-a", "dog-b")).hasMessage("DOG_NOT_ACCESSIBLE");
    }

    @Test void T_10_09_T_10_20_instructorAndRingFiltersMustBelongToTheClub() {
        when(catalogs.snapshot()).thenReturn(new PlanningCatalogAccess.Snapshot(List.of(),
                List.of(new PlanningCatalogAccess.ResourceView("ring-a", "Central", true)), List.of(new PlanningCatalogAccess.ResourceView("instructor-a", "Estel", true))));
        access.filters(null, null);
        access.filters("me", "ring-a");
        access.filters("instructor-a", null);
        assertThatThrownBy(() -> access.filters("instructor-b", null)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> access.filters(null, "ring-b")).hasMessage("NOT_FOUND");
    }

    @Test void T_10_21_attendancesAcceptOnlyTheDeclaredFiltersAndSorts() {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("filter", "state:eq:NO_SHOW"); params.add("filter", "classDate:gte:2026-08-01"); params.add("sort", "classDate,desc");
        access.attendances(params);
        for (String filter : List.of("markedByName:eq:Estel", "state:gt", "classDate:contains:2026")) {
            var invalid = new LinkedMultiValueMap<String, String>(); invalid.add("filter", filter);
            assertThatThrownBy(() -> access.attendances(invalid)).as(filter).hasMessage("INVALID_FILTER");
        }
        var sort = new LinkedMultiValueMap<String, String>(); sort.add("sort", "dogName,asc");
        assertThatThrownBy(() -> access.attendances(sort)).hasMessage("INVALID_FILTER");
        assertThat(AttendanceContractAccess.ATTENDANCES.filters()).containsOnlyKeys("dogId", "memberId", "classSessionId", "classDate", "state");
    }
}

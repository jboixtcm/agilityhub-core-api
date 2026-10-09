package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link MemberDogs} (S08 R-08-23; T-08-12): the member's own dogs are ordered by name, not by id.
 */
class MemberDogsSurvivorsTest {
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final MemberDogs dogs = new MemberDogs(census, catalogs);

    @Test void T_08_12_ownDogsAreOrderedByName() {
        // Toby was registered first (its id sorts first), Bruc later.
        when(census.accessibleDogs("member-laura")).thenReturn(List.of(
                new BookingMemberAccess.Dog("dog-1", "Toby", "MALE", "member-laura", null, "ACTIVE"),
                new BookingMemberAccess.Dog("dog-2", "Bruc", "MALE", "member-laura", null, "ACTIVE")));

        assertThat(dogs.chips("member-laura")).extracting(MemberDogs.Chip::id).containsExactly("dog-2", "dog-1");
    }
}

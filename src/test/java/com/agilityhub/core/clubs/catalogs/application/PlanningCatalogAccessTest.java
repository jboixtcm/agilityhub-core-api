package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.persistence.CatalogRepository;
import com.agilityhub.core.clubs.catalogs.persistence.Instructor;
import com.agilityhub.core.clubs.catalogs.persistence.InstructorRepository;
import com.agilityhub.core.shared.application.TeamMemberAccess;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** INC-53 point 8 (E11-T02; E5-W05 round 2 question 1, decision E82): the demo's «instructor index» follows the census member order. */
class PlanningCatalogAccessTest {
    static Instructor instructor(String id, String memberId, boolean active) {
        return new Instructor(id, "club-a", memberId, "Short " + id, "#000000", active, 0, null, null, null, null);
    }

    /**
     * The demo members' ids are name-based UUIDs of the club id, so their text order changes with the club: «instructor 0» was
     * `instructor@`, `instructor.2@` or `instructor.3@`. By member number it is always the first instructor of the seed's list.
     */
    @SuppressWarnings("unchecked")
    @Test void INC_53_theActiveInstructorsAreInTheirMembersNumberOrder() {
        var instructors = mock(InstructorRepository.class); var members = mock(TeamMemberAccess.class);
        when(instructors.findAll()).thenReturn(List.of(instructor("i-c", "a-member", true), instructor("i-a", "f-member", true),
                instructor("i-gone", "0-member", false), instructor("i-b", "c-member", true)));
        when(members.memberNumbers(any())).thenAnswer(call -> {
            assertThat((java.util.Collection<String>) call.getArgument(0)).containsExactlyInAnyOrder("a-member", "f-member", "c-member");
            return Map.of("f-member", 3, "c-member", 4, "a-member", 5);
        });
        var access = new PlanningCatalogAccess(mock(CatalogRepository.class), mock(CatalogRepository.class), instructors, members);
        assertThat(access.activeInstructorIds()).containsExactly("i-a", "i-b", "i-c");
        verify(members).memberNumbers(any());
    }
}

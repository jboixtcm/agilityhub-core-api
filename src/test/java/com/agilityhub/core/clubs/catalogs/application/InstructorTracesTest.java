package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.persistence.CatalogErasureRepository;
import com.agilityhub.core.clubs.catalogs.persistence.Instructor;
import com.agilityhub.core.platform.application.privacy.ErasureDirectory;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.PrivacyTraces;
import com.agilityhub.core.platform.persistence.privacy.PrivacyTraceRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * S14 R-14-15 / S15 R-15-19 (E11-T16 round 2, the kit's #1): the monthly sweep's item for the late `InstructorChanged` records
 * of an erased member's instructor profile — `due` lists the profiles that still have one, `apply` deletes them and refuses an
 * active instructor, an unknown profile and one without a member.
 */
class InstructorTracesTest {
    static final Instant AT = Instant.parse("2026-10-01T04:00:00Z");
    final ErasureDirectory directory = mock(ErasureDirectory.class);
    final CatalogErasureRepository erasure = mock(CatalogErasureRepository.class);
    final PrivacyTraceRepository repository = mock(PrivacyTraceRepository.class);
    final InstructorTraces task = new InstructorTraces(directory, erasure, new PrivacyTraces(repository, directory));

    static ErasureMember member(String id, Instant erasedAt) {
        return new ErasureMember(id, id + "-account", 1, erasedAt == null ? "ACTIVE" : "LEFT", LocalDate.of(2020, 8, 31), null, null, erasedAt, List.of());
    }
    static Instructor instructor(String id, String memberId) {
        return new Instructor(id, "club-a", memberId, "Fictional " + id, "#ff7a00", true, 1L, AT, AT, "admin-account", "admin-account");
    }

    @Test void R_15_19_R_14_15_theSweepListsTheErasedInstructorsWithProcessedRecordsAndPurgesOnlyThose() {
        assertThat(task.entityType()).isEqualTo("Instructor");
        assertThat(task.action()).isEqualTo("PURGE_ERASED_TRACES");
        assertThat(task.counter()).isEqualTo("erasedInstructorTracesPurged");
        when(directory.erased()).thenReturn(List.of(member("erased", AT), member("clean", AT)));
        when(erasure.instructorsOf(List.of("erased", "clean"))).thenReturn(List.of(instructor("instructor-erased", "erased"), instructor("instructor-clean", "clean")));
        when(repository.withProcessedEvents("InstructorChanged", List.of("instructor-erased", "instructor-clean"))).thenReturn(new TreeSet<>(Set.of("instructor-erased")));
        assertThat(task.due(null)).containsExactly("instructor-erased");

        when(erasure.instructor("instructor-erased")).thenReturn(Optional.of(instructor("instructor-erased", "erased")));
        when(directory.member("erased")).thenReturn(Optional.of(member("erased", AT)));
        when(repository.deleteProcessedEvents("InstructorChanged", List.of("instructor-erased"))).thenReturn(2L, 0L);
        assertThat(task.apply(null, "instructor-erased")).isTrue();
        assertThat(task.apply(null, "instructor-erased")).as("a second sweep has nothing left").isFalse();

        // An active instructor, an unknown profile and a profile without a member are never the sweep's.
        when(erasure.instructor("instructor-active")).thenReturn(Optional.of(instructor("instructor-active", "active")));
        when(directory.member("active")).thenReturn(Optional.of(member("active", null)));
        when(erasure.instructor("instructor-external")).thenReturn(Optional.of(instructor("instructor-external", null)));
        when(erasure.instructor("gone")).thenReturn(Optional.empty());
        for (String id : List.of("instructor-active", "instructor-external", "gone")) { assertThat(task.apply(null, id)).isFalse(); }
        verify(repository, never()).deleteProcessedEvents("InstructorChanged", List.of("instructor-active"));
    }

    @Test void R_15_19_noErasedMemberMeansNothingToRead() {
        when(directory.erased()).thenReturn(List.of());
        assertThat(task.due(null)).isEmpty();
        verifyNoInteractions(erasure, repository);
    }
}

package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.CensusForeignEvent;
import com.agilityhub.core.clubs.followup.domain.FollowupKind;
import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S10 §7 consumers of the D14 projection (E6-T03 step 5), without Mongo: the MEMBER_NOTE upsert and its «unread again for
 * everyone but the author», an older replay that only refreshes the excerpt, the author's fallback, and `DogTransferred`.
 */
class FollowupProjectionTest {
    static final Instant NOW = Instant.parse("2026-08-12T16:00:00Z");
    private final FollowupItemRepository items = mock(FollowupItemRepository.class);
    private final FollowupReadMarkRepository marks = mock(FollowupReadMarkRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final FollowupCensusAccess census = mock(FollowupCensusAccess.class);
    private final DashboardQuery dashboard = mock(DashboardQuery.class);
    private final FollowupProjection projection = new FollowupProjection(items, marks, tasks, census, dashboard, Clock.fixed(NOW, ZoneOffset.UTC));

    private static CensusForeignEvent event(String type, Instant at, Map<String, Object> payload) {
        return new CensusForeignEvent(type, "club-a", "Dog", "dog-a", at, payload, null, null, DomainEvent.Origin.APP);
    }
    private static FollowupItem row(Instant activityAt) {
        return new FollowupItem("row", "club-a", FollowupKind.MEMBER_NOTE, null, "dog-a", "member-a", "account-m", AuthorRole.MEMBER, "Laura", "Old", NOW, null, activityAt, false, NOW);
    }

    @Test void T_10_18_aNewNoteChangeUpsertsTheRowAndMakesItUnreadForEveryoneButTheAuthor() {
        String rowId = FollowupItemRepository.noteRowId("club-a", "dog-a");
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Nota\nnova", NOW, null)));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a", new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", "FEMALE", "account-m", null, "ca")));
        when(items.findById(rowId)).thenReturn(Optional.of(row(NOW.minusSeconds(60))));
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items).note("dog-a", "member-a", "account-m", "Laura", "Nota nova", NOW, NOW);
        verify(marks).unreadForEveryone(rowId, NOW);
        verify(dashboard).invalidateCountersAfterCommit("club-a");
        // An older (or the same) change replayed: the excerpt follows the note, nothing becomes unread.
        projection.memberNote(event("MemberNoteChanged", NOW.minusSeconds(120), Map.of("dogId", "dog-a")));
        verify(items).noteText("dog-a", "Nota nova", NOW);
        verify(marks, times(1)).unreadForEveryone(anyString(), any());
        // The first row, without a known member: no author name, the note's writer as the author.
        when(items.findById(rowId)).thenReturn(Optional.empty());
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Una", NOW, "account-w")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items).note("dog-a", "member-a", "account-w", "", "Una", NOW, NOW);
        // A stored row without activityAt is always refreshed; without a note or a dog, nothing.
        when(items.findById(rowId)).thenReturn(Optional.of(row(null)));
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items, times(2)).note(eq("dog-a"), any(), eq("account-w"), any(), any(), any(), any());
        when(census.dog("dog-a")).thenReturn(Optional.empty());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        when(census.instructorNote("dog-a")).thenReturn(Optional.empty());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items, times(3)).note(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void T_10_18_aTransferredDogTakesItsTasksAndRowsToTheNewOwner() {
        projection.transferred(event("DogTransferred", NOW, Map.of("dogId", "dog-a", "fromMemberId", "member-a", "toMemberId", "member-b")));
        verify(tasks).transfer("dog-a", "member-b"); verify(items).transfer("dog-a", "member-b");
        projection.transferred(event("DogTransferred", NOW, Map.of("dogId", "dog-b", "memberId", "member-c")));
        verify(tasks).transfer("dog-b", "member-c");
        projection.transferred(event("DogTransferred", NOW, Map.of("memberId", "member-c")));
        projection.transferred(new CensusForeignEvent("DogTransferred", "club-a", "Dog", "dog-c", NOW, null, null, null, DomainEvent.Origin.SYSTEM));
        verify(tasks, times(2)).transfer(any(), any()); verify(items, times(2)).transfer(any(), any());
    }
}

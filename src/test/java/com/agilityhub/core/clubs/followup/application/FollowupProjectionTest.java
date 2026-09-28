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
 * Round 2 (E64): the note's author is the event's member, never the dog's current owner, and a transfer applies the
 * census owner, never the event's destination.
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
        return new FollowupItem("row", "club-a", FollowupKind.MEMBER_NOTE, null, "dog-a", "member-a", "account-m", AuthorRole.MEMBER, "Laura", "FEMALE", "Old", NOW, null,
                activityAt, false, NOW);
    }

    @Test void T_10_18_aNewNoteChangeUpsertsTheRowAndMakesItUnreadForEveryoneButTheAuthor() {
        String rowId = FollowupItemRepository.noteRowId("club-a", "dog-a");
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Nota\nnova", NOW, null)));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a", new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", "FEMALE", "account-m", null, "ca")));
        when(items.findById(rowId)).thenReturn(Optional.of(row(NOW.minusSeconds(60))));
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-a")));
        verify(items).note("dog-a", "member-a", "account-m", "Laura", "FEMALE", "Nota nova", NOW, NOW);
        verify(marks).unreadForEveryone(rowId, NOW);
        verify(dashboard).invalidateCountersAfterCommit("club-a");
        // An older (or the same) change replayed: the excerpt follows the note, nothing becomes unread.
        projection.memberNote(event("MemberNoteChanged", NOW.minusSeconds(120), Map.of("dogId", "dog-a")));
        verify(items).noteText("dog-a", "Nota nova", NOW);
        verify(marks, times(1)).unreadForEveryone(anyString(), any());
        // The first row, without a known member (an event without memberId falls back to the owner): no author name or gender, the note's writer as the author.
        when(items.findById(rowId)).thenReturn(Optional.empty());
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Una", NOW, "account-w")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items).note("dog-a", "member-a", "account-w", "", null, "Una", NOW, NOW);
        // A member without an account: the note's writer is the author, with the member's name.
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a", new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", "FEMALE", null, null, "ca")));
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items).note("dog-a", "member-a", "account-w", "Laura", "FEMALE", "Una", NOW, NOW);
        // A stored row without activityAt is always refreshed; without a note or a dog, nothing.
        when(items.findById(rowId)).thenReturn(Optional.of(row(null)));
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items, times(3)).note(eq("dog-a"), any(), eq("account-w"), any(), any(), any(), any(), any());
        when(census.dog("dog-a")).thenReturn(Optional.empty());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        when(census.instructorNote("dog-a")).thenReturn(Optional.empty());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items, times(4)).note(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Round 2 (review #4): Joan wrote the note, and the dog is Laura's by the time the event is consumed. The row belongs to
     * Laura (the dog's owner), and its author is Joan: his account, first name and gender. Before the fix it named Laura.
     */
    @Test void T_10_18_aNoteConsumedAfterATransferIsStillItsWritersRow() {
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("La nota d'en Joan", NOW, "account-j")));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Toby", "ACTIVE", "member-l", "C")));
        when(census.members(List.of("member-j"))).thenReturn(Map.of("member-j", new FollowupCensusAccess.Member("member-j", "Joan", "Joan Example", "MALE", "account-j", null, "es")));
        when(census.members(List.of("member-l"))).thenReturn(Map.of("member-l", new FollowupCensusAccess.Member("member-l", "Laura", "Laura Example", "FEMALE", "account-l", null, "ca")));
        when(items.findById(any())).thenReturn(Optional.empty());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-j")));
        verify(items).note("dog-a", "member-l", "account-j", "Joan", "MALE", "La nota d'en Joan", NOW, NOW);
        verify(census, never()).members(List.of("member-l"));
    }

    /**
     * Round 3 (review #2, S10 §3): the event carries the author as they were when the note was written; the row takes that
     * snapshot and never reads the member's record, so a profile changed before the consumption does not rewrite it. Before
     * the fix the row took the record's current name and gender («Jan», FEMALE).
     */
    @Test void T_10_18_theNoteRowTakesTheAuthorTheEventFroze() {
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("La nota d'en Joan", NOW, "account-w")));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Toby", "ACTIVE", "member-l", "C")));
        when(census.members(anyCollection())).thenReturn(Map.of("member-j", new FollowupCensusAccess.Member("member-j", "Jan", "Jan Example", "FEMALE", "account-j", null, "es")));
        when(items.findById(any())).thenReturn(Optional.empty());
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-j",
                "author", Map.of("accountId", "account-j", "displayName", "Joan", "gender", "MALE"))));
        verify(items).note("dog-a", "member-l", "account-j", "Joan", "MALE", "La nota d'en Joan", NOW, NOW);
        // A snapshot without an account, a name or a gender (a member with none of them): the note's writer, «», null.
        projection.memberNote(event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-j", "author", Map.of())));
        verify(items).note("dog-a", "member-l", "account-w", "", null, "La nota d'en Joan", NOW, NOW);
        verify(census, never()).members(anyCollection());
    }

    /**
     * Round 2 (review #2): the tasks and rows go to the dog's owner as the census holds it, whatever the event says, so A → B
     * and B → C delivered in reverse order end with C. Before the fix the late A → B event put them back with B.
     */
    @Test void T_10_18_aTransferredDogTakesItsTasksAndRowsToItsCurrentOwner() {
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-c", "C")));
        projection.transferred(event("DogTransferred", NOW, Map.of("dogId", "dog-a", "fromMemberId", "member-b", "toMemberId", "member-c")));
        projection.transferred(event("DogTransferred", NOW.minusSeconds(60), Map.of("dogId", "dog-a", "fromMemberId", "member-a", "toMemberId", "member-b")));
        verify(tasks, times(2)).transfer("dog-a", "member-c"); verify(items, times(2)).transfer("dog-a", "member-c");
        verify(tasks, never()).transfer("dog-a", "member-b"); verify(items, never()).transfer("dog-a", "member-b");
        // Without a dog id, or a dog the census does not know in the club: nothing.
        when(census.dog("dog-x")).thenReturn(Optional.empty());
        projection.transferred(event("DogTransferred", NOW, Map.of("dogId", "dog-x", "toMemberId", "member-c")));
        projection.transferred(event("DogTransferred", NOW, Map.of("memberId", "member-c")));
        projection.transferred(new CensusForeignEvent("DogTransferred", "club-a", "Dog", "dog-c", NOW, null, null, null, DomainEvent.Origin.SYSTEM));
        verify(tasks, times(2)).transfer(any(), any()); verify(items, times(2)).transfer(any(), any());
    }
}

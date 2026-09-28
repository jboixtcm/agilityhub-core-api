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
                activityAt, false, NOW, null);
    }

    @Test void T_10_18_aNewNoteChangeUpsertsTheRowAndMakesItUnreadForEveryoneButTheAuthor() {
        String rowId = FollowupItemRepository.noteRowId("club-a", "dog-a");
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Nota\nnova", NOW, null)));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a", new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", "FEMALE", "account-m", null, "ca")));
        when(items.findById(rowId)).thenReturn(Optional.of(row(NOW.minusSeconds(60))));
        projection.memberNote("e-1", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-a")));
        verify(items).note("dog-a", "member-a", "account-m", "Laura", "FEMALE", "Nota nova", NOW, "e-1", NOW);
        verify(marks).unreadForEveryone(rowId, NOW);
        verify(dashboard).invalidateCountersAfterCommit("club-a");
        // An older (or the same) change replayed: the excerpt follows the note, nothing becomes unread.
        projection.memberNote("e-0", event("MemberNoteChanged", NOW.minusSeconds(120), Map.of("dogId", "dog-a")));
        verify(items).noteText("dog-a", "Nota nova", NOW);
        verify(marks, times(1)).unreadForEveryone(anyString(), any());
        // The first row, without a known member (an event without memberId falls back to the owner): no author name or gender, the note's writer as the author.
        when(items.findById(rowId)).thenReturn(Optional.empty());
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Una", NOW, "account-w")));
        when(census.members(List.of("member-a"))).thenReturn(Map.of());
        projection.memberNote("e-2", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items).note("dog-a", "member-a", "account-w", "", null, "Una", NOW, "e-2", NOW);
        // A member without an account: the note's writer is the author, with the member's name.
        when(census.members(List.of("member-a"))).thenReturn(Map.of("member-a", new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", "FEMALE", null, null, "ca")));
        projection.memberNote("e-3", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items).note("dog-a", "member-a", "account-w", "Laura", "FEMALE", "Una", NOW, "e-3", NOW);
        // A stored row without activityAt is always refreshed; without a note or a dog, nothing.
        when(items.findById(rowId)).thenReturn(Optional.of(row(null)));
        projection.memberNote("e-4", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items, times(3)).note(eq("dog-a"), any(), eq("account-w"), any(), any(), any(), any(), any(), any());
        when(census.dog("dog-a")).thenReturn(Optional.empty());
        projection.memberNote("e-5", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        when(census.instructorNote("dog-a")).thenReturn(Optional.empty());
        projection.memberNote("e-6", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a")));
        verify(items, times(4)).note(any(), any(), any(), any(), any(), any(), any(), any(), any());
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
        projection.memberNote("e-j", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-j")));
        verify(items).note("dog-a", "member-l", "account-j", "Joan", "MALE", "La nota d'en Joan", NOW, "e-j", NOW);
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
        projection.memberNote("e-1", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-j",
                "author", Map.of("accountId", "account-j", "displayName", "Joan", "gender", "MALE"))));
        verify(items).note("dog-a", "member-l", "account-j", "Joan", "MALE", "La nota d'en Joan", NOW, "e-1", NOW);
        // A snapshot without an account, a name or a gender (a member with none of them): the note's writer, «», null.
        projection.memberNote("e-2", event("MemberNoteChanged", NOW, Map.of("dogId", "dog-a", "memberId", "member-j", "author", Map.of())));
        verify(items).note("dog-a", "member-l", "account-w", "", null, "La nota d'en Joan", NOW, "e-2", NOW);
        verify(census, never()).members(anyCollection());
    }

    /**
     * Round 4 (review #2, S10 §7): the consumer is idempotent by `eventId`, and compares instants at the millisecond Mongo
     * stores. The event keeps `.519123Z`, the row `.519Z`. Before the fix the replay counted as a new change and made the
     * row unread for everyone again.
     */
    @Test void T_10_18_theSameNoteEventConsumedAgainNeverMakesTheRowUnreadAgain() {
        String rowId = FollowupItemRepository.noteRowId("club-a", "dog-a");
        Instant precise = NOW.plusNanos(519_123_000), stored = Instant.parse("2026-08-12T16:00:00.519Z");
        when(census.instructorNote("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Note("Nota", precise, "account-m")));
        when(census.dog("dog-a")).thenReturn(Optional.of(new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", "C")));
        var author = Map.<String, Object>of("accountId", "account-m", "displayName", "Laura", "gender", "FEMALE");
        var changed = event("MemberNoteChanged", precise, Map.of("dogId", "dog-a", "memberId", "member-a", "author", author));
        // The first delivery stores the instant at the stored precision, with the event's id.
        when(items.findById(rowId)).thenReturn(Optional.empty());
        projection.memberNote("e-1", changed);
        verify(items).note("dog-a", "member-a", "account-m", "Laura", "FEMALE", "Nota", stored, "e-1", NOW);
        verify(marks, times(1)).unreadForEveryone(rowId, NOW);
        // The same event again: nothing is written and no read mark is touched.
        var applied = new FollowupItem(rowId, "club-a", FollowupKind.MEMBER_NOTE, null, "dog-a", "member-a", "account-m", AuthorRole.MEMBER, "Laura", "FEMALE", "Nota",
                stored, null, stored, false, NOW, "e-1");
        when(items.findById(rowId)).thenReturn(Optional.of(applied));
        projection.memberNote("e-1", changed);
        // A row written before `lastEventId` existed: the replay is not newer at the stored precision, so only the excerpt follows.
        when(items.findById(rowId)).thenReturn(Optional.of(new FollowupItem(rowId, "club-a", FollowupKind.MEMBER_NOTE, null, "dog-a", "member-a", "account-m",
                AuthorRole.MEMBER, "Laura", "FEMALE", "Nota", stored, null, stored, false, NOW, null)));
        projection.memberNote("e-1", changed);
        verify(items, times(1)).noteText("dog-a", "Nota", NOW);
        verify(items, times(1)).note(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(marks, times(1)).unreadForEveryone(anyString(), any());
        verify(dashboard, times(1)).invalidateCountersAfterCommit("club-a");
        // A later change, one millisecond on, is a new change: unread again for everyone but its author.
        when(items.findById(rowId)).thenReturn(Optional.of(applied));
        projection.memberNote("e-2", event("MemberNoteChanged", stored.plusMillis(1), Map.of("dogId", "dog-a", "memberId", "member-a", "author", author)));
        verify(items).note("dog-a", "member-a", "account-m", "Laura", "FEMALE", "Nota", stored.plusMillis(1), "e-2", NOW);
        verify(marks, times(2)).unreadForEveryone(rowId, NOW);
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

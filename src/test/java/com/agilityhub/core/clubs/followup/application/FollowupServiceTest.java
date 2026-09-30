package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.FollowupKind;
import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListDataset;
import com.agilityhub.core.shared.application.lists.ListEngine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D14 (S10 R-10-13, E6-T03 step 6) without Mongo: the per-account `unread` stage and the «unread first» sort the list
 * hands to the universal engine, the rows resolved at read time (a missing dog or member leaves the names null), the
 * counter, a click that prunes the read ids read-all already covers, and the O(1) read-all; each write evicts the counters.
 */
class FollowupServiceTest {
    static final Instant NOW = Instant.parse("2026-08-12T16:00:00Z"), READ_ALL = NOW.minusSeconds(3600);
    private final FollowupItemRepository items = mock(FollowupItemRepository.class);
    private final FollowupReadMarkRepository marks = mock(FollowupReadMarkRepository.class);
    private final FollowupCensusAccess census = mock(FollowupCensusAccess.class);
    private final ListEngine lists = mock(ListEngine.class);
    private final DashboardQuery dashboard = mock(DashboardQuery.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final FollowupService service = new FollowupService(items, marks, tasks, census, lists, dashboard, Clock.fixed(NOW, ZoneOffset.UTC));
    private TenantContext.Scope tenant;
    @BeforeEach void open() { tenant = TenantContext.open("club-a"); }
    @AfterEach void close() { tenant.close(); }

    private static FollowupItem item(String id, FollowupKind kind, String dogId, String memberId, String author, AuthorRole role, Instant activityAt) {
        return new FollowupItem(id, "club-a", kind, kind == FollowupKind.TASK ? "task-" + id : null, dogId, memberId, author, role, kind == FollowupKind.TASK ? "Estel" : "Joan",
                kind == FollowupKind.TASK ? null : "MALE", "Text", NOW, null, activityAt, false, NOW, null);
    }

    @Test void T_10_06_T_10_18_theListComputesUnreadInTheQueryAndResolvesNamesAtReadTime() {
        when(marks.find("me")).thenReturn(Optional.of(new FollowupReadMark("m", "club-a", "me", READ_ALL, List.of("f2"), NOW, NOW)));
        when(lists.list(any(ListDataset.class), any())).thenReturn(new ListPage<>(List.of(Map.of("id", "f1"), Map.of("id", "f2"), Map.of("id", "f3"), Map.of("id", "gone")),
                0, 50, 4, 1, List.of()));
        when(items.byIds(List.of("f1", "f2", "f3", "gone"))).thenReturn(List.of(
                item("f3", FollowupKind.TASK, "dog-x", "member-x", "other", AuthorRole.INSTRUCTOR, NOW),
                item("f1", FollowupKind.MEMBER_NOTE, "dog-a", "member-a", "account-m", AuthorRole.MEMBER, NOW),
                item("f2", FollowupKind.TASK, "dog-a", "member-a", "other", AuthorRole.ADMIN, NOW)));
        when(census.dogs(List.of("dog-a", "dog-x"))).thenReturn(Map.of("dog-a", new FollowupCensusAccess.Dog("dog-a", "Duna", "ACTIVE", "member-a", null)));
        when(census.members(List.of("member-a", "member-x"))).thenReturn(Map.of("member-a",
                new FollowupCensusAccess.Member("member-a", "Laura", "Laura Example", "FEMALE", "account-m", null, "ca")));
        var page = service.list(new LinkedMultiValueMap<>(), "me");
        assertThat(page.items()).extracting(FollowupService.Item::id).containsExactly("f1", "f2", "f3");
        var note = page.items().get(0);
        // Round 2 (review #4): the note's author is its writer as stored (Joan, MALE), never the dog's current owner (Laura, FEMALE).
        assertThat(note.unread()).isTrue(); assertThat(note.authorName()).isEqualTo("Joan"); assertThat(note.authorGender()).isEqualTo("MALE");
        assertThat(note.dogName()).isEqualTo("Duna");
        assertThat(note.levelCode()).isNull(); assertThat(note.memberName()).isEqualTo("Laura Example"); assertThat(note.kind()).isEqualTo("MEMBER_NOTE");
        assertThat(page.items().get(1).unread()).as("clicked").isFalse(); assertThat(page.items().get(1).authorGender()).isNull();
        var unknown = page.items().get(2);
        assertThat(unknown.dogName()).isNull(); assertThat(unknown.memberName()).isNull(); assertThat(unknown.unread()).isTrue();
        var dataset = ArgumentCaptor.forClass(ListDataset.class);
        verify(lists).list(dataset.capture(), any());
        assertThat(dataset.getValue().leadingSort()).isEqualTo(new Document("unread", -1));
        assertThat(dataset.getValue().stages().get(0)).isEqualTo(new Document("$match", new Document("hidden", false)));
        var unreadStage = dataset.getValue().stages().get(1).get("$set", Document.class).get("unread", Document.class).getList("$and", Document.class);
        assertThat(unreadStage).hasSize(3);
        assertThat(unreadStage.get(0)).isEqualTo(new Document("$gt", List.of("$activityAt", Date.from(READ_ALL))));
        assertThat(unreadStage.get(2)).isEqualTo(new Document("$ne", List.of("$authorAccountId", "me")));
        // Without a read mark: no readAllAt condition, nothing clicked.
        when(marks.find("new")).thenReturn(Optional.empty());
        service.list(new LinkedMultiValueMap<>(), "new");
        verify(lists, times(2)).list(dataset.capture(), any());
        assertThat(dataset.getValue().stages().get(1).get("$set", Document.class).get("unread", Document.class).getList("$and", Document.class)).hasSize(2);
    }

    /**
     * E6-T06 step 2 (E75): the list's own search. `q` selects the rows of the dogs and members the census matched by name, the
     * rows whose author's name contains it (literally: a regex character is quoted), the task rows whose whole text does and
     * the note rows of the dogs whose note does.
     */
    @Test void T_10_18_theSearchMatchesTheCensusNamesTheAuthorAndTheWholeText() {
        when(marks.find("me")).thenReturn(Optional.empty());
        when(lists.list(any(ListDataset.class), any())).thenReturn(new ListPage<>(List.of(), 0, 50, 0, 0, List.of()));
        when(census.search("a.b")).thenReturn(new FollowupCensusAccess.Matches(Set.of("dog-a"), Set.of("member-a"), Set.of("dog-n")));
        when(tasks.idsContaining("a.b")).thenReturn(List.of("task-1"));
        service.list(new LinkedMultiValueMap<>(), "me");
        var dataset = ArgumentCaptor.forClass(ListDataset.class);
        verify(lists).list(dataset.capture(), any());
        assertThat(dataset.getValue().definition().searchable()).isNotEmpty();
        var search = dataset.getValue().search().apply("a.b");
        assertThat(search.getList("$or", Document.class)).containsExactly(
                new Document("dogId", new Document("$in", List.of("dog-a"))), new Document("memberId", new Document("$in", List.of("member-a"))),
                new Document("authorName", new Document("$regex", "\\Qa.b\\E").append("$options", "i")),
                new Document("kind", "TASK").append("taskId", new Document("$in", List.of("task-1"))),
                new Document("kind", "MEMBER_NOTE").append("dogId", new Document("$in", List.of("dog-n"))));
    }

    /**
     * E6-T06 step 1 (E75): the filter values are the engine's facets over the caller's rows, relabelled in one read per field:
     * the member's full name, the dog's name, the author's name; a value whose record is gone (or has no name) keeps its label,
     * and `kind`/`unread` are their values.
     */
    @Test void T_10_18_filterValuesAreTheCallersFacetsWithTheirNames() {
        when(marks.find("me")).thenReturn(Optional.of(new FollowupReadMark("m", "club-a", "me", READ_ALL, List.of("f2"), NOW, NOW)));
        var facets = new com.agilityhub.core.shared.application.contract.ApiContracts.FilterValues("x", List.of(
                new com.agilityhub.core.shared.application.contract.ApiContracts.FilterValue("a", "a", 3),
                new com.agilityhub.core.shared.application.contract.ApiContracts.FilterValue("gone", "gone", 1),
                new com.agilityhub.core.shared.application.contract.ApiContracts.FilterValue("blank", "blank", 1)));
        when(lists.facets(any(ListDataset.class), anyString(), any())).thenReturn(facets);
        when(census.members(List.of("a", "gone", "blank"))).thenReturn(Map.of("a", new FollowupCensusAccess.Member("a", "Laura", "Laura Example", "FEMALE", null, null, "ca"),
                "blank", new FollowupCensusAccess.Member("blank", "", "", null, null, null, "ca")));
        when(census.dogs(List.of("a", "gone", "blank"))).thenReturn(Map.of("a", new FollowupCensusAccess.Dog("a", "Duna", "ACTIVE", "m", null)));
        when(items.authorNames(List.of("a", "gone", "blank"))).thenReturn(Map.of("a", "Estel"));
        var labels = new LinkedHashMap<String, List<String>>();
        for (String field : List.of("memberId", "dogId", "authorAccountId", "kind")) {
            labels.put(field, service.filterValues(field, new LinkedMultiValueMap<>(), "me").values().stream().map(v -> v.label() + "/" + v.count()).toList());
        }
        assertThat(labels).containsExactly(Map.entry("memberId", List.of("Laura Example/3", "gone/1", "blank/1")), Map.entry("dogId", List.of("Duna/3", "gone/1", "blank/1")),
                Map.entry("authorAccountId", List.of("Estel/3", "gone/1", "blank/1")), Map.entry("kind", List.of("a/3", "gone/1", "blank/1")));
        // The caller's own unread: the facets run over the dataset built from their read mark.
        var dataset = ArgumentCaptor.forClass(ListDataset.class);
        verify(lists, times(4)).facets(dataset.capture(), anyString(), any());
        var unread = dataset.getValue().stages().get(1).get("$set", Document.class).get("unread", Document.class).getList("$and", Document.class);
        assertThat(unread.get(0)).isEqualTo(new Document("$gt", List.of("$activityAt", Date.from(READ_ALL))));
        assertThat(unread.get(2)).isEqualTo(new Document("$ne", List.of("$authorAccountId", "me")));
        verify(census, never()).search(anyString());
    }

    @Test void T_10_06_aClickPrunesTheIdsReadAllCoversAndReadAllIsOneWrite() {
        when(marks.find("me")).thenReturn(Optional.of(new FollowupReadMark("m", "club-a", "me", READ_ALL, List.of("old", "new"), NOW, NOW)));
        when(items.activity(List.of("old", "new", "f1"))).thenReturn(Map.of("old", READ_ALL.minusSeconds(1), "new", NOW, "f1", NOW));
        service.read("f1", "me");
        verify(marks).read("me", "f1", List.of("old"), NOW);
        // Without a mark (or without read ids), nothing to prune.
        when(marks.find("first")).thenReturn(Optional.empty());
        when(items.activity(List.of("f1"))).thenReturn(Map.of("f1", NOW));
        service.read("f1", "first");
        verify(marks).read("first", "f1", List.of(), NOW);
        when(marks.find("empty")).thenReturn(Optional.of(new FollowupReadMark("e", "club-a", "empty", null, null, NOW, NOW)));
        service.read("f1", "empty");
        verify(marks).read("empty", "f1", List.of(), NOW);
        service.readAll("me");
        verify(marks).readAll("me", NOW);
        verify(dashboard, times(4)).invalidateCountersAfterCommit("club-a");
        // The counter: the mark's readAllAt and read ids, or none.
        when(items.unread("me", READ_ALL, List.of("old", "new"))).thenReturn(3L);
        when(items.unread("first", null, List.of())).thenReturn(5L);
        when(items.unread("empty", null, List.of())).thenReturn(5L);
        assertThat(service.unreadCount("me")).isEqualTo(3); assertThat(service.unreadCount("first")).isEqualTo(5); assertThat(service.unreadCount("empty")).isEqualTo(5);
        var counter = new FollowupUnreadCounter(items, marks);
        assertThat(counter.count("club-a", "me")).isEqualTo(3);
        assertThatThrownBy(() -> counter.count("club-b", "me")).hasMessage("TENANT_MISMATCH");
    }
}

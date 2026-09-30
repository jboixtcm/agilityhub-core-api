package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.FollowupKind;
import com.agilityhub.core.clubs.followup.domain.FollowupRules;
import com.agilityhub.core.clubs.followup.persistence.FollowupItem;
import com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository;
import com.agilityhub.core.clubs.followup.persistence.FollowupReadMarkRepository;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterValue;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterValues;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListDataset;
import com.agilityhub.core.shared.application.lists.ListEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

/**
 * D14 «Seguiment alumnes» (S10 R-10-13, E6-T03). The rows are the visible {@link FollowupItem}s; `unread` is computed per
 * account in the query (`activityAt > readAllAt ∧ id ∉ readItemIds ∧ author ≠ me`), so the universal filters can use it
 * and the order is «unread first (activityAt desc), then the rest (activityAt desc)» in Mongo, not in the front. Member
 * and dog names and the level code are resolved at read time, one `$in` per page; the author (name, gender) is the row's
 * own, stored when it was written (E64). Pages hold at most 50 rows (S10 §3, {@link FollowupContractAccess#FOLLOWUP}). The list
 * searches, and its filter values are counted over the same per-caller rows (E6-T06, E75). The read marks are
 * single-document writes: read-all is O(1). Every write drops the dashboard counters after its commit, whose
 * `followUpUnread` ({@link FollowupUnreadCounter}, S14 R-14-08) is the same count as `GET /followup/unread-count`.
 */
@Service
public class FollowupService {
    public record Item(String id, String kind, String taskId, String dogId, String dogName, String levelCode, String memberId, String memberName,
            String authorName, AuthorRole authorRole, String authorGender, String textExcerpt, Instant createdAt, Instant completedAt, Instant activityAt,
            boolean unread) { }
    private final FollowupItemRepository items; private final FollowupReadMarkRepository marks; private final TaskRepository tasks;
    private final FollowupCensusAccess census; private final ListEngine lists; private final DashboardQuery dashboard; private final Clock clock;
    public FollowupService(FollowupItemRepository items, FollowupReadMarkRepository marks, TaskRepository tasks, FollowupCensusAccess census, ListEngine lists,
            DashboardQuery dashboard, Clock clock) {
        this.items = items; this.marks = marks; this.tasks = tasks; this.census = census; this.lists = lists; this.dashboard = dashboard; this.clock = clock;
    }

    /** The caller's D14 rows: `unread` computed for {@code me} from their read mark, unread first, `q` as {@link #search}. */
    private ListDataset dataset(String me, Instant readAllAt, List<String> read) {
        var unread = new ArrayList<Object>();
        if (readAllAt != null) { unread.add(new Document("$gt", List.of("$activityAt", Date.from(readAllAt)))); }
        unread.add(new Document("$not", List.of(new Document("$in", List.of("$_id", read)))));
        unread.add(new Document("$ne", List.of("$authorAccountId", me)));
        var projection = new LinkedHashMap<String, Object>();
        for (String field : List.of("kind", "taskId", "dogId", "memberId", "authorName", "authorRole", "textExcerpt", "createdAt", "completedAt", "activityAt", "unread")) { projection.put(field, 1); }
        return new ListDataset(FollowupContractAccess.FOLLOWUP, "followup_items",
                List.of(new Document("$match", new Document("hidden", false)), new Document("$set", new Document("unread", new Document("$and", unread)))),
                projection, Set.of(), (field, value) -> Objects.toString(value, ""), java.util.function.UnaryOperator.identity(), new Document("unread", -1))
                .withSearch(this::search);
    }
    /**
     * D14's `q` (CONVENCIONS_API §4 and S10 §6 amended 30-09, E75): the rows whose member's full name or dog's name (the census's,
     * as they are now), author's name (as the row stores it) or text contains it, any case, taken literally. The text is the
     * task's whole text or the dog's member note, not only the 120-character excerpt D14 shows.
     */
    Document search(String q) {
        var matches = census.search(q);
        return new Document("$or", List.of(new Document("dogId", new Document("$in", List.copyOf(matches.dogIds()))),
                new Document("memberId", new Document("$in", List.copyOf(matches.memberIds()))),
                new Document("authorName", new Document("$regex", Pattern.quote(q)).append("$options", "i")),
                new Document("kind", FollowupKind.TASK.name()).append("taskId", new Document("$in", tasks.idsContaining(q))),
                new Document("kind", FollowupKind.MEMBER_NOTE.name()).append("dogId", new Document("$in", List.copyOf(matches.noteDogIds())))));
    }

    public ListPage<Item> list(MultiValueMap<String, String> params, String me) {
        var mark = marks.find(me).orElse(null);
        Instant readAllAt = mark == null ? null : mark.readAllAt();
        List<String> read = mark == null || mark.readItemIds() == null ? List.of() : mark.readItemIds();
        var page = lists.list(dataset(me, readAllAt, read), params);
        var ids = page.items().stream().map(row -> row.get("id").toString()).toList();
        var byId = new HashMap<String, FollowupItem>(); items.byIds(ids).forEach(item -> byId.put(item.id(), item));
        var rows = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        var dogs = census.dogs(rows.stream().map(FollowupItem::dogId).distinct().toList());
        var members = census.members(rows.stream().map(FollowupItem::memberId).distinct().toList());
        var result = rows.stream().map(item -> {
            var dog = dogs.get(item.dogId()); var member = members.get(item.memberId());
            return new Item(item.id(), item.kind().name(), item.taskId(), item.dogId(), dog == null ? null : dog.name(), dog == null ? null : dog.levelCode(),
                    item.memberId(), member == null ? null : member.fullName(), item.authorName(), item.authorRole(), item.authorGender(), item.textExcerpt(),
                    item.createdAt(), item.completedAt(),
                    item.activityAt(), FollowupRules.unread(item.id(), item.activityAt(), item.authorAccountId(), readAllAt, read, me));
        }).toList();
        return new ListPage<>(result, page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
    }

    /**
     * `GET /followup/filter-values` (CONVENCIONS_API §4, S10 §6, E75): the values of {@code field} with their counts over the
     * caller's whole filtered set (`unread` is theirs, R-10-13). Labels: the member's full name, the dog's name and the
     * author's name, read for the (at most 50) values listed; `kind`, `unread` and an id whose record is gone, the value.
     */
    public FilterValues filterValues(String field, MultiValueMap<String, String> params, String me) {
        var mark = marks.find(me).orElse(null);
        var values = lists.facets(dataset(me, mark == null ? null : mark.readAllAt(), mark == null || mark.readItemIds() == null ? List.of() : mark.readItemIds()),
                field, params);
        var ids = values.values().stream().map(value -> value.value().toString()).toList();
        Map<String, String> names = new HashMap<>();
        switch (field) {
            case "memberId" -> census.members(ids).forEach((id, member) -> names.put(id, member.fullName()));
            case "dogId" -> census.dogs(ids).forEach((id, dog) -> names.put(id, dog.name()));
            case "authorAccountId" -> names.putAll(items.authorNames(ids));
            default -> { }
        }
        return new FilterValues(values.field(), values.values().stream().map(value -> {
            String name = names.get(value.value().toString());
            return new FilterValue(value.value(), name == null || name.isBlank() ? value.label() : name, value.count());
        }).toList());
    }

    /** The menu counter «Seguiment alumnes» (`GET /followup/unread-count`): the caller's unread rows. */
    public long unreadCount(String me) { return FollowupUnreadCounter.unread(items, marks, me); }

    /** A click on a row: `readItemIds += id`, pruning the ids that `readAllAt` already covers (R-10-13). */
    @Transactional
    public void read(String itemId, String me) {
        var mark = marks.find(me).orElse(null);
        var ids = new ArrayList<String>(mark == null || mark.readItemIds() == null ? List.of() : mark.readItemIds()); ids.add(itemId);
        var kept = FollowupRules.pruned(ids, items.activity(ids), mark == null ? null : mark.readAllAt());
        var dropped = ids.stream().filter(id -> !kept.contains(id)).distinct().toList();
        marks.read(me, itemId, dropped.stream().filter(id -> !id.equals(itemId)).toList(), clock.instant());
        dashboard.invalidateCountersAfterCommit(TenantContext.require());
    }
    /** «Marcar-ho tot com a llegit»: `readAllAt = now`, `readItemIds = []`, one document whatever the number of rows. */
    @Transactional
    public void readAll(String me) {
        marks.readAll(me, clock.instant());
        dashboard.invalidateCountersAfterCommit(TenantContext.require());
    }
}

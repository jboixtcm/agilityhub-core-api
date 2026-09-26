package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.bookings.application.ports.ActivityHistoryQuery;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * S07 → S10 (§7) `ActivityHistoryQuery.itemsFor(memberId, from)` over {@link ActivityQueryService#historyRowsFor}: the
 * member's done and cancelled registrations (waiting ones and activities not yet finished are left out by S07).
 */
@Service
public class ActivityHistory implements ActivityHistoryQuery {
    private final ActivityQueryService queries;
    public ActivityHistory(ActivityQueryService queries) { this.queries = queries; }
    @Override public List<Item> itemsFor(String memberId, Instant from) {
        return queries.historyRowsFor(memberId, from, Instant.MAX).stream().map(r -> new Item(r.get("id").toString(), Objects.toString(r.get("title"), ""),
                (Instant) r.get("startsAt"), (String) r.get("startsAtLocal"), r.get("state").toString(), (String) r.get("adminText"))).toList();
    }
}

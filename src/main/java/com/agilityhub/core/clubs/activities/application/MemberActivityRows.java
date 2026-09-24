package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.bookings.application.ports.MemberActivityRowsPort;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** S08 R-08-22 adapter over {@link ActivityQueryService}: the `ACTIVITY` rows of 03 and the «Activitats» block of 04. */
@Service
public class MemberActivityRows implements MemberActivityRowsPort {
    private final ActivityQueryService queries;
    public MemberActivityRows(ActivityQueryService queries) { this.queries = queries; }

    @Override public List<Row> live(String memberId) {
        return queries.liveRegistrationsFor(memberId).stream().map(r -> new Row(r.get("id").toString(), r.get("state").toString(), Objects.toString(r.get("title"), ""),
                (Instant) r.get("startsAt"), r.get("startsAtLocal").toString(), (String) r.get("endsAtLocal"), (String) r.get("ringName"))).toList();
    }
    @Override public List<Bookable> bookable(String memberId, String dogId) {
        return queries.bookableFor(memberId, dogId).stream().map(r -> new Bookable(r.get("id").toString(), Objects.toString(r.get("title"), ""),
                r.get("startsAtLocal").toString(), (Integer) r.get("freeSeats"))).toList();
    }
}

package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.persistence.Venue;
import com.agilityhub.core.courses.persistence.VenueRepository;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * INC-61 round 2 (E11-T02, review #7; S16 §14.2): the club's first ring picks a free venue slug, but another club's first ring
 * takes that slug between the check and the insert. The insert is then a duplicate key of the global `venue_slug` index: it is
 * retried with the next suffix, outside the geometry's transaction, and the geometry is saved (before: the duplicate key aborted
 * the geometry's transaction and the valid geometry answered 500).
 */
class VenueSlugRaceIT extends CourseItSupport {
    @MockitoSpyBean VenueRepository venues;

    @Test void T_16_06_INC_61_aSlugTakenByAConcurrentFirstRingIsRetriedWithTheNextSuffix() throws Exception {
        var raced = new AtomicBoolean();
        doAnswer(call -> {
            Venue venue = call.getArgument(0);
            if (raced.compareAndSet(false, true)) {
                // Another club's first ring commits the same slug first, on its own thread and outside this request's transaction.
                CompletableFuture.runAsync(() -> mongo.insert(new Document("_id", "e9t2-inc61-race-venue").append("clubId", OTHER)
                        .append("slug", venue.slug()).append("name", "The other club's venue"), "venues")).join();
            }
            return call.callRealMethod();
        }).when(venues).ensure(any());

        assertThat(ok(as(json(put("/api/v1/rings/" + RING_A1 + "/geometry"), geometry(0)), CLUB, "ADMIN"), 200).path("version").asLong()).isEqualTo(1);

        assertThat(raced).isTrue();
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "venues"))
                .singleElement().satisfies(venue -> assertThat(venue.getString("slug")).isEqualTo(CLUB + "-2"));
        assertThat(mongo.findOne(Query.query(Criteria.where("_id").is("e9t2-inc61-race-venue")), Document.class, "venues").getString("slug")).isEqualTo(CLUB);
    }

    static final String RING_A1 = RingGeometryIT.RING;
}

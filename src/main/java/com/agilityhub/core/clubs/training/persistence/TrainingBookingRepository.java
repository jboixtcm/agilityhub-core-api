package com.agilityhub.core.clubs.training.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class TrainingBookingRepository extends TenantRepository<TrainingBooking> {
    public TrainingBookingRepository(MongoTemplate mongo) { super(mongo, TrainingBooking.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(TrainingBooking.class);
        // R-09-06: the final guard against double booking of a seat, even from a stale grid.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("startsAt", ASC).on("seatIndex", ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("state").is("ACTIVE"))).named("training_active_seat"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("startsAt", ASC).named("training_club_starts"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("weekStart", ASC).on("state", ASC).named("training_club_dog_week_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("weekStart", ASC).on("state", ASC).named("training_club_member_week_state"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("state", ASC).on("startsAt", ASC).named("training_club_state_starts"));
    }
}

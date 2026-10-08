package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class VenueRepository extends CourseTenantRepository<Venue> {
    public VenueRepository(MongoTemplate mongo) {
        super(mongo, Venue.class);
        mongo.indexOps(Venue.class).ensureIndex(new Index().on("clubId", ASC).unique().partial(PartialIndexFilter.of(Criteria.where("clubId").type(2))).named("venue_club"));
        mongo.indexOps(Venue.class).ensureIndex(new Index().on("slug", ASC).unique().named("venue_slug"));
    }
}

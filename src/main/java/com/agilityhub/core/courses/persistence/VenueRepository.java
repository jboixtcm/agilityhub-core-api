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
        mongo.indexOps(Venue.class).createIndex(new Index().on("clubId", ASC).unique().partial(PartialIndexFilter.of(Criteria.where("clubId").type(2))).named("venue_club"));
        mongo.indexOps(Venue.class).createIndex(new Index().on("slug", ASC).unique().named("venue_slug"));
    }
    /** S16 §14.2: the club's own venue (one per club), if it exists yet. */
    public java.util.Optional<Venue> club() { return java.util.Optional.ofNullable(mongo.findOne(tenantQuery(), Venue.class)); }
    /**
     * INC-61 (E11-T02): {@code slug} itself when no venue has it yet, otherwise the first free `{slug}-2`, `{slug}-3`… The slug
     * index is global (a member's or a partner's venue may hold the club's slug), and a duplicate key would abort the geometry's
     * transaction, so the slug is chosen before the write.
     */
    public String freeSlug(String slug) {
        String candidate = slug;
        for (int n = 2; mongo.exists(org.springframework.data.mongodb.core.query.Query.query(Criteria.where("slug").is(candidate)), Venue.class); n++) { candidate = slug + "-" + n; }
        return candidate;
    }
    /** Creates the club's venue once: an upsert on the club that only inserts, so a second call (or a concurrent one) changes nothing. */
    public void ensure(Venue venue) {
        tenantQuery(venue.clubId());
        var document = new org.bson.Document();
        mongo.getConverter().write(venue, document);
        var update = new org.springframework.data.mongodb.core.query.Update();
        document.forEach((key, value) -> { if (!key.equals("clubId") && !key.equals("_class")) { update.setOnInsert(key, value); } });
        mongo.upsert(tenantQuery(), update, Venue.class);
    }
}

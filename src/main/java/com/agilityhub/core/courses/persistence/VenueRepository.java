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
     * INC-61 (E11-T02): creates the club's venue ({@code venue} builds it for a slug) with {@code slug} itself when no venue has it
     * yet, otherwise with the first free `{slug}-2`, `{slug}-3`…; the slug index is global (a member's or a partner's venue may
     * hold the club's slug). Round 2 (review #7): the free slug is checked before the write, but two clubs that draw their first
     * ring at the same instant can still pick the same one, and the later insert is then a duplicate key. That duplicate key is
     * caught here and the write retried with the next suffix (or, when it was this club's venue that a concurrent request created
     * meanwhile, nothing more is done). A duplicate key aborts a Mongo transaction, so the caller runs this outside its own.
     * @return the slug of the club's venue
     */
    public String freeSlug(String slug, java.util.function.Function<String, Venue> venue) {
        String candidate = slug;
        for (int n = 2, collisions = 0; ; n++) {
            if (!mongo.exists(org.springframework.data.mongodb.core.query.Query.query(Criteria.where("slug").is(candidate)), Venue.class)) {
                try { ensure(venue.apply(candidate)); return candidate; }
                catch (org.springframework.dao.DuplicateKeyException taken) {
                    var created = club();
                    if (created.isPresent()) { return created.get().slug(); }
                    if (++collisions == MAX_SLUG_COLLISIONS) { throw taken; }
                }
            }
            candidate = slug + "-" + n;
        }
    }
    /** A bound on the races {@link #freeSlug} retries, so that a duplicate key on another index cannot loop for ever. */
    static final int MAX_SLUG_COLLISIONS = 20;
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

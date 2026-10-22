package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** S14 R-14-15 writes of this context in the current club (see {@code CatalogsErasure}). */
@Repository
public class CatalogErasureRepository {
    private final MongoTemplate mongo;
    public CatalogErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }

    /** The member's instructor profile, renamed `label` unless it already is; empty when there is none to change. */
    public Optional<Instructor> renameInstructor(String memberId, String label, Instant at) {
        return Optional.ofNullable(mongo.findAndModify(Query.query(Criteria.where("clubId").is(TenantContext.require()).and("memberId").is(memberId)
                        .and("shortName").ne(label)),
                new Update().set("shortName", label).set("updatedAt", at).inc("version", 1), FindAndModifyOptions.options().returnNew(true), Instructor.class));
    }
    /** The instructor profiles of these members in the current club (E11-T16 round 2, the monthly sweep of their traces). */
    public List<Instructor> instructorsOf(Collection<String> memberIds) {
        if (memberIds.isEmpty()) { return List.of(); }
        return mongo.find(Query.query(club().and("memberId").in(memberIds)), Instructor.class);
    }
    public Optional<Instructor> instructor(String id) {
        return Optional.ofNullable(mongo.findOne(Query.query(club().and("_id").is(id)), Instructor.class));
    }
    private static Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }
}

package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
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
}

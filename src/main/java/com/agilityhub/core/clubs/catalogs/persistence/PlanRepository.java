package com.agilityhub.core.clubs.catalogs.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Repository;

@Repository
public class PlanRepository extends CatalogRepository<Plan> {
    public PlanRepository(MongoTemplate mongo) {
        super(mongo, Plan.class);
        mongo.indexOps(Plan.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("code", Direction.ASC)
                .unique().partial(PartialIndexFilter.of(Criteria.where("code").exists(true)))
                .collation(Collation.of("en").strength(2)).named("plan_code"));
    }
    @Override public Plan update(Plan next, long version) {
        super.update(next, version);
        var reset = new Update();
        if (next.billingMode() == null) { reset.unset("billingMode"); }
        if (next.pack() == null) { reset.unset("pack"); }
        if (next.singleClass() == null) { reset.unset("singleClass"); }
        if (!reset.getUpdateObject().isEmpty()) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(next.id())), reset, Plan.class);
        }
        return next;
    }
}

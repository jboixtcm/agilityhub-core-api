package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class PriceRepository extends TenantRepository<Price> {
    public PriceRepository(MongoTemplate mongo) {
        super(mongo, Price.class);
        mongo.indexOps(Price.class).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("planId", Sort.Direction.ASC)
                .on("concept", Sort.Direction.ASC).on("validFrom", Sort.Direction.ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("concept").exists(true).and("validFrom").exists(true))).named("price_start"));
    }
    public List<Price> forPlan(String planId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("planId").is(planId))
                .with(Sort.by("concept", "validFrom", "id")), Price.class);
    }
    public Optional<Price> current(String planId, PriceConcept concept, LocalDate date) {
        return forPlan(planId).stream().filter(price -> price.concept() == concept
                && com.agilityhub.core.clubs.catalogs.domain.PriceRules.status(price, date)
                == com.agilityhub.core.clubs.catalogs.domain.PriceRules.Status.CURRENT).findFirst();
    }
    public void update(Price next, long expected) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expected));
        var update = new Update().set("concept", next.concept()).set("amount", next.amount()).set("taxPercent", next.taxPercent())
                .set("validFrom", next.validFrom().toString()).set("validTo", next.validTo() == null ? null : next.validTo().toString())
                .set("version", next.version()).set("updatedAt", next.updatedAt()).set("updatedByAccountId", next.updatedByAccountId());
        if (mongo.updateFirst(query, update, Price.class).getMatchedCount() != 1) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
}

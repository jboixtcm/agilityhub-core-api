package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import java.time.LocalDate;
import java.util.*;
import org.bson.BsonType;
import org.bson.Document;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class PriceRepository extends TenantRepository<Price> {
    public PriceRepository(MongoTemplate mongo) {
        super(mongo, Price.class);
        mongo.indexOps(Price.class).createIndex(new Index().on("clubId", Sort.Direction.ASC).on("planId", Sort.Direction.ASC)
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
        // UpdateMapper applies PriceDateConverter: supply the domain days, as the whole-document write does (INC-68).
        var update = new Update().set("concept", next.concept()).set("amount", next.amount()).set("taxPercent", next.taxPercent())
                .set("validFrom", next.validFrom()).set("validTo", next.validTo())
                .set("version", next.version()).set("updatedAt", next.updatedAt()).set("updatedByAccountId", next.updatedByAccountId());
        if (mongo.updateFirst(query, update, Price.class).getMatchedCount() != 1) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }

    /**
     * INC-68: converts the validity days that a whole-document write stored as BSON dates (every club, before the fix) to the
     * `YYYY-MM-DD` strings every write stores now; idempotent. The template writes with the price's `clubId`, so reset's write
     * fence sees them (R-18-17). The filter repeats the old values, so a concurrent edit wins. A converted start that would
     * duplicate another price's (unique `price_start`) stays a date, which reads the same, and is logged by id.
     */
    public long normaliseValidityDates() {
        String collection = mongo.getCollectionName(Price.class);
        int date = BsonType.DATE_TIME.getValue();
        var legacy = Query.query(new Criteria().orOperator(Criteria.where("validFrom").type(date), Criteria.where("validTo").type(date)));
        long converted = 0;
        for (var stored : mongo.find(legacy, Document.class, collection)) {
            var unchanged = Query.query(Criteria.where("_id").is(stored.get("_id")).and("clubId").is(stored.get("clubId"))
                    .and("validFrom").is(stored.get("validFrom")).and("validTo").is(stored.get("validTo")));
            var days = new Update().set("validFrom", day(stored.get("validFrom")));
            if (stored.get("validTo") != null) { days.set("validTo", day(stored.get("validTo"))); }
            try { converted += mongo.updateFirst(unchanged, days, collection).getModifiedCount(); }
            catch (DuplicateKeyException duplicate) {
                LoggerFactory.getLogger(PriceRepository.class).warn("Price {} keeps a date validFrom: its day duplicates another price's start", stored.get("_id"));
            }
        }
        return converted;
    }
    private static String day(Object stored) { return stored == null ? null : PriceDateConverter.day(stored).toString(); }
}

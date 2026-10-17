package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import java.time.LocalDate;
import java.util.*;
import org.bson.BsonType;
import org.slf4j.LoggerFactory;
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
     * `YYYY-MM-DD` strings every write stores now; idempotent. The filter repeats the old values, so a concurrent edit wins. A
     * converted start that would duplicate another price's (unique `price_start`) stays a date, which reads the same, and is
     * logged by id.
     */
    public long normaliseValidityDates() {
        var collection = mongo.getCollection(mongo.getCollectionName(Price.class));
        long converted = 0;
        for (var stored : collection.find(Filters.or(Filters.type("validFrom", BsonType.DATE_TIME), Filters.type("validTo", BsonType.DATE_TIME)))) {
            var filter = Filters.and(Filters.eq("_id", stored.get("_id")), Filters.eq("validFrom", stored.get("validFrom")), Filters.eq("validTo", stored.get("validTo")));
            var days = Updates.combine(Updates.set("validFrom", day(stored.get("validFrom"))), Updates.set("validTo", day(stored.get("validTo"))));
            try { converted += collection.updateOne(filter, days).getModifiedCount(); }
            catch (MongoWriteException duplicate) {
                if (duplicate.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) { throw duplicate; }
                LoggerFactory.getLogger(PriceRepository.class).warn("Price {} keeps a date validFrom: its day duplicates another price's start", stored.get("_id"));
            }
        }
        return converted;
    }
    private static String day(Object stored) { return stored == null ? null : PriceDateConverter.day(stored).toString(); }
}

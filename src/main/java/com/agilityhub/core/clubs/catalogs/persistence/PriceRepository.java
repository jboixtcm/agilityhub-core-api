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
    /**
     * R-05-15: the one price of {@code concept} valid on {@code date}. INC-68 round 2: two of them (a legacy twin the startup
     * conversion reported) are never resolved by sort order: `409 PRICE_OVERLAP` names the plan and both prices.
     */
    public Optional<Price> current(String planId, PriceConcept concept, LocalDate date) {
        var current = forPlan(planId).stream().filter(price -> price.concept() == concept
                && com.agilityhub.core.clubs.catalogs.domain.PriceRules.status(price, date)
                == com.agilityhub.core.clubs.catalogs.domain.PriceRules.Status.CURRENT).toList();
        if (current.size() > 1) {
            throw new ApiException(ErrorCode.PRICE_OVERLAP, Map.of("planId", planId, "concept", concept.name(),
                    "priceId", current.get(0).id(), "conflictingPriceId", current.get(1).id()));
        }
        return current.stream().findFirst();
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
     * fence sees them (R-18-17). The filter repeats the old values, so a concurrent edit wins.
     *
     * <p>Round 2: a converted start that would duplicate another price's (unique `price_start`; before the fix the index saw a
     * date and a string as two starts) is a twin. A twin identical to the price kept (amount, tax, end) that no document names
     * (`members.priceId`, `invoices.lines.priceId`, `pending_charges`, `collections`, `pack_balances`) is removed. Any other
     * twin stays a date and is reported by {@link #legacyStartTwins()}: billing then refuses to choose ({@link #current}).
     */
    public long normaliseValidityDates() {
        String collection = mongo.getCollectionName(Price.class);
        int date = BsonType.DATE_TIME.getValue();
        var legacy = Query.query(new Criteria().orOperator(Criteria.where("validFrom").type(date), Criteria.where("validTo").type(date)));
        long converted = 0;
        List<Object> removed = new ArrayList<>();
        for (var stored : mongo.find(legacy, Document.class, collection)) {
            var unchanged = Query.query(Criteria.where("_id").is(stored.get("_id")).and("clubId").is(stored.get("clubId"))
                    .and("validFrom").is(stored.get("validFrom")).and("validTo").is(stored.get("validTo")));
            var days = new Update().set("validFrom", day(stored.get("validFrom")));
            if (stored.get("validTo") != null) { days.set("validTo", day(stored.get("validTo"))); }
            try { converted += mongo.updateFirst(unchanged, days, collection).getModifiedCount(); }
            catch (DuplicateKeyException duplicate) {
                if (identicalTwinKept(stored, collection) && !named(stored.get("clubId"), stored.get("_id"))
                        && mongo.remove(unchanged, collection).getDeletedCount() == 1) { removed.add(stored.get("_id")); }
            }
        }
        if (!removed.isEmpty()) {
            LoggerFactory.getLogger(PriceRepository.class).info("Price start twins removed count={} ids={}", removed.size(), removed);
        }
        return converted;
    }

    /**
     * INC-68 round 2: the ids of the prices whose start is still a BSON date after {@link #normaliseValidityDates()}, i.e. the
     * twins it kept (or a price written meanwhile by an old process). `docs/DEPLOY.md` says how to resolve them by hand.
     */
    public List<String> legacyStartTwins() {
        var legacy = Query.query(Criteria.where("validFrom").type(BsonType.DATE_TIME.getValue())).with(Sort.by("_id"));
        legacy.fields().include("_id");
        return mongo.find(legacy, Document.class, mongo.getCollectionName(Price.class)).stream().map(row -> String.valueOf(row.get("_id"))).toList();
    }

    /** The string-dated price that starts on the legacy one's day has the same amount, tax and end. */
    private boolean identicalTwinKept(Document stored, String collection) {
        var kept = mongo.findOne(Query.query(Criteria.where("clubId").is(stored.get("clubId")).and("planId").is(stored.get("planId"))
                .and("concept").is(stored.get("concept")).and("validFrom").is(day(stored.get("validFrom")))), Document.class, collection);
        if (kept == null) { return false; }
        var twin = mongo.getConverter().read(Price.class, stored);
        var price = mongo.getConverter().read(Price.class, kept);
        return Objects.equals(twin.amount(), price.amount()) && Objects.equals(twin.validTo(), price.validTo())
                && (twin.taxPercent() == null ? price.taxPercent() == null : price.taxPercent() != null && twin.taxPercent().compareTo(price.taxPercent()) == 0);
    }

    /** Whether a document of the club names the price (its member, invoice line, pending charge, upfront collection or pack). */
    private boolean named(Object clubId, Object priceId) {
        var references = Map.of("members", "priceId", "invoices", "lines.priceId", "pending_charges", "priceId",
                "collections", "priceId", "pack_balances", "priceId");
        return references.entrySet().stream().anyMatch(reference -> mongo.exists(
                Query.query(Criteria.where("clubId").is(clubId).and(reference.getValue()).is(priceId)), reference.getKey()));
    }
    private static String day(Object stored) { return stored == null ? null : PriceDateConverter.day(stored).toString(); }
}

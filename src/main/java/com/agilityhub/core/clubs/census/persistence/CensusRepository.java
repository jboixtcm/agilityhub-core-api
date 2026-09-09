package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;

/** Partial compare-and-set writes preserve fields owned by future census verticals. */
public class CensusRepository<T extends CensusEntity> extends TenantRepository<T> {
    private final Class<T> type;
    private final Clock clock;
    public CensusRepository(MongoTemplate mongo, Class<T> type, Clock clock) {
        super(mongo, type); this.type = type; this.clock = clock;
    }
    public T require(String id) { return findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public List<T> matching(Criteria criteria) { return mongo.find(tenantQuery().addCriteria(criteria), type); }
    public void ensureIndexes(String field, String bsonType, String name) {
        mongo.indexOps(type).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on(field, Sort.Direction.ASC)
                .unique().partial(PartialIndexFilter.of(new Document(field, new Document("$type", bsonType)))).named(name));
    }
    public void ensureLookup(String field) { mongo.indexOps(type).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on(field, Sort.Direction.ASC)); }
    @Override public T insert(T item) {
        item.version = 0L; item.createdAt = clock.instant(); item.updatedAt = item.createdAt;
        try { return super.insert(item); } catch (DuplicateKeyException duplicate) { throw duplicate(duplicate); }
    }
    public T save(T item) {
        long expected = item.version();
        var query = tenantQuery(item.clubId).addCriteria(Criteria.where("_id").is(item.id));
        query.addCriteria(new Criteria().orOperator(Criteria.where("version").is(expected),
                Criteria.where("version").is(expected == 0 ? null : expected)));
        item.version = expected + 1; item.updatedAt = clock.instant();
        Document data = new Document(); mongo.getConverter().write(item, data);
        Update update = new Update();
        data.forEach((key, value) -> { if (!Set.of("_id", "_class", "clubId").contains(key)) { update.set(key, value); } });
        for (var field : type.getDeclaredFields()) {
            if (!data.containsKey(field.getName())) { update.unset(field.getName()); }
        }
        try {
            if (mongo.updateFirst(query, update, mongo.getCollectionName(type)).getMatchedCount() != 1) { throw new ApiException(ErrorCode.STALE_VERSION); }
        } catch (DuplicateKeyException duplicate) { throw duplicate(duplicate); }
        return item;
    }
    private RuntimeException duplicate(DuplicateKeyException failure) {
        String message = failure.getMessage();
        if (message != null && message.contains("member_id_document")) { return new ApiException(ErrorCode.ID_DOCUMENT_ALREADY_EXISTS); }
        if (message != null && message.contains("dog_chip")) { return new ApiException(ErrorCode.CHIP_ALREADY_EXISTS); }
        return failure;
    }
    public void lock() {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(com.agilityhub.core.shared.application.TenantContext.require() + ":census")), new Update().inc("sequence", 1), "census_write_locks");
    }
    public void documentIndex() {
        mongo.indexOps(type).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("dogId", Sort.Direction.ASC)
                .on("type", Sort.Direction.ASC).unique().named("dog_document_type"));
    }
}

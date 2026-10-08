package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.*;

/** Transactional counter shared by tenant maintenance and all template mutations. */
@org.springframework.stereotype.Repository
public class TenantWriteCounterRepository extends TenantRepository<TenantWriteCounterRepository.Counter> {
    @Document("tenant_write_counters")
    public record Counter(@Id String id, String clubId, long sequence, long lockVersion, Long activeWriters) implements TenantEntity { }

    public TenantWriteCounterRepository(MongoTemplate mongo) { super(mongo, Counter.class); }

    /** Independent registration precedes the first mutation, including writers nested inside other transactions. */
    public void begin(String clubId) {
        mongo.upsert(Query.query(Criteria.where("_id").is(clubId).and("clubId").is(clubId)),
                new Update().inc("activeWriters", 1L).setOnInsert("sequence", 0L).setOnInsert("lockVersion", 0L), Counter.class);
    }

    /** Closing a committed writer and advancing the sequence are one atomic operation. */
    public void finish(String clubId, boolean changed) {
        var update = new Update().inc("activeWriters", -1L);
        if (changed) { update.inc("sequence", 1L); }
        if (mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId).and("clubId").is(clubId).and("activeWriters").gt(0)),
                update, Counter.class).getMatchedCount() != 1) { throw new IllegalStateException("Tenant write registration missing"); }
    }

    /** Template mutations, audit and outbox call this in the writer's transaction; rollback includes the counter. */
    public void written(String clubId) {
        if (clubId != null) {
            mongo.upsert(Query.query(Criteria.where("_id").is(clubId).and("clubId").is(clubId)),
                    new Update().inc("sequence", 1L).setOnInsert("lockVersion", 0L).setOnInsert("activeWriters", 0L), Counter.class);
        }
    }

    /** A real write obtains the same Mongo document lock as writers; the caller must keep its transaction open. */
    public long lock() {
        var counter = mongo.findAndModify(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())),
                new Update().setOnInsert("sequence", 0L).setOnInsert("activeWriters", 0L).inc("lockVersion", 1),
                FindAndModifyOptions.options().upsert(true).returnNew(true), Counter.class);
        if (counter.activeWriters() != null && counter.activeWriters() != 0) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
        return counter.sequence();
    }
}

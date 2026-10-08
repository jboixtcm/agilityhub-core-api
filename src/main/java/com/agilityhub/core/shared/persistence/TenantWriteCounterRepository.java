package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.*;

/** Transactional write fence for tenant maintenance. Only explicitly tracked tenants incur a counter write. */
@org.springframework.stereotype.Repository
public class TenantWriteCounterRepository extends TenantRepository<TenantWriteCounterRepository.Counter> {
    @Document("tenant_write_counters")
    public record Counter(@Id String id, String clubId, long sequence, long lockVersion) implements TenantEntity { }

    public TenantWriteCounterRepository(MongoTemplate mongo) { super(mongo, Counter.class); }

    /** Audit and outbox writers call this in the aggregate's transaction, so rollback also rolls back the counter. */
    public void written(String clubId) {
        if (clubId != null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId).and("clubId").is(clubId)),
                    new Update().inc("sequence", 1), Counter.class);
        }
    }

    /** A real write obtains the same Mongo document lock as writers; the caller must keep its transaction open. */
    public long lock() {
        var counter = mongo.findAndModify(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())),
                new Update().setOnInsert("sequence", 0L).inc("lockVersion", 1),
                FindAndModifyOptions.options().upsert(true).returnNew(true), Counter.class);
        return counter.sequence();
    }
}

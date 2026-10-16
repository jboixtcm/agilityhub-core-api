package com.agilityhub.core.platform.persistence.privacy;

import com.agilityhub.core.shared.application.TenantContext;
import java.util.Collection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * S14 R-14-15 and S15 R-15-19, the technical traces of an erased member: the processed outbox records about it (their payloads
 * carry names, e-mails and diffs; S15 deletes them after `jobs.retention.domainEventsDays` anyway, and never touches an
 * unprocessed one), the idempotency replays of an erased account and its security events (R-14-15 «esborrats físicament»).
 */
@Repository
public class PrivacyTraceRepository {
    private final MongoTemplate mongo;
    public PrivacyTraceRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public long deleteProcessedEvents(Collection<String> memberIds, Collection<String> dogIds) {
        var about = new Criteria().orOperator(Criteria.where("aggregateId").in(memberIds), Criteria.where("aggregateId").in(dogIds),
                Criteria.where("payload.memberId").in(memberIds), Criteria.where("payload.dogId").in(dogIds));
        return mongo.remove(Query.query(Criteria.where("clubId").is(TenantContext.require()).and("status").is("PUBLISHED")).addCriteria(about),
                "domain_events").getDeletedCount();
    }
    public long deleteReplays(String accountId) {
        return mongo.remove(Query.query(Criteria.where("accountId").is(accountId)), "idempotency_records").getDeletedCount();
    }
    public long deleteSecurityEvents(String accountId) {
        return mongo.remove(Query.query(Criteria.where("accountId").is(accountId)), "security_events").getDeletedCount();
    }
}

package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceConceptRepository extends TenantRepository<InvoiceConcept> {
    public InvoiceConceptRepository(MongoTemplate mongo) { super(mongo, InvoiceConcept.class); }

    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(InvoiceConcept.class).createIndex(new org.springframework.data.mongodb.core.index.Index()
                .on("clubId", Sort.Direction.ASC).on("cancelledAt", Sort.Direction.ASC).on("firstPeriod", Sort.Direction.ASC)
                .named("invoice_concept_club_active_month"));
    }

    public List<InvoiceConcept> active() {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("cancelledAt").is(null))
                .with(Sort.by("firstPeriod", "description", "_id")), InvoiceConcept.class);
    }

    public List<InvoiceConcept> forPeriod(String period) {
        var dates = new Criteria().orOperator(Criteria.where("firstPeriod").is(period),
                Criteria.where("firstPeriod").lt(period).and("endsOn").gte(period + "-01"));
        return mongo.find(tenantQuery().addCriteria(Criteria.where("cancelledAt").is(null)).addCriteria(dates)
                .with(Sort.by("description", "_id")), InvoiceConcept.class);
    }

    public void cancel(String id, java.time.Instant at, String actor) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("cancelledAt").is(null)),
                new Update().set("cancelledAt", at).set("cancelledBy", actor), InvoiceConcept.class);
    }
}

package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class UpfrontPaymentRepository extends TenantRepository<UpfrontPayment> {
    public UpfrontPaymentRepository(MongoTemplate mongo) { super(mongo,UpfrontPayment.class); }
    /** E8-T01: `GET /upfront-payments?memberId=&status=` (S12 §6) and D10's «Pagaments a l'acte» read by member and status. */
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(UpfrontPayment.class).ensureIndex(new org.springframework.data.mongodb.core.index.Index()
                .on("clubId",org.springframework.data.domain.Sort.Direction.ASC).on("memberId",org.springframework.data.domain.Sort.Direction.ASC)
                .on("status",org.springframework.data.domain.Sort.Direction.ASC).named("upfront_club_member_status"));
    }
    public List<UpfrontPayment> member(String memberId) { return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId)),UpfrontPayment.class); }
    public void update(UpfrontPayment payment) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(payment.id())),new Update().set("status",payment.status())
                .set("amountDue",payment.amountDue()).set("amountPaid",payment.amountPaid()).set("provider",payment.provider()).set("checkoutSessionId",payment.checkoutSessionId()).set("paidAt",payment.paidAt()),UpfrontPayment.class);
    }
}

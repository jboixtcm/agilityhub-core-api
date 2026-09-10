package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class UpfrontPaymentRepository extends TenantRepository<UpfrontPayment> {
    public UpfrontPaymentRepository(MongoTemplate mongo) { super(mongo,UpfrontPayment.class); }
    public List<UpfrontPayment> member(String memberId) { return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId)),UpfrontPayment.class); }
    public void update(UpfrontPayment payment) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(payment.id())),new Update().set("status",payment.status())
                .set("amountDue",payment.amountDue()).set("amountPaid",payment.amountPaid()).set("provider",payment.provider()).set("checkoutSessionId",payment.checkoutSessionId()).set("paidAt",payment.paidAt()),UpfrontPayment.class);
    }
}

package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class SignupCheckoutRepository extends TenantRepository<SignupCheckoutSession> {
    public SignupCheckoutRepository(MongoTemplate mongo) { super(mongo,SignupCheckoutSession.class); }
    public boolean finish(String id,String status,String providerPaymentId) {
        var update=new Update().set("status",status);if(providerPaymentId!=null) update.set("providerPaymentId",providerPaymentId);
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is("PENDING")),update,SignupCheckoutSession.class).getModifiedCount()==1;
    }
    /** E34: the first late completion keeps its mark, so a provider retry of the same completion changes nothing. */
    public boolean markLateCompletion(String id,String providerPaymentId,Instant at) {
        var update=new Update().set("lateCompletionAt",at);if(providerPaymentId!=null) update.set("providerPaymentId",providerPaymentId);
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("lateCompletionAt").is(null)),update,SignupCheckoutSession.class).getModifiedCount()==1;
    }
}

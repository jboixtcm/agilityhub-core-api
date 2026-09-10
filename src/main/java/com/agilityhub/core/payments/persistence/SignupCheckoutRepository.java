package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class SignupCheckoutRepository extends TenantRepository<SignupCheckoutSession> {
    public SignupCheckoutRepository(MongoTemplate mongo) { super(mongo,SignupCheckoutSession.class); }
    public boolean finish(String id,String status) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is("PENDING")),new Update().set("status",status),SignupCheckoutSession.class).getModifiedCount()==1;
    }
}

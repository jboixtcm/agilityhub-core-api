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
    /** The member's open signup checkouts (`PENDING`, no `bookingId`). */
    public java.util.List<SignupCheckoutSession> openSignup(String memberId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("status").is("PENDING").and("bookingId").is(null)),SignupCheckoutSession.class);
    }
    /** The member's open signup checkout that the keyed request {@code requestRef} opened and that has not expired yet, if any. */
    public java.util.Optional<SignupCheckoutSession> openFor(String memberId,String requestRef,Instant now) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("requestRef").is(requestRef)
                .and("status").is("PENDING").and("bookingId").is(null).and("expiresAt").gt(now)),SignupCheckoutSession.class));
    }
    /** Whether the session is still `PENDING` (read inside the caller's transaction). */
    public boolean pending(String id) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is("PENDING")),SignupCheckoutSession.class);
    }
    /** E34: the first late completion keeps its mark, so a provider retry of the same completion changes nothing. */
    public boolean markLateCompletion(String id,String providerPaymentId,Instant at) {
        var update=new Update().set("lateCompletionAt",at);if(providerPaymentId!=null) update.set("providerPaymentId",providerPaymentId);
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("lateCompletionAt").is(null)),update,SignupCheckoutSession.class).getModifiedCount()==1;
    }
}

package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class SignupCheckoutRepository extends TenantRepository<SignupCheckoutSession> {
    public SignupCheckoutRepository(MongoTemplate mongo) { super(mongo,SignupCheckoutSession.class); }
    /** A `PENDING` session ends; the provider request it kept for a retry (its customer's e-mail included) goes with it (E5-T30). */
    public boolean finish(String id,String status,String providerPaymentId) {
        var update=new Update().set("status",status).unset("providerRequest");if(providerPaymentId!=null) update.set("providerPaymentId",providerPaymentId);
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is("PENDING")),update,SignupCheckoutSession.class).getModifiedCount()==1;
    }
    /** The member's open signup checkouts (`PENDING`, no `bookingId`). */
    public java.util.List<SignupCheckoutSession> openSignup(String memberId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("status").is("PENDING").and("bookingId").is(null)),SignupCheckoutSession.class);
    }
    /**
     * The member's open signup checkout that the keyed request {@code requestRef} opened and that has not expired yet, if any,
     * with the provider request it sent (E5-T30).
     */
    public java.util.Optional<SignupCheckoutSession> openFor(String memberId,String requestRef,Instant now) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("requestRef").is(requestRef)
                .and("status").is("PENDING").and("bookingId").is(null).and("expiresAt").gt(now).and("providerRequest").ne(null)),SignupCheckoutSession.class));
    }
    /** The member's open signup checkouts whose `expiresAt` has passed ({@code now} included): the provider can no longer complete them. */
    public java.util.List<SignupCheckoutSession> lapsedSignup(String memberId,Instant now) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("status").is("PENDING").and("bookingId").is(null).and("expiresAt").lte(now)),
                SignupCheckoutSession.class);
    }
    /**
     * The answer of the session's request is stored in the caller's transaction: whether the session is still `PENDING`. Its
     * provider request is dropped (E5-T30): from now on the key replays the stored answer, and no retry needs it.
     */
    public boolean answered(String id) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is("PENDING")),new Update().unset("providerRequest"),
                SignupCheckoutSession.class).getMatchedCount()==1;
    }
    /** E34: the first late completion keeps its mark, so a provider retry of the same completion changes nothing. */
    public boolean markLateCompletion(String id,String providerPaymentId,Instant at) {
        var update=new Update().set("lateCompletionAt",at);if(providerPaymentId!=null) update.set("providerPaymentId",providerPaymentId);
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("lateCompletionAt").is(null)),update,SignupCheckoutSession.class).getModifiedCount()==1;
    }
}

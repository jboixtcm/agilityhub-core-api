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
    public List<UpfrontPayment> forBooking(String bookingId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("bookingId").is(bookingId)), UpfrontPayment.class);
    }
    public void lock(String id) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().inc("paymentSequence", 1), UpfrontPayment.class);
    }
    /** Retain the cancellation obligation even when another pending refund reserves the entire capture. */
    public void refundCompensation(String id, String reason) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("refundCompensationReason").is(null)),
                new Update().set("refundCompensationReason", reason), UpfrontPayment.class);
    }
    public java.util.Optional<String> refundCompensation(String id) {
        var row = mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id)), org.bson.Document.class, "upfront_payments");
        return row == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(row.getString("refundCompensationReason"));
    }
    public List<UpfrontPayment> forIntent(String intent) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("stripe.paymentIntentId").is(intent)).with(org.springframework.data.domain.Sort.by("_id")), UpfrontPayment.class);
    }
    public void captured(String id, com.agilityhub.core.shared.domain.Money amount) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("stripeCapturedAmount", amount), UpfrontPayment.class);
    }
    public java.util.Optional<com.agilityhub.core.shared.domain.Money> captured(String id) {
        var row = mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id)), org.bson.Document.class, "upfront_payments");
        if (row == null || !(row.get("stripeCapturedAmount") instanceof org.bson.Document money)) { return java.util.Optional.empty(); }
        return java.util.Optional.of(new com.agilityhub.core.shared.domain.Money(((Number) money.get("amountMinor")).longValue(), money.getString("currency")));
    }
    public void stripe(String id, String intent, String charge) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("stripe.paymentIntentId", intent).set("stripe.chargeId", charge), UpfrontPayment.class);
    }
    public void refund(String id, UpfrontPayment.Refund refund, boolean full) {
        var update = new Update().push("refunds", refund); if (full) { update.set("status", "REFUNDED"); }
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), update, UpfrontPayment.class);
    }
    public void reverseRefund(String id, String refundId) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("refunds.providerRef").is(refundId)),
                new Update().pull("refunds", new org.bson.Document("providerRef", refundId)).set("status", "PAID"), UpfrontPayment.class);
    }
    public void update(UpfrontPayment payment) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(payment.id())),new Update().set("status",payment.status())
                .set("amountDue",payment.amountDue()).set("amountPaid",payment.amountPaid()).set("provider",payment.provider()).set("checkoutSessionId",payment.checkoutSessionId()).set("paidAt",payment.paidAt()),UpfrontPayment.class);
    }
}

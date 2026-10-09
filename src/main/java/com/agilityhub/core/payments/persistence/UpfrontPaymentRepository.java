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
    /**
     * R-12-20's cancellation obligation, retained even when other refunds reserve the entire capture: `REFUND` or `CREDIT`, and
     * `interventionAt` once automatic compensation has stopped and an admin must repay what is still owed (ruling E97).
     */
    public record Compensation(String policy, String reason, java.time.Instant interventionAt) { }
    /** One obligation per payment: the first recorded policy wins. */
    public void compensation(String id, String policy, String reason) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("refundCompensationReason").is(null).and("creditCompensationReason").is(null)),
                new Update().set("REFUND".equals(policy) ? "refundCompensationReason" : "creditCompensationReason", reason), UpfrontPayment.class);
    }
    public java.util.Optional<Compensation> compensation(String id) {
        var row = mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id)), org.bson.Document.class, "upfront_payments");
        if (row == null) { return java.util.Optional.empty(); }
        var at = row.getDate("compensationInterventionAt");
        var instant = at == null ? null : at.toInstant();
        if (row.getString("refundCompensationReason") != null) { return java.util.Optional.of(new Compensation("REFUND", row.getString("refundCompensationReason"), instant)); }
        if (row.getString("creditCompensationReason") != null) { return java.util.Optional.of(new Compensation("CREDIT", row.getString("creditCompensationReason"), instant)); }
        return java.util.Optional.empty();
    }
    /** True only for the call that stops automatic compensation, so the warning is logged once. */
    public boolean intervention(String id, java.time.Instant at) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("compensationInterventionAt").is(null)),
                new Update().set("compensationInterventionAt", at), UpfrontPayment.class).getModifiedCount() == 1;
    }
    /** Under the payment lock: remember the latest total, including zero, so a duplicate cannot notify it again. */
    public boolean interventionOwed(String id, com.agilityhub.core.shared.domain.Money amount) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("compensationInterventionOwed").ne(amount)),
                new Update().set("compensationInterventionOwed", amount), UpfrontPayment.class).getModifiedCount() == 1;
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

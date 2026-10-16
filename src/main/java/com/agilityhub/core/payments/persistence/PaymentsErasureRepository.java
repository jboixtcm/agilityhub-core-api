package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * S14 R-14-14 and R-14-15, the payments part. Invoices keep number, dates, lines, amounts and state (S12 R-12-10): only their
 * member and payment-method snapshots lose the person (`holderName`/`fullName` → «Abonat suprimit #n», no `maskedAccount` or
 * tax id). Collections, remittances, upfront payments and pack balances are kept as they are (their XML is never rewritten).
 */
@Repository
public class PaymentsErasureRepository {
    private final MongoTemplate mongo;
    public PaymentsErasureRepository(MongoTemplate mongo) { this.mongo = mongo; }
    private Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }

    public boolean openInvoices(Collection<String> memberIds) {
        return mongo.exists(Query.query(club().and("memberId").in(memberIds).and("status").in("PENDING", "COLLECTING", "FAILED")), "invoices");
    }
    public boolean openUpfrontPayments(Collection<String> memberIds) {
        return mongo.exists(Query.query(club().and("memberId").in(memberIds).and("status").in("DUE", "PARTIAL", "CHECKOUT_PENDING")), "upfront_payments");
    }

    /** One member's invoices: the snapshots of R-14-15 (`Invoice.paymentMethod.holderName`, `maskedAccount`; `memberSnapshot`). */
    public long eraseInvoices(String memberId, String label) {
        return mongo.updateMulti(Query.query(club().and("memberId").is(memberId)), new Update()
                .set("memberSnapshot.fullName", label).unset("memberSnapshot.taxId")
                .set("paymentMethod.holderName", label).unset("paymentMethod.maskedAccount"), "invoices").getModifiedCount();
    }
    /** The member's name copied into billing runs and simulations (technical summaries of D6). */
    public long eraseBillingNames(String memberId, String label) {
        long changed = mongo.updateMulti(Query.query(club().and("skipped.memberId").is(memberId)),
                new Update().set("skipped.$[s].memberName", label).filterArray(Criteria.where("s.memberId").is(memberId)), "billing_runs").getModifiedCount();
        for (String array : List.of("incidents", "cashMembers", "invoicesPreview")) {
            changed += mongo.updateMulti(Query.query(club().and(array + ".memberId").is(memberId)),
                    new Update().set(array + ".$[m].memberName", label).filterArray(Criteria.where("m.memberId").is(memberId)), "billing_simulations").getModifiedCount();
        }
        return changed;
    }
    /** A provider checkout request keeps the payer's e-mail while it is open; an erased member's goes. */
    public long eraseCheckoutRequests(Collection<String> memberIds) {
        return mongo.updateMulti(Query.query(club().and("memberId").in(memberIds)), new Update().unset("providerRequest"), "checkout_sessions").getModifiedCount();
    }

    /** R-14-16 (c): the remittances of the club whose XML file (debtor names and IBANs) is older than the cut-off and still stored. */
    public List<String> remittanceFilesBefore(Instant cutoff) {
        var query = Query.query(club().and("creationAt").lt(cutoff).and("fileKey").ne(null));
        query.fields().include("_id");
        return mongo.find(query, org.bson.Document.class, "remittances").stream().map(document -> document.getString("_id")).toList();
    }
    public String remittanceFile(String remittanceId) {
        var document = mongo.findOne(Query.query(club().and("_id").is(remittanceId)), org.bson.Document.class, "remittances");
        return document == null ? null : document.getString("fileKey");
    }
    /** The remittance keeps everything but its file reference; `filePurgedAt` records when the XML left the store. */
    public boolean remittanceFilePurged(String remittanceId, Instant at) {
        return mongo.updateFirst(Query.query(club().and("_id").is(remittanceId).and("fileKey").ne(null)),
                new Update().unset("fileKey").set("filePurgedAt", at), "remittances").getModifiedCount() == 1;
    }

    /** S14 R-14-13: the member's rows of a collection of the current club, raw. */
    public List<org.bson.Document> rows(String collection, String field, Collection<String> values) {
        if (values.isEmpty()) { return List.of(); }
        return mongo.find(Query.query(club().and(field).in(values)).with(org.springframework.data.domain.Sort.by("_id")), org.bson.Document.class, collection);
    }
}

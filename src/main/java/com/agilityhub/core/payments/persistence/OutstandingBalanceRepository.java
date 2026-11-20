package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.payments.domain.OutstandingBalance;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/** Outstanding invoices and on-the-spot payments across all periods, grouped by debtor and currency. */
@Repository
public class OutstandingBalanceRepository extends TenantRepository<Invoice> {
    public OutstandingBalanceRepository(MongoTemplate mongo) { super(mongo, Invoice.class); }


    public List<OutstandingBalance> balances() {
        String club = tenantQuery().getQueryObject().getString("clubId");
        var invoiceMatch = new Document("clubId", club).append("status", new Document("$in", List.of("PENDING", "FAILED", "COLLECTING")));
        var upfrontMatch = new Document("clubId", club).append("status", new Document("$in", List.of("DUE", "PARTIAL", "CHECKOUT_PENDING")));
        var balance = new Document("$subtract", List.of("$amountDue.amountMinor", "$amountPaid.amountMinor"));
        var upfront = List.of(new Document("$match", upfrontMatch), project("$amountDue.currency", balance, "CHECKOUT_PENDING", false));
        var group = new Document("_id", new Document("member", "$memberId").append("currency", "$currency"))
                .append("invoices", new Document("$sum", "$invoices")).append("upfront", new Document("$sum", "$upfront"))
                .append("processing", new Document("$sum", "$processing"));
        var member = new Document("from", "members").append("localField", "_id.member").append("foreignField", "_id")
                .append("pipeline", List.of(new Document("$match", new Document("clubId", club)),
                        new Document("$project", new Document("firstName", 1).append("lastName1", 1).append("lastName2", 1)))).append("as", "member");
        var pipeline = List.of(new Document("$match", invoiceMatch), project("$total.currency", "$total.amountMinor", "COLLECTING", true),
                new Document("$unionWith", new Document("coll", "upfront_payments").append("pipeline", upfront)),
                new Document("$match", new Document("balance", new Document("$gt", 0))),
                new Document("$group", group), new Document("$lookup", member),
                new Document("$sort", new Document("member.0.firstName", 1).append("member.0.lastName1", 1).append("_id", 1)));
        return mongo.getCollection("invoices").aggregate(pipeline).map(row -> {
            var id = row.get("_id", Document.class);
            String currency = id.getString("currency"), memberId = id.getString("member");
            var members = row.getList("member", Document.class);
            String name = members.isEmpty() ? memberId : java.util.stream.Stream.of("firstName", "lastName1", "lastName2")
                    .map(members.getFirst()::getString).filter(part -> part != null && !part.isBlank()).collect(java.util.stream.Collectors.joining(" "));
            return new OutstandingBalance(memberId, name.isBlank() ? memberId : name, money(row, "invoices", currency),
                    money(row, "upfront", currency), money(row, "processing", currency));
        }).into(new java.util.ArrayList<>());
    }

    private static Document project(String currency, Object amount, String processingStatus, boolean invoice) {
        var processing = new Document("$eq", List.of("$status", processingStatus));
        var due = new Document("$cond", List.of(processing, 0, amount));
        return new Document("$project", new Document("memberId", 1).append("currency", currency).append("balance", amount)
                .append(invoice ? "invoices" : "upfront", due).append("processing", new Document("$cond", List.of(processing, amount, 0))));
    }

    private static Money money(Document row, String key, String currency) { return new Money(((Number) row.get(key)).longValue(), currency); }
}

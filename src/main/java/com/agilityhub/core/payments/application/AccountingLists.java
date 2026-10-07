package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;

/** R-12-26: each invoice line is one accounting row, with the member and method frozen at issue. */
@Service
public class AccountingLists implements ListProvider {
    public static final List<String> COLUMNS = List.of("displayNumber", "issueDate", "period", "memberNumber", "fullName", "holderTaxId",
            "concept", "base", "taxPercent", "tax", "total", "method", "status", "collectionDate", "remittance", "paymentReference");
    private static final ListDefinition DEFINITION = new ListDefinition("accounting",
            Map.of("period", new ListDefinition.Field("period", ListDefinition.Type.TEXT)),
            Map.of("number", "number", "lineNo", "lines.lineNo"), List.of(), COLUMNS, COLUMNS,
            List.of("number,asc", "lineNo,asc"), Set.copyOf(COLUMNS));
    private final ClubConfigService configs; private final InvoiceRepository invoices;
    public AccountingLists(ClubConfigService configs, InvoiceRepository invoices) { this.configs = configs; this.invoices = invoices; }
    @Override public Set<String> keys() { return Set.of("accounting"); }
    @Override public ListDataset dataset(String key) {
        ListAccess.account(); if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (!configs.get(TenantContext.require()).modules().contains(Module.BILLING)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        var stages = new ArrayList<Document>();
        stages.add(new Document("$unwind", "$lines"));
        stages.add(new Document("$lookup", new Document("from", "collections").append("localField", "_id").append("foreignField", "invoiceId")
                .append("pipeline", List.of(new Document("$match", new Document("clubId", TenantContext.require())),
                        new Document("$sort", new Document("attempt", -1).append("createdAt", -1)), new Document("$limit", 1)))
                .append("as", "accountingCollection")));
        var projection = new LinkedHashMap<String, Object>();
        for (String column : List.of("displayNumber", "issueDate", "period", "status")) { projection.put(column, "$" + column); }
        projection.put("memberNumber", "$memberSnapshot.number"); projection.put("fullName", "$memberSnapshot.fullName");
        projection.put("holderTaxId", "$memberSnapshot.taxId"); projection.put("concept", "$lines.description");
        for (String column : List.of("base", "taxPercent", "tax", "total")) { projection.put(column, "$lines." + column); }
        projection.put("method", "$paymentMethod.type"); projection.put("collectionDate", "$paidAt");
        projection.put("remittance", "$remittanceId");
        projection.put("paymentReference", new Document("$ifNull", Arrays.asList(new Document("$first", "$accountingCollection.providerRef"),
                new Document("$ifNull", Arrays.asList(new Document("$first", "$accountingCollection.mandateRef"), "$paymentMethod.mandateRef")))));
        var rolledBack = invoices.rolledBackRunIds();
        return new ListDataset(DEFINITION, "invoices", stages, projection,
                Set.of("memberNumber", "holderTaxId", "collectionDate", "remittance", "paymentReference"), (field, value) -> String.valueOf(value))
                .withScope(query -> rolledBack.isEmpty() ? null : new Document("runId", new Document("$nin", rolledBack)));
    }
}

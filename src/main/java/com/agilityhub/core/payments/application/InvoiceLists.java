package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListAccess;
import com.agilityhub.core.shared.application.lists.ListDataset;
import com.agilityhub.core.shared.application.lists.ListProvider;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.springframework.stereotype.Service;

/**
 * S12 §6 `GET /invoices` (E8-T02): D6's receipts as a universal list (CONVENCIONS_API §4) over {@link BillingContractAccess#INVOICES}
 * — the declared filters, sorts and `q` (number, member). A row is the D6 columns: `concept` is the first line's frozen
 * description, with `(+n)` when the invoice has more lines; `member` is the snapshot taken at issue; `memberLastName` (the
 * sort) is joined from the census. ADMIN only, module `BILLING`, the open club only. Never an IBAN: the payment method is
 * its type. A receipt a rollback cancelled (R-12-14, ruling E87) is in the list, its counts, its search and its filter values
 * only when the `status` filter selects `CANCELLED` (`eq` or `in`), and then `rolledBack = true` marks it: its number was
 * reissued by the next run. A receipt is rolled back when its run is `ROLLED_BACK` (E8-T07 step 4), whatever its `cancelReason`.
 */
@Service
public class InvoiceLists implements ListProvider {
    private final ClubConfigService configs; private final InvoiceRepository invoices;
    public InvoiceLists(ClubConfigService configs, InvoiceRepository invoices) { this.configs = configs; this.invoices = invoices; }
    @Override public Set<String> keys() { return Set.of("invoices"); }
    @Override public ListDataset dataset(String key) {
        ListAccess.account();
        if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (!configs.get(TenantContext.require()).modules().contains(Module.BILLING)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        var stages = List.of(
                new Document("$lookup", new Document("from", "members").append("localField", "memberId").append("foreignField", "_id")
                        .append("pipeline", List.of(new Document("$project", new Document("lastName1", 1)))).append("as", "billingMember")),
                new Document("$set", new Document("memberLastName", new Document("$ifNull", List.of(new Document("$first", "$billingMember.lastName1"), "")))));
        var projection = new LinkedHashMap<String, Object>();
        for (String field : List.of("displayNumber", "number", "issueDate", "period", "total", "status", "kind", "runId", "remittanceId", "refundedTotal", "paidAt", "failedAt")) {
            projection.put(field, "$" + field);
        }
        projection.put("member", new Document("id", "$memberId").append("fullName", "$memberSnapshot.fullName").append("memberNumber", "$memberSnapshot.number"));
        projection.put("paymentMethodType", "$paymentMethod.type");
        var first = new Document("$arrayElemAt", List.of("$lines.description", 0));
        var more = new Document("$subtract", List.of(new Document("$size", "$lines"), 1));
        projection.put("concept", new Document("$cond", List.of(new Document("$gt", List.of(more, 0)),
                new Document("$concat", List.of(first, " (+", new Document("$toString", more), ")")), first)));
        var rolledBackRuns = invoices.rolledBackRunIds();
        projection.put("rolledBack", new Document("$in", List.of(new Document("$ifNull", java.util.Arrays.asList("$runId", null)), rolledBackRuns)));
        return new ListDataset(BillingContractAccess.INVOICES, "invoices", stages, projection,
                Set.of("runId", "remittanceId", "paidAt", "failedAt", "member.memberNumber"), (field, value) -> String.valueOf(value))
                .withScope(query -> selectsCancelled(query) || rolledBackRuns.isEmpty() ? null : new Document("runId", new Document("$nin", rolledBackRuns)));
    }
    /** Whether the query's `status` filter names `CANCELLED` (D6's «Anul·lats»): only then are the rolled-back receipts listed. */
    static boolean selectsCancelled(ListQuery query) {
        return query.filters().stream().anyMatch(filter -> filter.field().equals("status") && switch (filter.op()) {
            case eq -> "CANCELLED".equals(filter.value());
            case in -> filter.value() instanceof List<?> values && values.contains("CANCELLED");
            default -> false;
        });
    }
}

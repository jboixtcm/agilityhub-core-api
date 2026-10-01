package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListAccess;
import com.agilityhub.core.shared.application.lists.ListDataset;
import com.agilityhub.core.shared.application.lists.ListProvider;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.springframework.stereotype.Service;

/**
 * S12 §6 `GET /remittances` (E8-T03): D6 «Remeses» as a universal list (CONVENCIONS_API §4) over
 * {@link BillingContractAccess#REMITTANCES} — filters `period` and `status`, sorts `creationAt` (default, newest first) and
 * `period`. A row is what the screen shows: month, creation date, collection date, invoice count, total, status, whether the
 * XML can be downloaded (`fileAvailable`, never the storage key) and when it was sent to the bank. A rolled-back remittance is
 * listed (its file is kept, R-12-14). A club that never generated a remittance — one without `SEPA_XML` — gets an empty page
 * (R-12-28, T-12-32). ADMIN only, module `BILLING`, the open club only. Never the creditor or a debtor account.
 */
@Service
public class RemittanceLists implements ListProvider {
    private final ClubConfigService configs;
    public RemittanceLists(ClubConfigService configs) { this.configs = configs; }
    @Override public Set<String> keys() { return Set.of("remittances"); }
    @Override public ListDataset dataset(String key) {
        ListAccess.account();
        if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (!configs.get(TenantContext.require()).modules().contains(Module.BILLING)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        var projection = new LinkedHashMap<String, Object>();
        for (String field : List.of("period", "messageId", "creationAt", "requestedCollectionDate", "count", "total", "status", "submittedAt")) {
            projection.put(field, "$" + field);
        }
        // A string sorts after null and a missing field before it: true exactly when a file key is stored.
        projection.put("fileAvailable", new Document("$gt", java.util.Arrays.asList("$fileKey", null)));
        return new ListDataset(BillingContractAccess.REMITTANCES, "remittances", List.of(), projection, Set.of("submittedAt"), (field, value) -> String.valueOf(value));
    }
}

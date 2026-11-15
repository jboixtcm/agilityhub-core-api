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
 * PAY-02 (owner decision of 13 Nov 2026), S12 §6 `GET /billing/stripe-events`: the club's Stripe events whose processing failed
 * (`outcome = FAILED`, R-12-21) as a universal list (CONVENCIONS_API §4) over {@link BillingContractAccess#STRIPE_EVENTS},
 * newest first. A row is the event's id and type, when it arrived, the attempts it spent (an outage spends none, so 0 when it
 * never spent one) and either when it runs again (`nextAttemptAt`, still retrying) or when its retries ran out
 * (`processedAt`). Never its stored recovery data. ADMIN only, module `BILLING`, the open club only.
 */
@Service
public class StripeEventLists implements ListProvider {
    private final ClubConfigService configs;
    public StripeEventLists(ClubConfigService configs) { this.configs = configs; }
    @Override public Set<String> keys() { return Set.of("stripe-events"); }
    @Override public ListDataset dataset(String key) {
        ListAccess.account();
        if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (!configs.get(TenantContext.require()).modules().contains(Module.BILLING)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        var projection = new LinkedHashMap<String, Object>();
        for (String field : List.of("type", "receivedAt", "nextAttemptAt", "processedAt")) { projection.put(field, "$" + field); }
        projection.put("attempts", new Document("$ifNull", List.of("$attempts", 0)));
        return new ListDataset(BillingContractAccess.STRIPE_EVENTS, "stripe_events", List.of(new Document("$match", new Document("outcome", "FAILED"))),
                projection, Set.of(), (field, value) -> String.valueOf(value));
    }
}

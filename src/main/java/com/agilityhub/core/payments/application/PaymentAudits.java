package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.domain.Money;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Called inside the same transaction as the corresponding durable payment checkpoint. */
@Service
public class PaymentAudits {
    @Audited(action = AuditAction.CARD_CHARGES_STARTED, entityType = "'BillingRun'", entity = "#id", before = "null", details = "#result")
    public Map<String, Object> started(String id, int submitted, int skipped) {
        return Map.of("runId", id, "submitted", submitted, "skipped", skipped);
    }
    @Audited(action = AuditAction.PAYMENT_REFUNDED, entityType = "#type", entity = "#id", reason = "#reason", before = "null", details = "#result")
    public Map<String, Object> refunded(String type, String id, Money amount, String providerRef, String reason) {
        return Map.of("amount", amount, "providerRef", providerRef);
    }
    /**
     * E8-T11: automatic compensation stopped and an admin must settle {@code amount} by hand, in the member's audit trail. Under the
     * closed catalogs it is a {@code PAYMENT_REFUNDED} entry without money movement: `reason` says which case and `details` the
     * amount — {@code owed} (the club still owes the member) or {@code overpaid} (Stripe refunded more than the credit allowed).
     */
    @Audited(action = AuditAction.PAYMENT_REFUNDED, entityType = "'UpfrontPayment'", entity = "#id", member = "#memberId", reason = "#reason",
            before = "null", details = "#result")
    public Map<String, Object> intervention(String id, String memberId, String reason, String kind, Money amount) {
        return Map.of(kind, amount, "intervention", true);
    }
    @Audited(action = AuditAction.UPFRONT_PAYMENT_RECORDED, entityType = "'UpfrontPayment'", entity = "#id", member = "#memberId", before = "null", details = "#result")
    public Map<String, Object> recorded(String id, String memberId, Money amount) { return Map.of("amountPaid", amount); }
}

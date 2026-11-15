package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import com.agilityhub.core.shared.application.lists.ListEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.agilityhub.core.payments.api.BillingContracts.StripeEventPage;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * PAY-02 (owner decision of 13 Nov 2026), S12 §6: the club's Stripe events whose processing failed (R-12-21), for the admin's
 * billing view. ADMIN only, module `BILLING`, impersonation refused, read only.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class StripeEventsController {
    private final BillingContractAccess access; private final ListEngine lists;
    public StripeEventsController(BillingContractAccess access, ListEngine lists) { this.access = access; this.lists = lists; }

    @GetMapping("/api/v1/billing/stripe-events")
    @ListContract(sortable = {"receivedAt"}, columns = {"type*", "receivedAt*", "attempts*", "nextAttemptAt*", "processedAt*"}, paged = true,
            searchable = false, fields = {"id", "type", "receivedAt", "attempts", "nextAttemptAt", "processedAt"})
    @ContractErrors({INVALID_FILTER, MODULE_DISABLED})
    @Operation(summary = "stripeEvents", description = BillingController.ROLES + "PAY-02 (R-12-21): the club's Stripe events whose processing "
            + "failed, universal list (CONVENCIONS_API §4), newest receivedAt first; no filter and no free-text search (a filter or a q → 400 "
            + "INVALID_FILTER). A failed processing is retried with a growing backoff (1 min, 5 min, 15 min, 30 min, 1 h, 2 h, 4 h, 8 h, 8 h: ten "
            + "attempts over about 24 hours); an outage of the provider (connection error, 5xx, 401, 403, 429) spends no attempt and waits 5 min. "
            + "A row: the Stripe event id and type, receivedAt, the attempts spent, and nextAttemptAt while it is retrying or processedAt once its "
            + "retries ran out. A FAILED event is kept until the retention cleanup (jobs.retention.stripeEventsDays); Stripe delivering it again "
            + "(a resend from the Stripe dashboard) puts it back in the queue." + BillingController.SERVED,
            responses = @ApiResponse(responseCode = "200", description = "StripeEventPage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = StripeEventPage.class))))
    public Object stripeEvents(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.stripeEventList(params);
        return lists.list("stripe-events", params);
    }
}

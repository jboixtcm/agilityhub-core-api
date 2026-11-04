package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.payments.domain.InvoiceAmounts;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.MemberIdentityAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/concepts")
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class InvoiceConceptController {
    public record InvoiceConceptInput(@NotBlank @Size(max=140) String description, @NotNull Money amount,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxPercent, @NotNull String firstPeriod,
            @Schema(nullable=true, requiredMode=Schema.RequiredMode.NOT_REQUIRED) LocalDate endsOn, @NotNull Boolean allMembers, @NotNull Set<@NotBlank String> memberIds) {}

    public record InvoiceConceptView(String id, String description, Money amount, BigDecimal taxPercent, String firstPeriod,
            @Schema(nullable=true, requiredMode=Schema.RequiredMode.NOT_REQUIRED) String endsOn, boolean allMembers, List<String> memberNames) {}
    private final MemberIdentityAccess names;
    private final InvoiceConceptRepository concepts;
    private final BillingDocuments.BillingRunRepository runs;
    private final BillingContractAccess access;
    private final InvoicingService invoicing;
    private final BillingSimulationService simulator;
    private final BillingTransactions transactions;
    private final Clock clock;
    private final ObjectMapper mapper;

    public InvoiceConceptController(InvoiceConceptRepository concepts, BillingDocuments.BillingRunRepository runs,
            BillingContractAccess access, InvoicingService invoicing, BillingSimulationService simulator,
            BillingTransactions transactions, Clock clock, ObjectMapper mapper, MemberIdentityAccess names) {
        this.names = names;
        this.concepts = concepts;
        this.runs = runs;
        this.access = access;
        this.invoicing = invoicing;
        this.simulator = simulator;
        this.transactions = transactions;
        this.clock = clock;
        this.mapper = mapper;
    }

    @GetMapping
    @ContractErrors({MODULE_DISABLED})
    @Operation(summary="listInvoiceConcepts", description=BillingController.ROLES + "Lists the club's uncancelled invoice concepts.")
    public List<InvoiceConceptView> list() {
        access.tenant();
        var cache = new HashMap<String,String>();
        return concepts.active().stream().map(rule -> view(rule, cache)).toList();
    }

    private InvoiceConceptView view(InvoiceConcept rule, Map<String,String> cache) {
        var members = rule.memberIds().stream().map(id -> cache.computeIfAbsent(id, key -> names.displayName(key).orElse(key)))
                .sorted().toList();
        return new InvoiceConceptView(rule.id(), rule.description(), rule.amount(), rule.taxPercent(), rule.firstPeriod(),
                rule.endsOn(), rule.allMembers(), members);
    }

    @PostMapping
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, NOT_FOUND, RUN_EXISTS, BILLING_BUSY, CURRENCY_MISMATCH, IDEMPOTENCY_KEY_REUSED, STALE_VERSION})
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="createInvoiceConcept", description=BillingController.ROLES + "Without endsOn, applies once in firstPeriod. Otherwise applies monthly through the month of endsOn, inclusive.")
    public InvoiceConceptView create(@RequestBody @Valid InvoiceConceptInput body, @RequestHeader("Idempotency-Key") UUID key) {
        access.tenant();
        access.month("firstPeriod", body.firstPeriod());
        if (body.endsOn() != null && body.endsOn().isBefore(LocalDate.parse(body.firstPeriod() + "-01"))) {
            throw BillingContractAccess.invalid("endsOn");
        }
        if (body.amount().amountMinor() == 0) { throw BillingContractAccess.invalid("amount"); }
        if (body.allMembers() != body.memberIds().isEmpty()) { throw BillingContractAccess.invalid("memberIds"); }
        return simulator.locked("concept", () -> transactions.keyed(201, () -> {
            if (runs.live(body.firstPeriod()).isPresent()) { throw new ApiException(ErrorCode.RUN_EXISTS); }
            InvoiceAmounts.requireCurrency(body.amount(), invoicing.context().currency());
            body.memberIds().forEach(access::member);
            var rule = concepts.insert(new InvoiceConcept(UUID.randomUUID().toString(), TenantContext.require(), body.description().strip(),
                    body.amount(), body.taxPercent(), body.firstPeriod(), Objects.toString(body.endsOn(), null), body.allMembers(),
                    body.memberIds(), clock.instant(), BillingEvents.actor(), null, null));
            return view(rule, new HashMap<>());
        }, this::json));
    }

    @DeleteMapping("/{id}")
    @ContractErrors({MODULE_DISABLED, NOT_FOUND, BILLING_BUSY})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary="cancelInvoiceConcept", description=BillingController.ROLES + "Stops future application. Issued invoices retain their lines.")
    public void cancel(@PathVariable String id) {
        access.tenant();
        simulator.locked("concept", () -> {
            concepts.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            concepts.cancel(id, clock.instant(), BillingEvents.actor());
            return null;
        });
    }

    private byte[] json(InvoiceConceptView value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}

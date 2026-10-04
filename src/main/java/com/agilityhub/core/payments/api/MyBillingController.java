package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.BillingContracts.*;
import static com.agilityhub.core.payments.api.BillingRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 §6 member routes (screen 12 «Rebuts», the card banner, screen 13's pack): MEMBER, also the impersonation token
 * (MATRIU «Facturació»: a member reads their own invoices, and with R-12-27 those of their family group's holder); a token
 * without MEMBER → 403. Module `BILLING` (R-12-27: off → 404), `PACKS` for the pack. The invoices and their receipt are served
 * (E8-T02); card setup and packs run their guards and answer 501 NOT_IMPLEMENTED until E8-T04 and E8-T05.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize("hasRole('MEMBER')")
public class MyBillingController {
    static final String ROLES = "Roles: MEMBER, also the impersonation token (ADMIN- or INSTRUCTOR-only tokens → 403). BILLING off → 404 MODULE_DISABLED. ";
    static final String STUB = BillingController.STUB;
    static final String SERVED = " Tenant comes from the JWT; another member's or another club's invoice → 404.";
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.payments.application.PaymentCheckouts checkouts;
    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final BillingContractAccess access; private final com.agilityhub.core.payments.application.BillingQueries queries;
    private final com.agilityhub.core.payments.application.ReceiptPdf receipts; private final com.agilityhub.core.platform.application.ClubConfigService configs;
    public MyBillingController(BillingContractAccess access, com.agilityhub.core.payments.application.BillingQueries queries,
            com.agilityhub.core.payments.application.ReceiptPdf receipts, com.agilityhub.core.platform.application.ClubConfigService configs) {
        this.access = access; this.queries = queries; this.receipts = receipts; this.configs = configs;
    }

    @GetMapping("/api/v1/me/invoices")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED})
    @Operation(summary = "myInvoices", description = ROLES + "R-12-27 screen 12 «Rebuts»: the caller's invoices and, with FAMILY_GROUP, those of the "
            + "holder of the family group the caller belongs to (familyGroup = true), newest first, read only, never an IBAN. The lines keep their "
            + "frozen descriptions; the client formats period and amounts in the reader's locale (R-12-30)." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "MeInvoicePage", useReturnTypeSchema = true))
    public MeInvoicePage myInvoices(@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        access.tenant();
        var result = queries.memberInvoices(access.me(), page, size);
        return new MeInvoicePage(result.items().stream().map(BillingViews::meInvoice).toList(), page, size, result.totalItems(),
                (int) Math.min(Integer.MAX_VALUE, (result.totalItems() + size - 1) / size));
    }

    @GetMapping("/api/v1/me/invoices/{id}")
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "myInvoice", description = ROLES + "R-12-27: one of the caller's invoices, or of their family group's holder (familyGroup = "
            + "true)." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "MeInvoice", useReturnTypeSchema = true))
    public MeInvoice myInvoice(@PathVariable String id) {
        access.ownInvoice(id);
        return BillingViews.meInvoice(queries.memberInvoice(access.me(), id));
    }

    @GetMapping("/api/v1/me/invoices/{id}/document")
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "myInvoiceDocument", description = ROLES + "[Descarrega el justificant]: the receipt PDF («Rebut», T-12-20) of one of the "
            + "caller's invoices (or of their family group's holder), in the caller's language with the frozen descriptions and the club's mark; "
            + "never an IBAN." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Receipt PDF",
                    headers = @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                    content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public org.springframework.http.ResponseEntity<byte[]> myInvoiceDocument(@PathVariable String id) {
        access.ownInvoice(id);
        var item = queries.memberInvoice(access.me(), id);
        var config = configs.get(com.agilityhub.core.shared.application.TenantContext.require());
        return InvoicesController.pdf(item.invoice().displayNumber(), receipts.render(item.invoice(), item.familyGroup(), config,
                com.agilityhub.core.shared.application.LocaleContext.current()));
    }

    @PostMapping("/api/v1/me/card-setup")
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MEMBER_ERASED, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, PAYMENT_PROVIDER_NOT_ENABLED, PROVIDER_CONFIG_INVALID, RATE_LIMITED})
    @Operation(summary = "myCardSetup", description = ROLES + "R-12-22 [Actualitza la targeta] (N-35's retry_link): a Stripe Checkout session in "
            + "mode=setup for the caller; setup_intent.succeeded saves the new card (CARD.invalid = false) and sends N-38. STRIPE not enabled → 422 "
            + "PAYMENT_PROVIDER_NOT_ENABLED. successUrl/cancelUrl on the club's app host." + BillingController.SERVED,
            responses = @ApiResponse(responseCode = "201", description = "CardSetupLink", useReturnTypeSchema = true))
    public CardSetupLink myCardSetup(@Valid @RequestBody CardSetupRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        // E8-T01 round 2: the caller's own member of the club first (a token without one → 404; erased → 409), then the stub.
        access.mutableMember(access.me());
        var result = checkouts.create(access.me(), null, java.util.List.of(), true, request.successUrl(), request.cancelUrl(), created -> {
            try { return mapper.writeValueAsBytes(new CardSetupLink(created.checkoutUrl())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        });
        return new CardSetupLink(result.checkoutUrl());
    }

    @GetMapping("/api/v1/me/pack-balances")
    @AllowsImpersonation
    @RequiresModule(Module.PACKS)
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "myPackBalances", description = ROLES + "PACKS off → 404 MODULE_DISABLED. Screen 13 «Pack {n} — amb {gos}»: the packs of "
            + "the caller's dogs with their movements, the live ones first." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "PackBalanceDetail[]", useReturnTypeSchema = true))
    public List<PackBalanceDetail> myPackBalances() {
        access.member(access.me());
        throw new UnsupportedOperationException();
    }
}

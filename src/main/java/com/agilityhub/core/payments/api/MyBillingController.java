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
 * (MATRIU «Facturació»: a member reads their own invoices only); a token without MEMBER → 403. Module `BILLING` (R-12-27:
 * off → 404), `PACKS` for the pack. Every operation runs its guards and then answers 501 NOT_IMPLEMENTED until E8-T02
 * (invoices), E8-T04 (card setup) and E8-T05 (packs).
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize("hasRole('MEMBER')")
public class MyBillingController {
    static final String ROLES = "Roles: MEMBER, also the impersonation token (ADMIN- or INSTRUCTOR-only tokens → 403). BILLING off → 404 MODULE_DISABLED. ";
    static final String STUB = BillingController.STUB;
    private final BillingContractAccess access;
    public MyBillingController(BillingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/me/invoices")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED})
    @Operation(summary = "myInvoices", description = ROLES + "R-12-27 screen 12 «Rebuts»: the caller's invoices and those of the family group holder "
            + "that cover them (familyGroup = true), newest first, read only, never an IBAN." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MeInvoicePage", useReturnTypeSchema = true))
    public MeInvoicePage myInvoices(@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/invoices/{id}")
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "myInvoice", description = ROLES + "R-12-27: one of the caller's invoices; another member's or another club's → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MeInvoice", useReturnTypeSchema = true))
    public MeInvoice myInvoice(@PathVariable String id) {
        access.ownInvoice(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/invoices/{id}/document")
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "myInvoiceDocument", description = ROLES + "[Descarrega el justificant]: the receipt PDF of one of the caller's invoices "
            + "(T-12-20); another member's → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Receipt PDF",
                    headers = @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                    content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public org.springframework.http.ResponseEntity<byte[]> myInvoiceDocument(@PathVariable String id) {
        access.ownInvoice(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/me/card-setup")
    @AllowsImpersonation
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, PAYMENT_PROVIDER_NOT_ENABLED})
    @Operation(summary = "myCardSetup", description = ROLES + "R-12-22 [Actualitza la targeta] (N-35's retry_link): a Stripe Checkout session in "
            + "mode=setup for the caller; setup_intent.succeeded saves the new card (CARD.invalid = false) and sends N-38. STRIPE not enabled → 422 "
            + "PAYMENT_PROVIDER_NOT_ENABLED. successUrl/cancelUrl on the club's app host." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "CardSetupLink", useReturnTypeSchema = true))
    public CardSetupLink myCardSetup(@Valid @RequestBody CardSetupRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/pack-balances")
    @AllowsImpersonation
    @RequiresModule(Module.PACKS)
    @ContractErrors({MODULE_DISABLED})
    @Operation(summary = "myPackBalances", description = ROLES + "PACKS off → 404 MODULE_DISABLED. Screen 13 «Pack {n} — amb {gos}»: the packs of "
            + "the caller's dogs with their movements, the live ones first." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "PackBalanceDetail[]", useReturnTypeSchema = true))
    public List<PackBalanceDetail> myPackBalances() {
        access.tenant();
        throw new UnsupportedOperationException();
    }
}

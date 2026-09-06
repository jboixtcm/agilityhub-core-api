package com.agilityhub.core.clubs.catalogs.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import static com.agilityhub.core.clubs.catalogs.api.CatalogResponses.*;
import static com.agilityhub.core.clubs.catalogs.api.CatalogRequests.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class PricesController {
    @GetMapping("/api/v1/prices")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.BILLING)
    @ListContract(filterable = {"planId", "concept"}, sortable = {},
            columns = {"concept*", "amount*", "taxPercent*", "validFrom*", "validTo*", "status*"}, paged = false, exportable = false)
    @Operation(summary = "List prices",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "CatalogItems<Price>"))
    public CatalogItems<Price> listPrices(@RequestParam String planId, @RequestParam(required = false) PriceConcept concept) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/prices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.BILLING)
    @ContractErrors({CURRENCY_MISMATCH, PRICE_OVERLAP, PRICE_LOCKED})
    @Operation(summary = "Create price",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "PriceCreated"))
    public PriceCreated createPrice(@Valid @RequestBody PriceCreate request) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/prices/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.BILLING)
    @ContractErrors({PRICE_LOCKED, STALE_VERSION})
    @Operation(summary = "Update price",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Price"))
    public Price updatePrice(@PathVariable String id, @Valid @RequestBody PricePatch request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/prices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.BILLING)
    @ContractErrors({PRICE_LOCKED})
    @Operation(summary = "Delete price",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deletePrice(@PathVariable String id) { throw new UnsupportedOperationException(); }

}

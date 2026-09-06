package com.agilityhub.core.platform.api;

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
import static com.agilityhub.core.platform.api.SettingsContracts.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class SettingsController {
    @GetMapping("/api/v1/club")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Club settings",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "ClubSettings"))
    public ClubSettings clubSettings() { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/parameters")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"key*", "label*", "value*", "isOverride*", "lastChange", "editableBy", "constraints", "module"}, paged = false, exportable = false)
    @Operation(summary = "Parameters",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Parameters"))
    public Parameters parameters(@RequestParam(required = false) String block) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/parameters/{key}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({UNKNOWN_PARAMETER})
    @Operation(summary = "Parameter",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter parameter(@PathVariable String key, @RequestParam(required = false) String scopeRef) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/parameters/{key}/history")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"value*", "changedAt*", "changedByAccountId*", "reason"}, paged = false, exportable = false)
    @ContractErrors({UNKNOWN_PARAMETER})
    @Operation(summary = "Parameter history",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "List<ParameterHistoryEntry>"))
    public List<ParameterHistoryEntry> parameterHistory(@PathVariable String key, @RequestParam(required = false) String scopeRef) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/parameters/{key}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PARAMETER_INVALID, UNKNOWN_PARAMETER, PLATFORM_ONLY, MODULE_DISABLED, STALE_VERSION, TIMEZONE_CHANGE_BLOCKED})
    @Operation(summary = "Update parameter",
            description = "S02 §6, R-02-04. Versioned parameter override; the key determines module and editableBy guards. JSON value follows the catalog type. No new parameter keys are accepted.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter updateParameter(@PathVariable String key, @Valid @RequestBody ParameterUpdate request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/parameters/{key}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({UNKNOWN_PARAMETER, PLATFORM_ONLY, MODULE_DISABLED})
    @Operation(summary = "Reset parameter",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter resetParameter(@PathVariable String key, @RequestParam(required = false) String scopeRef) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/club/modules/{module}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PLATFORM_ONLY, MODULE_DEPENDENCY})
    @Operation(summary = "Update module",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "ModuleSettings"))
    public ModuleSettings updateModule(@PathVariable String module, @Valid @RequestBody ModuleUpdate request) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/club/opening-hours")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PARAMETER_INVALID, STALE_VERSION})
    @Operation(summary = "Update opening hours",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter updateOpeningHours(@Valid @RequestBody OpeningHoursUpdate request) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/club/holidays")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PARAMETER_INVALID, STALE_VERSION})
    @Operation(summary = "Update holidays",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter updateHolidays(@Valid @RequestBody HolidaysUpdate request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/country-profile/postal-codes/{code}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ListContract(filterable = {}, sortable = {},
            columns = {"town*", "region*"}, paged = false, exportable = false)
    @Operation(summary = "Postal code towns",
            description = "S02 §6, R-02-06. Anonymous host-selected club or JWT club context; empty array when the country profile has no lookup.",
            responses = @ApiResponse(responseCode = "200", description = "List<PostalTown>"))
    public List<PostalTown> postalCodeTowns(@PathVariable String code) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/platform/parameter-catalog")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"key*", "type*", "default*", "block*", "editableBy*", "constraints", "modules", "scope"}, paged = false, exportable = false)
    @Operation(summary = "Parameter catalog",
            description = "S02 §6. Global AGILITYHUB_ADMIN contract with product defaults; no club or secrets required.",
            responses = @ApiResponse(responseCode = "200", description = "List<ParameterDefinition>"))
    public List<ParameterDefinition> parameterCatalog() { throw new UnsupportedOperationException(); }

}

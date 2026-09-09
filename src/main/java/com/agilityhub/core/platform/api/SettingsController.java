package com.agilityhub.core.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import static com.agilityhub.core.platform.api.SettingsContracts.*;

/** Tenant-scoped configuration with explicit public projections. */
@RestController
public class SettingsController {
    private final ParameterSettingsService parameters;
    private final ClubSettingsService clubs;
    private final ParameterCatalog catalog;
    private final ClubConfigService configs;
    private final SettingsMapper views;
    private final ObjectMapper mapper;
    public SettingsController(ParameterSettingsService parameters, ClubSettingsService clubs, ParameterCatalog catalog,
            ClubConfigService configs, SettingsMapper views, ObjectMapper mapper) {
        this.parameters = parameters; this.clubs = clubs; this.catalog = catalog;
        this.configs = configs; this.views = views; this.mapper = mapper;
    }

    @GetMapping("/api/v1/club")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Club settings",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "ClubSettings"))
    public ClubSettings clubSettings() { return views.club(clubs.club()); }

    @GetMapping("/api/v1/parameters")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"key*", "label*", "value*", "isOverride*", "lastChange", "editableBy", "constraints", "module"}, paged = false, exportable = false)
    @Operation(summary = "Parameters",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "Parameters"))
    public Parameters parameters(@RequestParam(required = false) String block) { return views.parameters(parameters.list(block)); }

    @GetMapping("/api/v1/parameters/{key}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({UNKNOWN_PARAMETER})
    @Operation(summary = "Parameter",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter parameter(@PathVariable String key, @RequestParam(required = false) String scopeRef) { return views.parameter(parameters.view(key, scopeRef)); }

    @GetMapping("/api/v1/parameters/{key}/history")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"value*", "changedAt*", "changedByAccountId*", "reason"}, paged = false, exportable = false)
    @ContractErrors({UNKNOWN_PARAMETER})
    @Operation(summary = "Parameter history",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "List<ParameterHistoryEntry>"))
    public List<ParameterHistoryEntry> parameterHistory(@PathVariable String key, @RequestParam(required = false) String scopeRef) { return parameter(key, scopeRef).history(); }

    @PutMapping("/api/v1/parameters/{key}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PARAMETER_INVALID, UNKNOWN_PARAMETER, PLATFORM_ONLY, MODULE_DISABLED, STALE_VERSION, TIMEZONE_CHANGE_BLOCKED})
    @Operation(summary = "Update parameter",
            description = "S02 §6, R-02-04. Versioned parameter override; the key determines module and editableBy guards. JSON value follows the catalog type. No new parameter keys are accepted.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter updateParameter(@PathVariable String key, @Valid @RequestBody ParameterUpdate request) {
        parameters.update(key, request.value(), request.scopeRef(), request.version(), request.reason());
        return parameter(key, request.scopeRef());
    }

    @DeleteMapping("/api/v1/parameters/{key}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({UNKNOWN_PARAMETER, PLATFORM_ONLY, MODULE_DISABLED})
    @Operation(summary = "Reset parameter",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter resetParameter(@PathVariable String key, @RequestParam(required = false) String scopeRef) {
        parameters.reset(key, scopeRef); return parameter(key, scopeRef);
    }

    @PutMapping("/api/v1/club/modules/{module}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PLATFORM_ONLY, MODULE_DEPENDENCY})
    @Operation(summary = "Update module",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "ModuleSettings"))
    public ModuleSettings updateModule(@PathVariable String module, @Valid @RequestBody ModuleUpdate request) {
        return new ModuleSettings(clubs.updateModule(module, request.enabled()).modules());
    }

    @PutMapping("/api/v1/club/opening-hours")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PARAMETER_INVALID, STALE_VERSION})
    @Operation(summary = "Update opening hours",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter updateOpeningHours(@Valid @RequestBody OpeningHoursUpdate request) {
        return updateParameter("club.openingHours", new ParameterUpdate(mapper.convertValue(request.value(), Map.class), request.reason(), null, request.version()));
    }

    @PutMapping("/api/v1/club/holidays")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PARAMETER_INVALID, STALE_VERSION})
    @Operation(summary = "Update holidays",
            description = "S02. Tenant comes from the JWT. ADMIN endpoints reject impersonation. Changes are versioned and audited.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter updateHolidays(@Valid @RequestBody HolidaysUpdate request) {
        return updateParameter("club.holidays", new ParameterUpdate(mapper.convertValue(request.value(), List.class), request.reason(), null, request.version()));
    }

    @GetMapping("/api/v1/country-profile/postal-codes/{code}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ListContract(filterable = {}, sortable = {},
            columns = {"town*", "region*"}, paged = false, exportable = false)
    @Operation(summary = "Postal code towns",
            description = "S02 §6, R-02-06. Anonymous host-selected club or JWT club context; empty array when the country profile has no lookup.",
            responses = @ApiResponse(responseCode = "200", description = "List<PostalTown>"))
    public List<PostalTown> postalCodeTowns(@PathVariable String code) {
        return configs.get(TenantContext.require()).countryProfile().postalCodeLookup(code).stream().map(t -> new PostalTown(t.town(), t.region())).toList();
    }

    @GetMapping("/api/v1/platform/parameter-catalog")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"key*", "type*", "default*", "block*", "editableBy*", "constraints", "modules", "scope"}, paged = false, exportable = false)
    @Operation(summary = "Parameter catalog",
            description = "S02 §6. Global AGILITYHUB_ADMIN contract with product defaults; no club or secrets required.",
            responses = @ApiResponse(responseCode = "200", description = "List<ParameterDefinition>"))
    public List<ParameterDefinition> parameterCatalog() { return catalog.entries().values().stream().map(views::definition).toList(); }

    @PutMapping("/api/v1/club")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({PLATFORM_ONLY, TIMEZONE_CHANGE_BLOCKED, STALE_VERSION, VALIDATION_ERROR})
    @Operation(summary = "Update club settings", description = "S02. Versioned edit of name, contact and theme. Console-only fields are rejected.",
            responses = @ApiResponse(responseCode = "200", description = "ClubSettings"))
    public ClubSettings updateClub(@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = ClubUpdate.class)))
            @RequestBody Map<String, Object> request) {
        clubs.update(request); return clubSettings();
    }

    @GetMapping("/api/v1/club/opening-hours")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Opening hours", description = "S02. Resolved club.openingHours, version and history.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter openingHours() { return parameter("club.openingHours", null); }

    @GetMapping("/api/v1/club/holidays")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Holidays", description = "S02. Resolved local holiday dates with labels, version and history.",
            responses = @ApiResponse(responseCode = "200", description = "Parameter"))
    public Parameter holidays() { return parameter("club.holidays", null); }

    @GetMapping("/api/v1/country-profile")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @Operation(summary = "Country profile", description = "S02. Public country validation and formatting capabilities for the host-selected club.",
            responses = @ApiResponse(responseCode = "200", description = "CountryProfileSettings"))
    public CountryProfileSettings countryProfile() {
        var country = configs.get(TenantContext.require()).countryProfile();
        return new CountryProfileSettings(country.code(), country.idDocumentTypes(), !country.code().equals("GENERIC"),
                country.defaultPhonePrefix(), country.dateFormat(), country.timeFormat());
    }

}

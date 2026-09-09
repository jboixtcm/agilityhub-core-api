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
import static com.agilityhub.core.platform.api.AuditContracts.*;

/** Tenant audit reads; RGPD and platform operations retain their later-vertical contracts. */
@RestController
public class AuditController {
    private final com.agilityhub.core.platform.application.audit.AuditReadService audits;
    public AuditController(com.agilityhub.core.platform.application.audit.AuditReadService audits) { this.audits = audits; }

    @GetMapping("/api/v1/audit-entries")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"at(between)", "action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin"}, sortable = {"at"},
            columns = {"at*", "action*", "entityLabel*", "actorName*", "impersonatedName*", "changes*", "origin*", "details"}, paged = true, exportable = true)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Audit entries",
            description = "S14 R-14-11. Tenant-scoped audit, newest first; ADMIN only and impersonation is rejected. Member history includes direct and impersonated entries. Sensitive details and changes are masked.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<AuditEntryListItem>; fields selects a sparse projection", content = @Content(schema = @Schema(implementation = AuditPage.class))))
    public ListPage<java.util.Map<String, Object>> auditEntries(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) { return audits.list(params, null); }

    @GetMapping("/api/v1/audit-entries/filter-values")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"at(between)", "action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin"}, sortable = {},
            columns = {}, paged = false, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Audit filter values",
            description = "S14 R-14-11. Tenant-scoped audit, newest first; ADMIN only and impersonation is rejected. Member history includes direct and impersonated entries. Sensitive details and changes are masked.",
            responses = @ApiResponse(responseCode = "200", description = "FilterValues"))
    public FilterValues auditFilterValues(@RequestParam String field, @RequestParam(required = false) String q, @RequestParam(required = false) List<String> filter,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) { return audits.facets(field, params); }

    @GetMapping("/api/v1/audit-entries/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Audit entry",
            description = "S14 R-14-11. Tenant-scoped audit, newest first; ADMIN only and impersonation is rejected. Member history includes direct and impersonated entries. Sensitive details and changes are masked.",
            responses = @ApiResponse(responseCode = "200", description = "AuditEntry", content = @Content(schema = @Schema(implementation = AuditEntry.class))))
    public java.util.Map<String, Object> auditEntry(@PathVariable String id) { return audits.get(id); }

    @GetMapping("/api/v1/members/{id}/audit-entries")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"at(between)", "action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin"}, sortable = {"at"},
            columns = {"at*", "action*", "entityLabel*", "actorName*", "impersonatedName*", "changes*", "origin*", "details"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Member audit entries",
            description = "S14 R-14-11. Tenant-scoped audit, newest first; ADMIN only and impersonation is rejected. Member history includes direct and impersonated entries. Sensitive details and changes are masked.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<AuditEntryListItem>; fields selects a sparse projection", content = @Content(schema = @Schema(implementation = AuditPage.class))))
    public ListPage<java.util.Map<String, Object>> memberAuditEntries(@PathVariable String id,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) { return audits.list(params, id); }

    @GetMapping("/api/v1/members/{id}/consents")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {"type*", "granted*", "version*", "at*", "locale", "source"}, paged = false, exportable = false)
    @Operation(summary = "Member consents",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "List<ConsentHistoryEntry>"))
    public List<ConsentHistoryEntry> memberConsents(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/members/{id}/erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ERASURE_BLOCKED, ERASURE_ALREADY_REQUESTED, INVALID_STATE})
    @Operation(summary = "Request member erasure",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "ErasureRequest"))
    public ErasureRequest requestMemberErasure(@PathVariable String id, @Valid @RequestBody ErasureInput request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/members/{id}/erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({NOT_FOUND, INVALID_STATE})
    @Operation(summary = "Member erasure",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "ErasureRequest"))
    public ErasureRequest memberErasure(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/members/{id}/erasure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({NOT_FOUND, INVALID_STATE})
    @Operation(summary = "Cancel member erasure",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void cancelMemberErasure(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/accounts/{id}/erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ERASURE_BLOCKED, ERASURE_ALREADY_REQUESTED})
    @Operation(summary = "Request account erasure",
            description = "S14 §6. Global account erasure request; AGILITYHUB_ADMIN only, no request tenant.",
            responses = @ApiResponse(responseCode = "201", description = "ErasureRequest"))
    public ErasureRequest requestAccountErasure(@PathVariable String id, @Valid @RequestBody ErasureInput request) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/platform/audit-entries")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"clubId", "at(between)", "action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin"}, sortable = {"at"},
            columns = {"clubId", "at*", "action*", "entityLabel*", "actorName*", "impersonatedName*", "changes*", "origin*"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Platform audit entries",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<AuditEntryListItem>"))
    public ListPage<AuditEntryListItem> platformAuditEntries() { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/platform/erasure-requests")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"clubId"}, sortable = {},
            columns = {"id*", "scope*", "status*", "requestedAt*", "executeAt*", "clubId"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Platform erasure requests",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<ErasureRequest>"))
    public ListPage<ErasureRequest> platformErasureRequests() { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/platform/security-events")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"clubId"}, sortable = {},
            columns = {"at*", "type*", "accountId", "clubId", "route"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Platform security events",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<SecurityEventView>"))
    public ListPage<SecurityEventView> platformSecurityEvents() { throw new UnsupportedOperationException(); }

}

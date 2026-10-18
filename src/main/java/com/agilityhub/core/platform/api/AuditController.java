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

/** Tenant audit reads, the RGPD erasure and consent routes (S14 R-14-14…R-14-18, E11-T01) and the console's views (S17). */
@RestController
public class AuditController {
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.PlatformAuditQueries platformQueries;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.privacy.ErasureService erasures;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.privacy.ErasureQueries erasureQueries;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.privacy.MemberConsents consents;
    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.agilityhub.core.platform.application.audit.AuditReadService audits;
    public AuditController(com.agilityhub.core.platform.application.audit.AuditReadService audits) { this.audits = audits; }

    @GetMapping("/api/v1/audit-entries")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"at(between)", "action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin"}, sortable = {"at"},
            columns = {"at*", "action*", "entityLabel*", "actorName*", "impersonatedName*", "changes*", "origin*", "details"}, paged = true, exportable = true,
            fields = {"id", "clubId", "at", "actorAccountId", "actorName", "actorRole", "impersonatedMemberId", "impersonatedName", "origin", "entityType", "entityId", "entityLabel",
                    "memberId", "action", "changes", "reason", "details"})
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
            columns = {"at*", "action*", "entityLabel*", "actorName*", "impersonatedName*", "changes*", "origin*", "details"}, paged = true, exportable = false,
            fields = {"id", "clubId", "at", "actorAccountId", "actorName", "actorRole", "impersonatedMemberId", "impersonatedName", "origin", "entityType", "entityId", "entityLabel",
                    "memberId", "action", "changes", "reason", "details"})
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
            description = "S14 R-14-18. The member's full consent history (S04's append-only ledger), oldest first; it survives an erasure. Tenant comes from the JWT; another club's member is 404. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "List<ConsentHistoryEntry>"))
    public List<ConsentHistoryEntry> memberConsents(@PathVariable String id) {
        return consents.history(id).stream().map(entry -> new ConsentHistoryEntry(entry.type(), entry.granted(), entry.version(), entry.at(), entry.locale(),
                entry.ipHash(), entry.source())).toList();
    }

    @PostMapping("/api/v1/members/{id}/erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({ERASURE_BLOCKED, ERASURE_ALREADY_REQUESTED, MEMBER_ERASED})
    @Operation(summary = "Request member erasure",
            description = "S14 R-14-14. Checks first (LEFT member, no open invoice or upfront payment, no future booking): any failure is 422 ERASURE_BLOCKED {reasons[]} and nothing is created. SCHEDULED at max(now, leaveDate + rgpd.erasureMinDaysAfterLeave); one live request per member (409 ERASURE_ALREADY_REQUESTED); an erased member is 409 MEMBER_ERASED. Emits AccountErasureRequested (N-52 to the account), audited ERASURE_REQUESTED. Tenant comes from the JWT; another club's member is 404. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "ErasureRequest"))
    public ErasureRequest requestMemberErasure(@PathVariable String id, @Valid @RequestBody ErasureInput request) {
        return clubView(erasures.requestMember(id, request.reason()), id);
    }

    @GetMapping("/api/v1/members/{id}/erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "Member erasure",
            description = "S14 §6. The member's live request (SCHEDULED, BLOCKED with its blockReasons, or ERASING); none is 404. Tenant comes from the JWT; another club's member is 404. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "ErasureRequest"))
    public ErasureRequest memberErasure(@PathVariable String id) { return clubView(erasures.live(id), id); }

    @DeleteMapping("/api/v1/members/{id}/erasure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({NOT_FOUND, INVALID_STATE})
    @Operation(summary = "Cancel member erasure",
            description = "S14 R-14-14. SCHEDULED or BLOCKED → CANCELLED, audited ERASURE_CANCELLED; ERASING (or the console's account request) is 409 INVALID_STATE; no live request is 404. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void cancelMemberErasure(@PathVariable String id) { erasures.cancel(id); }

    @PostMapping("/api/v1/accounts/{id}/erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({NOT_FOUND, ERASURE_BLOCKED, ERASURE_ALREADY_REQUESTED})
    @Operation(summary = "Request account erasure",
            description = "S14 R-14-14. Global account erasure (scope ACCOUNT, every membership); AGILITYHUB_ADMIN only, no request tenant. The checks of every club of the account's members, plus no ACTIVE membership left (OTHER_MEMBERSHIPS_ACTIVE): 422 ERASURE_BLOCKED {reasons[]}. A live request, or an erased account, is 409 ERASURE_ALREADY_REQUESTED; an unknown account 404. An account without members is erased at once (ERASING).",
            responses = @ApiResponse(responseCode = "201", description = "ErasureRequest"))
    public ErasureRequest requestAccountErasure(@PathVariable String id, @Valid @RequestBody ErasureInput request) {
        return view(erasures.requestAccount(id, request.reason()));
    }

    @GetMapping("/api/v1/platform/audit-entries")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"clubId", "at(between)", "action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin"}, sortable = {"at"},
            columns = {"clubId", "at*", "action*", "entityLabel*", "actorName*", "impersonatedName*", "changes*", "origin*"}, paged = true, exportable = false, acceptsFields = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Platform audit entries",
            description = "S17 R-17-12. Global platform view. Only configuration audit entries and aggregate-safe security metadata; member audit is excluded regardless of filters.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<PlatformAuditEntry>: whole rows (no fields parameter)"))
    public ListPage<PlatformAuditEntry> platformAuditEntries(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        var result=platformQueries.audit(params);return new ListPage<>(result.items().stream().map(row->mapper.convertValue(row,PlatformAuditEntry.class)).toList(),result.page(),result.size(),result.totalItems(),result.totalPages(),result.appliedFilters());
    }

    @GetMapping("/api/v1/platform/erasure-requests")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"clubId"}, sortable = {},
            columns = {"id*", "scope*", "status*", "requestedAt*", "executeAt*", "clubId"}, paged = true, exportable = false, acceptsFields = false, searchable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Platform erasure requests",
            description = "S14 §6 / S17. Every erasure request, newest first; clubId filters the clubs a request covers, clubId:exists:false the platform's account requests. Ids, states and dates only, so no free-text search (a non-blank q is 400 INVALID_FILTER).",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<ErasureRequest>"))
    public ListPage<ErasureRequest> platformErasureRequests(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        var page = erasureQueries.platform(params);
        return new ListPage<>(page.items().stream().map(this::view).toList(), page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
    }

    @DeleteMapping("/api/v1/platform/erasure-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({NOT_FOUND, INVALID_STATE})
    @Operation(summary = "Cancel account erasure",
            description = "S14 §5 (E11-T01 round 2). The console cancels an account request (scope ACCOUNT) that is SCHEDULED or BLOCKED → CANCELLED, audited ERASURE_CANCELLED on the platform; AGILITYHUB_ADMIN only, no request tenant. The clubs it already erased (a request blocked halfway) stay erased. Another state, or a club's member request (its club cancels it), is 409 INVALID_STATE {status, scope}; an unknown id is 404.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void cancelAccountErasure(@PathVariable String id) { erasures.cancelAccountRequest(id); }

    private ErasureRequest view(com.agilityhub.core.platform.persistence.privacy.ErasureRequest request) {
        return new ErasureRequest(request.id(), request.clubId(), ErasureScope.valueOf(request.scope()), request.accountId(), request.memberIds(), request.clubIds(),
                ErasureSource.valueOf(request.source()), request.requestedByAccountId(), request.requestedAt(), request.reason(),
                ErasureStatus.valueOf(request.status()), request.executeAt(), request.blockReasons(), request.erasedAt(), request.cancelledAt(),
                request.cancelledByAccountId(), request.version());
    }
    /** A club reads only its own part of a request: an account's request lists the club's member and the club, nothing of other clubs. */
    private ErasureRequest clubView(com.agilityhub.core.platform.persistence.privacy.ErasureRequest request, String memberId) {
        var full = view(request);
        if (!"ACCOUNT".equals(request.scope())) { return full; }
        String club = com.agilityhub.core.shared.application.TenantContext.require();
        return new ErasureRequest(full.id(), club, full.scope(), full.accountId(), List.of(memberId), List.of(club), full.source(), null, full.requestedAt(),
                null, full.status(), full.executeAt(), full.blockReasons(), full.erasedAt(), full.cancelledAt(), null, full.version());
    }

    @GetMapping("/api/v1/platform/security-events")
    @PreAuthorize("hasRole('AGILITYHUB_ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"clubId"}, sortable = {},
            columns = {"at*", "type*", "accountId", "clubId", "route"}, paged = true, exportable = false, acceptsFields = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Platform security events",
            description = "S17 R-17-12. Global platform security events. Only type, account ID, club ID, route and timestamp; no IP, credentials or request body.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<SecurityEventView>"))
    public ListPage<SecurityEventView> platformSecurityEvents(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam org.springframework.util.MultiValueMap<String,String> params) {
        var result=platformQueries.security(params);return new ListPage<>(result.items().stream().map(row->mapper.convertValue(row,SecurityEventView.class)).toList(),result.page(),result.size(),result.totalItems(),result.totalPages(),result.appliedFilters());
    }

}

package com.agilityhub.core.clubs.common.api;

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
import static com.agilityhub.core.clubs.common.api.CommonContracts.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class ExportsController {
    @GetMapping("/api/v1/exports")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"kind"}, sortable = {},
            columns = {"id*", "kind*", "listKey*", "format*", "status*", "rows", "fileName", "createdAt*", "expiresAt"}, paged = false, exportable = false)
    @Operation(summary = "Exports",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "List<ExportJob>"))
    public List<ExportJob> exports(@RequestParam(required = false) ExportKind kind) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/exports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MEMBER') and (hasRole('MEMBER') or principal.claims['imp'] != true)")
    @ContractErrors({EXPORT_EXPIRED})
    @Operation(summary = "Export job",
            description = "S14 §6. ADMIN or MEMBER: caller-owned jobs only. Catalog canonical EXPORT_EXPIRED is 422; narrative 410 requires catalog alignment.",
            responses = @ApiResponse(responseCode = "200", description = "ExportJob"))
    public ExportJob exportJob(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/members/{id}/data-export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({DATA_EXPORT_TOO_SOON})
    @Operation(summary = "Export member data",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "202", description = "ExportAccepted"))
    public ExportAccepted exportMemberData(@PathVariable String id, @Valid @RequestBody DataExportInput request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/me/data-export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('MEMBER')")
    @ContractErrors({IMPERSONATION_DENIED, DATA_EXPORT_TOO_SOON})
    @Operation(summary = "Export my data",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "202", description = "ExportAccepted"))
    public ExportAccepted exportMyData(@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
            throw new com.agilityhub.core.shared.domain.ApiException(IMPERSONATION_DENIED);
        }
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/members/export")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"memberNumber", "lastName", "fullName(contains)", "status", "displayStatus", "planId", "priceId", "paymentMethodType", "nextInvoiceDate", "joinedAt", "leaveDate", "bookingBlocked", "familyGroupId", "imageRightsGranted", "roles", "city", "postalCode", "dogLevelId", "dogName(contains)", "hasPendingDocuments", "freeTrainingAllowed", "gender", "birthDate"}, sortable = {"lastName", "firstName", "memberNumber", "joinedAt", "leaveDate", "nextInvoiceDate", "city"},
            columns = {"fullName*", "dogs*", "plan*", "displayStatus*", "memberNumber", "contact", "paymentMethod@BILLING", "nextInvoiceDate@BILLING", "familyGroup@FAMILY_GROUP", "joinedAt", "leaveDate", "bookingBlocked", "imageRights", "roles", "city", "postalCode", "pendingDocuments", "freeTraining@FREE_TRAINING", "birthDate", "gender", "idDocument"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export members",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked; implementation and future-vertical field allowlists are deferred.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportMembers(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/dogs/export")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"name(contains)", "breed(contains)", "levelId", "memberId", "ownerName(contains)", "status", "freeTrainingAllowed", "hasLicense", "licenseOrganisation", "hasPendingDocuments", "sex", "birthDate", "chip", "registeredAt", "levelAssignedAt"}, sortable = {"name", "breed", "levelOrder", "ownerLastName", "registeredAt", "levelAssignedAt"},
            columns = {"name*", "breed*", "level*#levels.enabled", "owner*", "freeTraining*@FREE_TRAINING", "licenses*", "displayStatus*", "sex", "age", "chip", "pendingDocuments", "levelAssignedAt", "pack@PACKS", "registeredAt"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export dogs",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked; implementation and future-vertical field allowlists are deferred.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportDogs(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/audit-entries/export")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"at(between)", "action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin"}, sortable = {"at"},
            columns = {"at*", "action*", "entityLabel*", "actorName*", "impersonatedName*", "changes*", "origin*"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export audit entries",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked; implementation and future-vertical field allowlists are deferred.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportAuditEntries(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/invoices/export")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.BILLING)
    @ListContract(filterable = {}, sortable = {},
            columns = {}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export invoices",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked; implementation and future-vertical field allowlists are deferred.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportInvoices(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/activity-registrations/export")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.ACTIVITIES)
    @ListContract(filterable = {}, sortable = {},
            columns = {}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export activity registrations",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked; implementation and future-vertical field allowlists are deferred.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportActivityRegistrations(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/notifications/export")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {}, sortable = {},
            columns = {}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export notifications",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked; implementation and future-vertical field allowlists are deferred.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportNotifications(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns) { throw new UnsupportedOperationException(); }

}

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

/** Member/dog list exports and their lifecycle; later verticals retain their reserved contracts. */
@RestController
public class ExportsController {
    private final com.agilityhub.core.clubs.common.application.ListExportService lists;
    private final com.agilityhub.core.clubs.common.application.ExportQueries jobs;
    public ExportsController(com.agilityhub.core.clubs.common.application.ListExportService lists, com.agilityhub.core.clubs.common.application.ExportQueries jobs) { this.lists = lists; this.jobs = jobs; }
    private ExportJob view(com.agilityhub.core.clubs.common.application.ExportQueries.View job) {
        return new ExportJob(job.id(), ExportKind.valueOf(job.kind()), job.listKey(), ExportFormat.valueOf(job.format()), ExportStatus.valueOf(job.status()),
                job.rows(), job.progressPct(), job.fileName(), job.downloadUrl(), job.createdAt(), job.expiresAt(),
                job.errorCode() == null ? null : new ExportError(job.errorCode(), job.errorMessage()));
    }
    private org.springframework.http.ResponseEntity<?> export(String key, String format, String columns, org.springframework.util.MultiValueMap<String, String> params) {
        var result = lists.export(key, format, columns, params);
        if (result.file() == null) {
            String url = "/api/v1/exports/" + result.jobId();
            return org.springframework.http.ResponseEntity.accepted().location(java.net.URI.create(url)).body(new ExportAccepted(result.jobId(), url));
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + result.fileName() + "\"")
                .header("Cache-Control", "no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(format.equals("pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(result.file());
    }
    @GetMapping("/api/v1/exports")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"kind"}, sortable = {},
            columns = {"id*", "kind*", "listKey*", "format*", "status*", "rows", "fileName", "createdAt*", "expiresAt"}, paged = false, exportable = false)
    @Operation(summary = "Exports",
            description = "Up to 100 recent caller-owned jobs, newest first. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "List<ExportJob>"))
    public List<ExportJob> exports(@RequestParam(required = false) ExportKind kind) { return jobs.list(kind == null ? null : kind.name()).stream().map(this::view).toList(); }

    @GetMapping("/api/v1/exports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MEMBER') and principal.claims['imp'] != true")
    @ContractErrors({EXPORT_EXPIRED})
    @Operation(summary = "Export job",
            description = "S14 §6. ADMIN or MEMBER: caller-owned jobs only. Catalog canonical EXPORT_EXPIRED is 422; narrative 410 requires catalog alignment.",
            responses = @ApiResponse(responseCode = "200", description = "ExportJob"))
    public ExportJob exportJob(@PathVariable String id) { return view(jobs.get(id)); }

    @GetMapping("/api/v1/exports/{id}/download")
    @PreAuthorize("hasAnyRole('ADMIN','MEMBER') and principal.claims['imp'] != true")
    @ContractErrors({EXPORT_EXPIRED, INVALID_STATE})
    @Operation(summary = "Download a local export", description = "Local/test storage: caller-owned job, bearer authentication and signed URL required. Expires with the file after seven days. S3 deployments return a direct signed URL instead.",
            responses = @ApiResponse(responseCode = "200", description = "Export file", content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary"))))
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> download(@PathVariable String id, @RequestParam long expires, @RequestParam String signature) {
        var file = jobs.download(id, expires, signature);
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(file.contentType())).contentLength(file.size())
                .body(new org.springframework.core.io.InputStreamResource(file.stream()));
    }

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

    @RequestMapping(path = "/api/v1/members/export", method = {RequestMethod.GET, RequestMethod.POST})
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"id", "memberNumber", "lastName", "fullName(contains)", "status", "displayStatus", "planId", "priceId", "paymentMethodType", "nextInvoiceDate", "joinedAt", "leaveDate", "bookingBlocked", "familyGroupId", "imageRightsGranted", "roles", "city", "postalCode", "dogLevelId", "dogName(contains)", "hasPendingDocuments", "freeTrainingAllowed", "gender", "birthDate"}, sortable = {"lastName", "firstName", "memberNumber", "joinedAt", "leaveDate", "nextInvoiceDate", "city"},
            columns = {"fullName*", "dogs*", "plan*", "displayStatus*", "memberNumber", "contact", "paymentMethod@BILLING", "nextInvoiceDate@BILLING", "familyGroup@FAMILY_GROUP", "joinedAt", "leaveDate", "bookingBlocked", "imageRights", "roles", "city", "postalCode", "pendingDocuments", "freeTraining@FREE_TRAINING", "birthDate", "gender", "idDocument"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export members",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked. Up to 5,000 rows inline; larger requests queue a background export, capped at 100,000 rows. Catalog EXPORT_LIMIT is HTTP 422. Files and signed links last seven days.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<?> exportMembers(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) { return export("members", format, columns, params); }

    @RequestMapping(path = "/api/v1/dogs/export", method = {RequestMethod.GET, RequestMethod.POST})
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ListContract(filterable = {"id", "name(contains)", "breed(contains)", "levelId", "memberId", "ownerName(contains)", "handlerName(contains)", "status", "freeTrainingAllowed", "hasLicense", "licenseOrganisation", "hasPendingDocuments", "sex", "birthDate", "chip", "registeredAt", "levelAssignedAt"}, sortable = {"name", "breed", "levelOrder", "ownerLastName", "registeredAt", "levelAssignedAt"},
            columns = {"name*", "breed*", "level*#levels.enabled", "owner*", "handler", "freeTraining*@FREE_TRAINING", "licenses*", "displayStatus*", "sex", "age", "chip", "pendingDocuments", "levelAssignedAt", "pack@PACKS", "registeredAt"}, paged = true, exportable = false)
    @ContractErrors({INVALID_FILTER, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "Export dogs",
            description = "S14 §6, R-14-12. Same q/filter/sort and selected columns as the list. 200 binary file or 202 ExportAccepted. Sensitive values are masked. Up to 5,000 rows inline; larger requests queue a background export, capped at 100,000 rows. Catalog EXPORT_LIMIT is HTTP 422. Files and signed links last seven days.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<?> exportDogs(@RequestParam @Schema(allowableValues = {"xlsx", "pdf"}) String format, @RequestParam(required = false) String columns,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) { return export("dogs", format, columns, params); }

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

package com.agilityhub.core.clubs.activities.api;

import com.agilityhub.core.clubs.activities.application.ActivityContractAccess;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.shared.application.contract.*;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.application.Module;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import static com.agilityhub.core.clubs.activities.api.ActivityContracts.*;
import static com.agilityhub.core.clubs.activities.api.ActivityRequests.*;

/** Reserved S07 operations. Guards execute before the standard NOT_IMPLEMENTED response. */
@RestController
public class PublicActivitiesController {
    private final ActivityContractAccess access;
    public PublicActivitiesController(ActivityContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/public/{clubSlug}/activities")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_API_KEY, MODULE_DISABLED, CLUB_NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "publicActivities", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Published content only. No names, member data or registrations. X-Api-Key required. Public cache 300 seconds with ETag.", responses = @ApiResponse(responseCode = "200", description = "PublicActivities", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Cache-Control", schema = @Schema(type = "string", example = "public, max-age=300")), @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", schema = @Schema(type = "string"))}, useReturnTypeSchema = true))
    public PublicActivities publicActivities(@PathVariable String clubSlug, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestHeader(value = "X-Api-Key", required = false) String apiKey, @RequestParam(defaultValue = "upcoming") @Schema(allowableValues = {"upcoming", "past"}) String scope) {
        access.publicRead(clubSlug, apiKey, true, null, null);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/public/{clubSlug}/activities/{slug}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_API_KEY, MODULE_DISABLED, CLUB_NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "publicActivity", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Public states: PUBLISHED, FINISHED and CANCELLED; DRAFT hidden. No names, member data or registrations. X-Api-Key required. Public cache 300 seconds with ETag.", responses = @ApiResponse(responseCode = "200", description = "PublicActivity", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Cache-Control", schema = @Schema(type = "string", example = "public, max-age=300")), @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", schema = @Schema(type = "string"))}, useReturnTypeSchema = true))
    public PublicActivity publicActivity(@PathVariable String clubSlug, @PathVariable String slug, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        access.publicRead(clubSlug, apiKey, true, slug, null);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/public/{clubSlug}/activities/{slug}/files/{fileId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ResponseStatus(HttpStatus.FOUND)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, MODULE_DISABLED, CLUB_NOT_FOUND})
    @Operation(summary = "publicFile", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Contract only; returns 501 NOT_IMPLEMENTED after tenant, role and module guards. Public states: PUBLISHED, FINISHED and CANCELLED; DRAFT hidden. No names, member data or registrations. No API key. Redirect to a short-lived signed URL.", responses = @ApiResponse(responseCode = "302", description = "void", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Location", schema = @Schema(type = "string"))}, content = @Content))
    public void publicFile(@PathVariable String clubSlug, @PathVariable String slug, @PathVariable String fileId, @RequestHeader(value = "Accept-Language", required = false) String language) {
        access.publicRead(clubSlug, null, false, slug, fileId);
        throw new UnsupportedOperationException();
    }
}

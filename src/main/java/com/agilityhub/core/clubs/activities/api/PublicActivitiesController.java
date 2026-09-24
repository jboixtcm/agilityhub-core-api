package com.agilityhub.core.clubs.activities.api;

import com.agilityhub.core.clubs.activities.application.PublicActivityService;
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

/** Slug-scoped public activity allowlist and signed file redirects. */
@RestController
public class PublicActivitiesController {
    private final PublicActivityService service;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.agilityhub.core.clubs.followup.application.AttachmentService attachments;
    public PublicActivitiesController(PublicActivityService service,com.fasterxml.jackson.databind.ObjectMapper mapper,com.agilityhub.core.clubs.followup.application.AttachmentService attachments) {
        this.service=service; this.mapper=mapper; this.attachments=attachments;
    }
    private void headers(PublicActivityService.Result result,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","public, max-age=300"); response.setHeader("Content-Language",result.language());
        response.setHeader("Vary","Accept-Language, X-Api-Key");
        try { response.setHeader("ETag","\""+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(result.value())))+"\""); }
        catch(java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }

    @GetMapping("/api/v1/public/{clubSlug}/activities")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_API_KEY, MODULE_DISABLED, CLUB_NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "publicActivities", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Published content only. No names, member data or registrations. X-Api-Key required and checked before the club and the module (403 INVALID_API_KEY first). Public cache 300 seconds with ETag.", responses = @ApiResponse(responseCode = "200", description = "PublicActivities", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Cache-Control", schema = @Schema(type = "string", example = "public, max-age=300")), @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", schema = @Schema(type = "string"))}, useReturnTypeSchema = true))
    public PublicActivities publicActivities(@PathVariable String clubSlug, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestHeader(value = "X-Api-Key", required = false) String apiKey, @RequestParam(defaultValue = "upcoming") @Schema(allowableValues = {"upcoming", "past"}) String scope, jakarta.servlet.http.HttpServletResponse response) {
        var result=service.read(clubSlug,apiKey,language,scope,null); headers(result,response);
        return mapper.convertValue(result.value(),PublicActivities.class);
    }

    @GetMapping("/api/v1/public/{clubSlug}/activities/{slug}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_API_KEY, MODULE_DISABLED, CLUB_NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "publicActivity", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Public states: PUBLISHED, FINISHED and CANCELLED; DRAFT hidden. No names, member data or registrations. X-Api-Key required and checked before the club and the module (403 INVALID_API_KEY first). Public cache 300 seconds with ETag.", responses = @ApiResponse(responseCode = "200", description = "PublicActivity", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Cache-Control", schema = @Schema(type = "string", example = "public, max-age=300")), @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", schema = @Schema(type = "string"))}, useReturnTypeSchema = true))
    public PublicActivity publicActivity(@PathVariable String clubSlug, @PathVariable String slug, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestHeader(value = "X-Api-Key", required = false) String apiKey, jakarta.servlet.http.HttpServletResponse response) {
        var result=service.read(clubSlug,apiKey,language,null,slug); headers(result,response);
        return mapper.convertValue(result.value(),PublicActivity.class);
    }

    @GetMapping("/api/v1/public/{clubSlug}/activities/{slug}/files/{fileId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ResponseStatus(HttpStatus.FOUND)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, MODULE_DISABLED, CLUB_NOT_FOUND})
    @Operation(summary = "publicFile", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Public states: PUBLISHED, FINISHED and CANCELLED; DRAFT hidden. No names, member data or registrations. No API key. Redirect to a short-lived signed URL.", responses = @ApiResponse(responseCode = "302", description = "void", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Location", schema = @Schema(type = "string"))}, content = @Content))
    public org.springframework.http.ResponseEntity<?> publicFile(@PathVariable String clubSlug, @PathVariable String slug, @PathVariable String fileId, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestParam(required=false) Long expires, @RequestParam(required=false) String signature) throws java.io.IOException {
        var file=service.file(clubSlug,slug,fileId);
        if(expires==null && signature==null) return org.springframework.http.ResponseEntity.status(302).location(java.net.URI.create(file.signedUrl())).build();
        if(expires==null || signature==null) throw new com.agilityhub.core.shared.domain.ApiException(FORBIDDEN);
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(file.clubId())) {
            return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(file.mimeType()))
                    .header("Cache-Control","no-store").header("Content-Disposition",org.springframework.http.ContentDisposition.inline().filename(file.name()).build().toString())
                    .body(new org.springframework.core.io.InputStreamResource(attachments.openLocal(file.fileKey(),expires,signature)));
        }
    }
}

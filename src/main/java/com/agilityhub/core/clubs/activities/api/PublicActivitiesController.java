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
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_API_KEY, MODULE_DISABLED, CLUB_SUSPENDED, RATE_LIMITED})
    @Operation(summary = "publicActivities", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Published content only. No names, member data or registrations. X-Api-Key required and checked before the club and the module (403 INVALID_API_KEY first). Public cache 300 seconds with ETag.", responses = @ApiResponse(responseCode = "200", description = "PublicActivities", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Cache-Control", schema = @Schema(type = "string", example = "public, max-age=300")), @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", schema = @Schema(type = "string"))}, useReturnTypeSchema = true))
    public PublicActivities publicActivities(@PathVariable String clubSlug, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestHeader(value = "X-Api-Key", required = false) String apiKey, @RequestParam(defaultValue = "upcoming") @Schema(allowableValues = {"upcoming", "past"}) String scope, jakarta.servlet.http.HttpServletResponse response) {
        var result=service.read(clubSlug,apiKey,language,scope,null); headers(result,response);
        return mapper.convertValue(result.value(),PublicActivities.class);
    }

    @GetMapping("/api/v1/public/{clubSlug}/activities/{slug}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, INVALID_API_KEY, MODULE_DISABLED, CLUB_SUSPENDED, RATE_LIMITED})
    @Operation(summary = "publicActivity", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Public states: PUBLISHED, FINISHED and CANCELLED; DRAFT hidden. No names, member data or registrations. X-Api-Key required and checked before the club and the module (403 INVALID_API_KEY first). Public cache 300 seconds with ETag.", responses = @ApiResponse(responseCode = "200", description = "PublicActivity", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Cache-Control", schema = @Schema(type = "string", example = "public, max-age=300")), @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", schema = @Schema(type = "string"))}, useReturnTypeSchema = true))
    public PublicActivity publicActivity(@PathVariable String clubSlug, @PathVariable String slug, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestHeader(value = "X-Api-Key", required = false) String apiKey, jakarta.servlet.http.HttpServletResponse response) {
        var result=service.read(clubSlug,apiKey,language,null,slug); headers(result,response);
        return mapper.convertValue(result.value(),PublicActivity.class);
    }

    @GetMapping("/api/v1/public/{clubSlug}/activities/{slug}/files/{fileId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ResponseStatus(HttpStatus.FOUND)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, IMPERSONATION_DENIED, MODULE_DISABLED, CLUB_NOT_FOUND})
    @Operation(summary = "publicFile", description = "Roles: ANON, MEMBER, INSTRUCTOR, ADMIN, AGILITYHUB_ADMIN. Public states: PUBLISHED, FINISHED and CANCELLED; DRAFT hidden. No names, member data or registrations. No API key. Redirect to a short-lived signed URL. The signed URL of the local storage (`expires` and `signature`) answers the file itself, with byte ranges (INC-43, CONVENCIONS_API §5).", responses = {
            @ApiResponse(responseCode = "302", description = "void", headers = {@io.swagger.v3.oas.annotations.headers.Header(name = "Location", schema = @Schema(type = "string"))}, content = @Content),
            @ApiResponse(responseCode = "200", description = "The whole file (signed URL): no Range, several ranges, a malformed one or an If-Range", headers = {
                    @io.swagger.v3.oas.annotations.headers.Header(name = "Accept-Ranges", schema = @Schema(type = "string", allowableValues = "bytes"))},
                    content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "206", description = "The one byte range asked for (signed URL, Range: bytes=a-b, a- or -n)", headers = {
                    @io.swagger.v3.oas.annotations.headers.Header(name = "Accept-Ranges", schema = @Schema(type = "string", allowableValues = "bytes")),
                    @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Range", description = "bytes start-end/size", schema = @Schema(type = "string"))},
                    content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "416", description = "The range starts past the file (signed URL); no body", headers = {
                    @io.swagger.v3.oas.annotations.headers.Header(name = "Content-Range", description = "bytes */size", schema = @Schema(type = "string"))},
                    content = @Content)})
    public org.springframework.http.ResponseEntity<?> publicFile(@PathVariable String clubSlug, @PathVariable String slug, @PathVariable String fileId, @RequestHeader(value = "Accept-Language", required = false) String language, @RequestParam(required=false) Long expires, @RequestParam(required=false) String signature, jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        var file=service.file(clubSlug,slug,fileId);
        if(expires==null && signature==null) return org.springframework.http.ResponseEntity.status(302).location(java.net.URI.create(file.signedUrl())).build();
        if(expires==null || signature==null) throw new com.agilityhub.core.shared.domain.ApiException(FORBIDDEN);
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(file.clubId())) {
            // E5-T27 step 8 (ruling E61): an SVG is never shown on the api's origin; it downloads as a sandboxed attachment.
            var type=com.agilityhub.core.shared.application.FileDownloads.type(file.mimeType());boolean svg=com.agilityhub.core.shared.application.FileDownloads.svg(type);
            var headers=new org.springframework.http.HttpHeaders();headers.setContentType(type);headers.set("Cache-Control","no-store");
            headers.set("Content-Disposition",(svg?org.springframework.http.ContentDisposition.attachment():org.springframework.http.ContentDisposition.inline()).filename(file.name()).build().toString());
            // E5-T27 round 2 (ruling E71, CONVENCIONS_API §5): every public file is sandboxed except a PDF shown inline.
            if(!com.agilityhub.core.shared.application.FileDownloads.pdf(type)) headers.set("Content-Security-Policy",com.agilityhub.core.shared.application.ContentSecurityPolicies.DOWNLOAD);
            // INC-43 (E11-T02): a video plays in Safari from the local storage, as from S3: byte ranges.
            var local=attachments.openLocal(file.fileKey(),expires,signature);
            return com.agilityhub.core.shared.application.ByteRanges.answer(headers,local.sizeBytes(),local.content(),com.agilityhub.core.shared.application.ByteRanges.of(request));
        }
    }
}

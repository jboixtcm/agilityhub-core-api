package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class PlatformThemeFilesController {
    private final PlatformThemeFiles files;private final PlatformConsoleQueries clubs;
    public PlatformThemeFilesController(PlatformThemeFiles files,PlatformConsoleQueries clubs) {this.files=files;this.clubs=clubs;}
    public record PlatformLogoUploadRequest(@NotBlank String fileName,@NotBlank String mimeType,@Positive long sizeBytes) { }
    @PostMapping("/api/v1/platform/clubs/{id}/theme/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PlatformJobsController.PLATFORM)
    @ContractErrors({ErrorCode.VALIDATION_ERROR,ErrorCode.FORBIDDEN,ErrorCode.CLUB_NOT_FOUND,ErrorCode.FILE_TOO_LARGE,ErrorCode.FILE_TYPE_NOT_ALLOWED,ErrorCode.IMPERSONATION_DENIED})
    @Operation(summary="uploadPlatformLogo",description="S17 §2/§6, R-17-02. Roles: AGILITYHUB_ADMIN, without a tenant or club membership. The console's only logo upload (light logo, dark logo or mark): an image upload bound to the target club and caller, with the attachment route's signed-upload response and five-minute expiry. mimeType must be an image allowed by the target club's files.allowedTypes and sizeBytes must not exceed files.maxSizeMb (FILE_TOO_LARGE includes maxSizeMb). Upload with the returned headers, then publish the fileKey with the versioned theme update (logoFileKey, logoDarkFileKey or markFileKey).",
            responses=@ApiResponse(responseCode="201",description="Signed upload URL and opaque file key",useReturnTypeSchema=true))
    public ConsoleThemeFileAccess.Upload uploadPlatformLogo(@PathVariable String id,@Valid @RequestBody PlatformLogoUploadRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        clubs.get(id);try(var scope=TenantContext.open(id)) {return files.upload(request.fileName(),request.mimeType(),request.sizeBytes());}
    }
    @GetMapping("/api/v1/public/{clubSlug}/theme/{fileKey}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ContractErrors({ErrorCode.NOT_FOUND,ErrorCode.VALIDATION_ERROR})
    @Operation(summary="publicClubLogo",description="Public branding image, served directly with an ETag and a five-minute public cache lifetime. Only a logo currently published by this club is accessible at the origin; unrelated uploads always return 404. Replacing a logo changes its URL.",
            responses={@ApiResponse(responseCode="200",description="Published image",content=@io.swagger.v3.oas.annotations.media.Content(mediaType="image/*",schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",format="binary"))),
                    @ApiResponse(responseCode="304",description="Not modified",content=@io.swagger.v3.oas.annotations.media.Content)})
    public ResponseEntity<byte[]> publicClubLogo(@PathVariable String clubSlug,@PathVariable String fileKey,
            org.springframework.web.context.request.WebRequest request) {
        var file = files.read(clubSlug,fileKey);
        var type = FileDownloads.type(file.mimeType());
        var disposition = FileDownloads.shownInline(type) ? ContentDisposition.inline() : ContentDisposition.attachment();
        String tag = "\"" + fileKey + "\"";
        boolean notModified = request.checkNotModified(tag);
        var response = ResponseEntity.status(notModified ? 304 : 200)
                .eTag(tag).cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic().mustRevalidate())
                .header("Content-Security-Policy", ContentSecurityPolicies.DOWNLOAD);
        return notModified ? response.build() : response.contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(file.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentLength(file.content().length).body(file.content());
    }
}

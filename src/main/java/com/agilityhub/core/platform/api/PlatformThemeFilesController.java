package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
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
    @ContractErrors({ErrorCode.CLUB_NOT_FOUND,ErrorCode.FILE_TOO_LARGE,ErrorCode.FILE_TYPE_NOT_ALLOWED,ErrorCode.IMPERSONATION_DENIED})
    @Operation(summary="uploadPlatformLogo",description="S17 R-17-02. AGILITYHUB_ADMIN only. An image upload bound to the target club and caller. Publish its fileKey with the versioned theme update.",
            responses=@ApiResponse(responseCode="201",description="Signed upload URL and opaque file key",useReturnTypeSchema=true))
    public ConsoleThemeFileAccess.Upload uploadPlatformLogo(@PathVariable String id,@Valid @RequestBody PlatformLogoUploadRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        clubs.get(id);try(var scope=TenantContext.open(id)) {return files.upload(request.fileName(),request.mimeType(),request.sizeBytes());}
    }
    @GetMapping("/api/v1/public/{clubSlug}/theme/{fileKey}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ContractErrors({ErrorCode.NOT_FOUND,ErrorCode.VALIDATION_ERROR})
    @Operation(summary="publicClubLogo",description="Public branding image. Only a logo currently published by this club is accessible; unrelated uploads always return 404.",
            responses=@ApiResponse(responseCode="302",description="Short-lived download URL"))
    public ResponseEntity<Void> publicClubLogo(@PathVariable String clubSlug,@PathVariable String fileKey) {
        return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore()).location(URI.create(files.download(clubSlug,fileKey))).build();
    }
}

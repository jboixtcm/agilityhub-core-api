package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class AttachmentsController {
    private final AttachmentService attachments; private final IdentityTransactions transactions;
    public AttachmentsController(AttachmentService attachments, IdentityTransactions transactions) { this.attachments = attachments; this.transactions = transactions; }
    public record UploadRequest(@NotBlank @Schema(allowableValues = {"DOG_DOCUMENT", "DOG_PHOTO", "INSTRUCTOR_NOTE"}) String purpose,
            @NotBlank String fileName, @NotBlank String mimeType, @Positive long sizeBytes) { }
    public record AttachmentRequest(@NotBlank @Schema(allowableValues = "INSTRUCTOR_NOTE") String entityType,
            @NotBlank String entityId, @NotBlank String fileKey, @NotBlank @Size(max = 80) String name) { }
    public record AttachmentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mimeType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) java.time.Instant uploadedAt) { }
    @PostMapping("/api/v1/attachments/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MEMBER')")
    @ContractErrors({FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, ATTACHMENT_ENTITY_MISMATCH})
    @Operation(summary = "Create an attachment upload URL", description = "S03/S10 minimal upload contract. Upload using the returned headers; URLs last five minutes. Ownership is checked when attaching the uploaded file.",
            responses = @ApiResponse(responseCode = "201", description = "Upload URL, opaque file key, required headers and expiry"))
    public AttachmentService.Upload upload(@Valid @RequestBody UploadRequest request) {
        return attachments.upload(request.purpose(), request.fileName(), request.mimeType(), request.sizeBytes());
    }
    @PostMapping("/api/v1/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER') and (principal.claims['imp'] == true or !hasAnyRole('ADMIN','INSTRUCTOR'))")
    @ContractErrors({MEMBER_ERASED, ATTACHMENT_LIMIT_REACHED, ATTACHMENT_ENTITY_MISMATCH, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED})
    @Operation(summary = "Attach a file to the owner's instructor note", description = "Minimal S10 INSTRUCTOR_NOTE attachment. Task and observation attachments remain owned by S10.",
            responses = @ApiResponse(responseCode = "201", description = "Attachment with signed download URL", content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = AttachmentResponse.class))))
    public Map<String,Object> add(@Valid @RequestBody AttachmentRequest request) {
        return transactions.run(() -> attachments.addNote(request.entityType(), request.entityId(), request.fileKey(), request.name()));
    }
    @io.swagger.v3.oas.annotations.Hidden
    @PutMapping("/api/v1/attachments/uploads/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MEMBER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putLocal(@PathVariable String id, @RequestParam long expires, @RequestParam String signature, jakarta.servlet.http.HttpServletRequest request) throws IOException {
        attachments.putLocal(id, expires, signature, request.getContentType(), request.getInputStream());
    }
    @io.swagger.v3.oas.annotations.Hidden
    @GetMapping("/api/v1/attachments/files/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    public ResponseEntity<InputStreamResource> getLocal(@PathVariable String id, @RequestParam long expires, @RequestParam String signature) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition", "attachment")
                .header("Cache-Control", "no-store").body(new InputStreamResource(attachments.openLocal(id, expires, signature)));
    }
}

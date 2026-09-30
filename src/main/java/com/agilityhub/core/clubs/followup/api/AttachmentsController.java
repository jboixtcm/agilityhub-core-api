package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.clubs.followup.application.FollowupContractAccess;
import com.agilityhub.core.clubs.followup.application.FollowupTransactions;
import com.agilityhub.core.clubs.followup.domain.AttachmentEntityType;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * Signed-URL attachments (CONVENCIONS_API §5): the S03/S07 purposes, the member's INSTRUCTOR_NOTE (E2-T06/E3-T03) and,
 * since E6-T03, the S10 purposes TASK and DOG_OBSERVATIONS (INSTRUCTOR/ADMIN, TASKS): registration, the entity list and
 * the removal (R-10-11), all on the same {@link AttachmentService}. The impersonation token acts as the member: accepted
 * where the member may act, IMPERSONATION_DENIED on the staff entities. An account with MEMBER and a staff role acts on its
 * own dog's note as the member (S01 R-01-07, ruling E41). The upload URL, the registration and the removal run in
 * {@link FollowupTransactions#keyed} (E6-T03 round 5), retried whole on a write conflict.
 */
@RestController
public class AttachmentsController {
    static final Set<String> STAFF_PURPOSES = Set.of("TASK", "DOG_OBSERVATIONS");
    private final AttachmentService attachments; private final FollowupTransactions transactions; private final FollowupContractAccess access;
    private final ObjectMapper mapper;
    public AttachmentsController(AttachmentService attachments, FollowupTransactions transactions, FollowupContractAccess access, ObjectMapper mapper) {
        this.attachments = attachments; this.transactions = transactions; this.access = access; this.mapper = mapper;
    }
    public record UploadRequest(@NotBlank @Schema(allowableValues = {"DOG_DOCUMENT", "DOG_PHOTO", "INSTRUCTOR_NOTE", "ACTIVITY_IMAGE", "ACTIVITY_DOCUMENT",
            "TASK", "DOG_OBSERVATIONS"}) String purpose,
            @NotBlank String fileName, @NotBlank String mimeType, @Positive long sizeBytes) { }
    public record AttachmentRequest(@NotBlank @Schema(allowableValues = {"INSTRUCTOR_NOTE", "TASK", "DOG_OBSERVATIONS"}) String entityType,
            @NotBlank String entityId, @NotBlank String fileKey, @NotBlank @Size(max = 80) String name) { }
    @Schema(name = "Attachment", description = "S10 §6 attachment with a short-lived signed download URL")
    public record AttachmentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mimeType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) java.time.Instant uploadedAt) { }
    private static boolean anyRole(String... roles) {
        var granted = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        return granted.stream().anyMatch(authority -> Set.of(roles).stream().anyMatch(role -> authority.getAuthority().equals("ROLE_" + role)));
    }

    @PostMapping("/api/v1/attachments/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ContractErrors({FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, ATTACHMENT_ENTITY_MISMATCH, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "Create an attachment upload URL", description = "Roles: TASK and DOG_OBSERVATIONS → INSTRUCTOR, ADMIN (MEMBER → 403, impersonation → IMPERSONATION_DENIED); the other purposes → ADMIN, MEMBER (also the impersonation token). S03/S07/S10 upload contract (R-10-11). INSTRUCTOR_NOTE, TASK and DOG_OBSERVATIONS require TASKS; ACTIVITY_IMAGE and ACTIVITY_DOCUMENT require ACTIVITIES; mimeType ∈ files.allowedTypes (images: image/*), sizeBytes ≤ files.maxSizeMb (DOG_PHOTO: files.dogPhotoMaxMb) → FILE_TOO_LARGE{maxSizeMb}. Upload using the returned headers; URLs last five minutes. Ownership and files.maxAttachmentsPerEntity are checked when attaching the uploaded file.",
            responses = @ApiResponse(responseCode = "201", description = "Upload URL, opaque file key, required headers and expiry"))
    public AttachmentService.Upload upload(@Valid @RequestBody UploadRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (STAFF_PURPOSES.contains(request.purpose())) { access.staffWriter(access.caller(jwt.getClaimAsString("memberId"))); }
        else if (!anyRole("ADMIN", "MEMBER")) { throw new ApiException(FORBIDDEN); }
        // E6-T03 round 5 (INC-47): a new grant each time, but the first grant of a database creates the grants' collection,
        // and a second transaction doing the same meets a write conflict: retried, never a 500.
        return transactions.keyed(201, () -> attachments.upload(request.purpose(), request.fileName(), request.mimeType(), request.sizeBytes()),
                KeyedAnswers.json(mapper));
    }
    @PostMapping("/api/v1/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED, MEMBER_ERASED, ATTACHMENT_LIMIT_REACHED, ATTACHMENT_ENTITY_MISMATCH, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED,
            IMPERSONATION_DENIED, IDEMPOTENCY_KEY_REUSED, INVALID_STATE, VALIDATION_ERROR})
    @Operation(summary = "Register an uploaded attachment", description = "Roles: INSTRUCTOR_NOTE → the dog's owner, MEMBER (also the impersonation token, audited; an account with MEMBER and a staff role that owns the dog too, S01 R-01-07; staff otherwise → 403); TASK (a live task) and DOG_OBSERVATIONS (a dog) → INSTRUCTOR, ADMIN (MEMBER → 403, impersonation → IMPERSONATION_DENIED), TASKS required. R-10-11: the same fileKey again → the same attachment and no second AttachmentAdded; at most files.maxAttachmentsPerEntity live ones per entity → ATTACHMENT_LIMIT_REACHED{max}; a fileKey whose upload purpose is not the entityType (e.g. DOG_DOCUMENT) or another account's → ATTACHMENT_ENTITY_MISMATCH; the stored object must match the upload (FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED); name ≤ 80. AttachmentAdded, and a task's attachmentCount + 1. Two simultaneous registrations on one entity are serialized: at the limit, the second → ATTACHMENT_LIMIT_REACHED. An optional Idempotency-Key (S10 §6, CONVENCIONS_API §7) replays the same 201 body; the same key with another body → IDEMPOTENCY_KEY_REUSED. The reused dog of a pending readmission (S04 R-04-06, E38) is frozen: 409 INVALID_STATE with details.reason = READMISSION_PENDING.",
            responses = @ApiResponse(responseCode = "201", description = "Attachment with signed download URL", content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = AttachmentResponse.class))))
    public Map<String,Object> add(@Valid @RequestBody AttachmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Schema(format = "uuid") java.util.UUID idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        var caller = access.caller(jwt.getClaimAsString("memberId"));
        if (STAFF_PURPOSES.contains(request.entityType())) {
            access.writableEntity(caller, AttachmentEntityType.valueOf(request.entityType()), request.entityId());
        } else if (access.owner(caller, request.entityId()).staff()) { throw new ApiException(FORBIDDEN); }
        // E6-T03 round 5 (INC-47): the registration and its stored 201 commit together, retried whole on a write conflict
        // (the entity's lock): two registrations on one entity never answer 500.
        return transactions.keyed(201, () -> attachments.add(request.entityType(), request.entityId(), request.fileKey(), request.name()), KeyedAnswers.json(mapper));
    }
    @GetMapping("/api/v1/attachments")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @RequiresModule(Module.TASKS)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "attachments", description = "Roles: TASK and INSTRUCTOR_NOTE → the dog's owner MEMBER (also the impersonation token), INSTRUCTOR, ADMIN; DOG_OBSERVATIONS → INSTRUCTOR, ADMIN (the member gets 404, R-10-11). The entity's live attachments, oldest first, each with a signed url valid for 5 minutes; another club's or member's entity, or a deleted task → 404. Requires TASKS (MODULE_DISABLED). Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "200", description = "AttachmentList", useReturnTypeSchema = true))
    public FollowupContracts.AttachmentList listAttachments(@RequestParam AttachmentEntityType entityType, @RequestParam String entityId,
            @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        access.readableEntity(access.caller(jwt.getClaimAsString("memberId")), entityType, entityId);
        return new FollowupContracts.AttachmentList(attachments.list(entityType.name(), entityId).stream().map(AttachmentsController::response).toList());
    }
    @DeleteMapping("/api/v1/attachments/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','MEMBER')")
    @RequiresModule(Module.TASKS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, IMPERSONATION_DENIED})
    @Operation(summary = "removeAttachment", description = "Roles: TASK and DOG_OBSERVATIONS → INSTRUCTOR, ADMIN (MEMBER → 403, impersonation → IMPERSONATION_DENIED); INSTRUCTOR_NOTE → the dog's owner MEMBER (also the impersonation token, audited; an account with MEMBER and a staff role that owns the dog too, S01 R-01-07; staff otherwise → 403). R-10-11: removal = removedAt + removedByAccountId (AttachmentRemoved, a task's attachmentCount − 1); the file stays until the GDPR erasure; the attachment leaves the lists. Same Idempotency-Key → the same 204; a removed one → 404, also for the second of two simultaneous removals. Requires TASKS (MODULE_DISABLED). Tenant comes from the JWT.",
            responses = @ApiResponse(responseCode = "204", description = "void", content = @io.swagger.v3.oas.annotations.media.Content))
    public void removeAttachment(@PathVariable String id, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") java.util.UUID idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        access.tenant();
        var caller = access.caller(jwt.getClaimAsString("memberId"));
        // Round 5: the second of two removals with different keys re-reads the attachment removed → 404, never a 500.
        transactions.keyedNoContent(() -> attachments.remove(access.removableAttachment(caller, id)));
    }
    /** The §6 `Attachment` of an {@link AttachmentService} view. */
    static AttachmentResponse response(Map<String, Object> view) {
        return new AttachmentResponse((String) view.get("id"), (String) view.get("name"), (String) view.get("mimeType"), ((Number) view.get("sizeBytes")).longValue(),
                (String) view.get("url"), (java.time.Instant) view.get("uploadedAt"));
    }
    /** The local storage's signed upload URL (CONVENCIONS_API §5, E5-T24): authorised by its signature alone, no bearer. */
    @io.swagger.v3.oas.annotations.Hidden
    @PutMapping("/api/v1/attachments/uploads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putLocal(@PathVariable String id, @RequestParam long expires, @RequestParam String signature, jakarta.servlet.http.HttpServletRequest request) throws IOException {
        attachments.putLocal(id, expires, signature, request.getContentType(), request.getInputStream());
    }
    /** The local storage's signed download URL (CONVENCIONS_API §5, E5-T24): authorised by its signature alone, no bearer. */
    @io.swagger.v3.oas.annotations.Hidden
    @GetMapping("/api/v1/attachments/files/{id}")
    public ResponseEntity<InputStreamResource> getLocal(@PathVariable String id, @RequestParam long expires, @RequestParam String signature) throws IOException {
        return download(attachments.openLocal(id, expires, signature));
    }
    /**
     * E5-T26: a local download answers like S3, with the file's stored MIME type, so that an `<img>` of another origin shows it.
     * An image is `inline` and any other file an `attachment`, both with the stored name; `nosniff` stays. E5-T27 step 8 (ruling
     * E61, CONVENCIONS_API §5): an SVG, which can carry script, is always an `attachment`, and every download carries the api's
     * policy plus `sandbox`, so nothing opened on the api's origin runs there.
     */
    static ResponseEntity<InputStreamResource> download(AttachmentService.Download file) {
        var type = com.agilityhub.core.shared.application.FileDownloads.type(file.mimeType());
        var disposition = com.agilityhub.core.shared.application.FileDownloads.shownInline(type) ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok().contentType(type).contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(file.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("Content-Security-Policy", com.agilityhub.core.shared.application.ContentSecurityPolicies.DOWNLOAD)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(new InputStreamResource(file.content()));
    }
}

package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.MessagingContractAccess;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S11 §6 message templates (D9, R-11-12, R-11-13), ADMIN only; the impersonation token is refused (MATRIU rule 3, T-11-28).
 * Every operation runs the tenant, role and resource guards and then answers 501 NOT_IMPLEMENTED until E7-T03 (E7-T04 the
 * send). Error statuses are CATALEG_ERRORS' (rule 0), whatever S11 §6 writes.
 */
@RestController
@PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
public class MessageTemplatesController {
    static final String STUB = " Contract only; returns 501 NOT_IMPLEMENTED after the tenant, role and resource guards. Tenant comes from the JWT.";
    static final String ROLES = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403). ";
    private final MessagingContractAccess access;
    public MessageTemplatesController(MessagingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/message-templates")
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "messageTemplates", description = ROLES + "D9 list with the counts per category: every CATALOG template of the club (the seed "
            + "of a code without one is created on first use, R-11-01) and its CUSTOM ones; ARCHIVED only with includeArchived. The SMS column follows "
            + "the SMS module (cells kept, R-11-17)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateList", useReturnTypeSchema = true))
    public MessageTemplateList messageTemplates(@RequestParam(required = false) NotificationCategory category, @RequestParam(required = false) TemplateKind kind,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/message-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, TEMPLATE_SYNTAX_ERROR, TEMPLATE_UNKNOWN_VARIABLE, CHANNEL_NOT_ALLOWED, SMS_BODY_REQUIRED, SMS_BODY_TOO_LONG})
    @Operation(summary = "createMessageTemplate", description = ROLES + "[＋ Nova plantilla]: a CUSTOM template of PERSONAL, CLUB_NEWS or CLUB_CHANGES "
            + "(SYSTEM or OPERATIONAL → VALIDATION_ERROR) with member variables only; matrix cells outside the category's caps → CHANNEL_NOT_ALLOWED; an "
            + "active SMS cell needs smsBody. MessageTemplateChanged and CATALOG_CHANGED audit." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail createMessageTemplate(@Valid @RequestBody MessageTemplateCreateRequest request) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/message-templates/{id}")
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "messageTemplate", description = ROLES + "The editor of D9: the texts per club locale, the seed texts (CATALOG) and lastChange. "
            + "Another club's template → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail messageTemplate(@PathVariable String id) {
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @PutMapping("/api/v1/message-templates/{id}")
    @ContractErrors({VALIDATION_ERROR, TEMPLATE_SYNTAX_ERROR, TEMPLATE_UNKNOWN_VARIABLE, CHANNEL_NOT_ALLOWED, SMS_BODY_REQUIRED, SMS_BODY_TOO_LONG, NOT_FOUND,
            STALE_VERSION, TEMPLATE_MANDATORY})
    @Operation(summary = "updateMessageTemplate", description = ROLES + "[DESA] (R-11-12): texts, icon, colour, matrix within caps, enabled (not a "
            + "mandatory one: TEMPLATE_MANDATORY) and, for CUSTOM, the category; version+1 and customized. A missing required variable (N-02 link, N-08a "
            + "admin_text) is VALIDATION_ERROR with details.missingVariables (MissingVariablesDetails) until the catalog has S11's TEMPLATE_MISSING_VARIABLE. "
            + "A stale version → STALE_VERSION. MessageTemplateChanged, CATALOG_CHANGED audit, club cache evicted." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail updateMessageTemplate(@PathVariable String id, @Valid @RequestBody MessageTemplateUpdateRequest request) {
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/message-templates/{id}/preview")
    @ContractErrors({VALIDATION_ERROR, TEMPLATE_SYNTAX_ERROR, NOT_FOUND})
    @Operation(summary = "previewMessageTemplate", description = ROLES + "[Vista prèvia] of the saved texts or of an unsaved draft, in one locale, with "
            + "the fictional data of that locale; SMS length and segments after transliteration; an unknown variable is a warning (rendered empty). "
            + "sendTest (E7-T03) delivers the rendered draft to the acting admin only. Writes nothing else." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "TemplatePreview", useReturnTypeSchema = true))
    public TemplatePreview previewMessageTemplate(@PathVariable String id, @Valid @RequestBody TemplatePreviewRequest request) {
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/message-templates/{id}/reset")
    @ContractErrors({NOT_FOUND, TEMPLATE_NOT_CATALOG})
    @Operation(summary = "resetMessageTemplate", description = ROLES + "«Restaura el text per defecte»: the seed texts, icon, colour and matrix back, "
            + "customized = false; a CUSTOM template → TEMPLATE_NOT_CATALOG (422). MessageTemplateChanged, CATALOG_CHANGED audit." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail resetMessageTemplate(@PathVariable String id) {
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/message-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({NOT_FOUND, TEMPLATE_NOT_CUSTOM})
    @Operation(summary = "archiveMessageTemplate", description = ROLES + "«Elimina»: a CUSTOM template becomes ARCHIVED (nothing is deleted, invisible in "
            + "D9 and in the send dialog); a CATALOG one → TEMPLATE_NOT_CUSTOM (422). No body." + STUB,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void archiveMessageTemplate(@PathVariable String id) {
        access.template(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/message-templates/{id}/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, NOT_FOUND, TEMPLATE_NOT_SENDABLE, NO_RECIPIENTS, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "sendMessageTemplate", description = ROLES + "«Enviar comunicat» (R-11-13, N-24): N-24 or a CUSTOM template only "
            + "(TEMPLATE_NOT_SENDABLE, 422); recipients = the members of the selection or of the filters (the semantics of GET /members, any status the "
            + "list shows; NO_RECIPIENTS, 422). dryRun → 200 with the count and nothing written; otherwise 202, AnnouncementSent{templateId, batchId, "
            + "recipientCount, filters}, one MEMBER notification per member (dedupKey {batchId}:{memberId}) and ANNOUNCEMENT_SENT audit. The same "
            + "Idempotency-Key replays the same batchId." + STUB,
            responses = {@ApiResponse(responseCode = "202", description = "AnnouncementResult", useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "200", description = "AnnouncementResult (dryRun)", content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AnnouncementResult.class)))})
    public AnnouncementResult sendMessageTemplate(@PathVariable String id, @Valid @RequestBody AnnouncementRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.template(id);
        throw new UnsupportedOperationException();
    }
}

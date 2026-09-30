package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.MessageTemplateService;
import com.agilityhub.core.clubs.messaging.application.MessagingContractAccess;
import com.agilityhub.core.clubs.messaging.application.TemplatePreviewService;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.contract.ApiContracts.LastChange;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S11 §6 message templates (D9, R-11-12), ADMIN only; the impersonation token is refused (MATRIU rule 3, T-11-28). E7-T03
 * serves the list, the detail, `CUSTOM` creation, the save, the preview (with «envia prova»), «Restaura el text per defecte»
 * and «Elimina»; `POST …/send` (R-11-13, N-24) keeps answering 501 NOT_IMPLEMENTED after its guards until E7-T04. Error
 * statuses are CATALEG_ERRORS' (rule 0), whatever S11 §6 writes.
 */
@RestController
@PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
public class MessageTemplatesController {
    static final String STUB = " Contract only; returns 501 NOT_IMPLEMENTED after the tenant, role and resource guards. Tenant comes from the JWT.";
    static final String TENANT = " Tenant comes from the JWT.";
    static final String ROLES = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403). ";
    private final MessagingContractAccess access; private final MessageTemplateService templates; private final TemplatePreviewService previews;
    private final IcuMessageSource messages;

    public MessageTemplatesController(MessagingContractAccess access, MessageTemplateService templates, TemplatePreviewService previews, IcuMessageSource messages) {
        this.access = access; this.templates = templates; this.previews = previews; this.messages = messages;
    }

    @GetMapping("/api/v1/message-templates")
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "messageTemplates", description = ROLES + "D9 list in D9's order (category OPERATIONAL · PERSONAL · CLUB_CHANGES · CLUB_NEWS, then "
            + "the catalog's codes, then the CUSTOM ones) with the counts per category (archived ones excluded): every CATALOG template of the club (the "
            + "seed of a code without one is created here, R-11-01) and its CUSTOM ones; ARCHIVED only with includeArchived. name = the title in the "
            + "admin's language; variables = the code's, labelled in the admin's language (notif.variable.<key>). With SMS off, caps leave SMS out (the "
            + "column is not available; the stored cells are kept, R-11-17); with PUSH off, push is empty." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateList", useReturnTypeSchema = true))
    public MessageTemplateList messageTemplates(@RequestParam(required = false) NotificationCategory category, @RequestParam(required = false) TemplateKind kind,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        var listing = templates.list(category, kind, includeArchived);
        var counts = listing.countsByCategory();
        return new MessageTemplateList(listing.items().stream().map(this::item).toList(), new CategoryCounts(counts.get(NotificationCategory.OPERATIONAL),
                counts.get(NotificationCategory.PERSONAL), counts.get(NotificationCategory.CLUB_CHANGES), counts.get(NotificationCategory.CLUB_NEWS)));
    }

    @PostMapping("/api/v1/message-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, TEMPLATE_SYNTAX_ERROR, TEMPLATE_UNKNOWN_VARIABLE, CHANNEL_NOT_ALLOWED, SMS_BODY_REQUIRED, SMS_BODY_TOO_LONG})
    @Operation(summary = "createMessageTemplate", description = ROLES + "[＋ Nova plantilla]: a CUSTOM template of PERSONAL, CLUB_NEWS or CLUB_CHANGES "
            + "(SYSTEM or OPERATIONAL → VALIDATION_ERROR) with member variables only and no action, sent to members only; texts in the club's languages "
            + "(the default one required; body ≤ 2000); matrix cells outside the category's MEMBER caps → CHANNEL_NOT_ALLOWED (422); an active SMS cell "
            + "needs smsBody (≤ 160 GSM-7 rendered with the preview data set). MessageTemplateChanged and CATALOG_CHANGED audit." + TENANT,
            responses = @ApiResponse(responseCode = "201", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail createMessageTemplate(@Valid @RequestBody MessageTemplateCreateRequest request) {
        return detail(templates.create(new MessageTemplateService.Create(request.category(),
                new MessageTemplateService.Texts(request.title(), request.body(), request.smsBody()), request.icon(), request.color(), matrix(request.matrix()))));
    }

    @GetMapping("/api/v1/message-templates/{id}")
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "messageTemplate", description = ROLES + "The editor of D9: the texts per club locale, the seed texts (CATALOG) and lastChange. "
            + "Another club's, an unknown or an archived template → 404." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail messageTemplate(@PathVariable String id) { return detail(templates.get(id)); }

    @PutMapping("/api/v1/message-templates/{id}")
    @ContractErrors({VALIDATION_ERROR, TEMPLATE_SYNTAX_ERROR, TEMPLATE_UNKNOWN_VARIABLE, CHANNEL_NOT_ALLOWED, SMS_BODY_REQUIRED, SMS_BODY_TOO_LONG, NOT_FOUND,
            STALE_VERSION, TEMPLATE_MANDATORY})
    @Operation(summary = "updateMessageTemplate", description = ROLES + "[DESA] (R-11-12): texts, icon, colour, matrix within caps, enabled (not a "
            + "mandatory one: TEMPLATE_MANDATORY) and, for CUSTOM, the category; version+1 and customized (the texts differ from the seed). A missing "
            + "required variable (N-02 link, N-08a admin_text) is VALIDATION_ERROR with details.missingVariables (MissingVariablesDetails). With SMS off the "
            + "SMS cells are kept as stored. A stale version → STALE_VERSION. MessageTemplateChanged and CATALOG_CHANGED audit in the same transaction; "
            + "the next notification uses the saved text (no template cache)." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail updateMessageTemplate(@PathVariable String id, @Valid @RequestBody MessageTemplateUpdateRequest request) {
        return detail(templates.update(id, new MessageTemplateService.Update(new MessageTemplateService.Texts(request.title(), request.body(), request.smsBody()),
                request.icon(), request.color(), matrix(request.matrix()), request.enabled(), request.version(), request.category())));
    }

    @PostMapping("/api/v1/message-templates/{id}/preview")
    @ContractErrors({VALIDATION_ERROR, TEMPLATE_SYNTAX_ERROR, NOT_FOUND})
    @Operation(summary = "previewMessageTemplate", description = ROLES + "[Vista prèvia] of the saved texts or of an unsaved draft, in one of the club's "
            + "locales, with the fictional data of that locale; SMS length and segments after transliteration; an unknown variable and an SMS over 160 "
            + "characters are warnings (the variable renders empty). sendTest («envia prova») also delivers the rendering once to the acting admin's own "
            + "account (APP and its e-mail, never SMS, never anybody else), stored in the log. Writes nothing else." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "TemplatePreview", useReturnTypeSchema = true))
    public TemplatePreview previewMessageTemplate(@PathVariable String id, @Valid @RequestBody TemplatePreviewRequest request) {
        var draft = request.draft() == null ? null : new TemplatePreviewService.Draft(request.draft().title(), request.draft().body(), request.draft().smsBody());
        var preview = previews.preview(id, request.locale(), draft, Boolean.TRUE.equals(request.sendTest()));
        var sms = preview.sms() == null ? null : new SmsPreview(preview.sms().text(), preview.sms().text().length(), preview.sms().segments(), preview.sms().truncated());
        return new TemplatePreview(preview.title(), preview.body(), preview.emailSubject(), preview.emailHtml(), sms,
                preview.warnings().stream().map(w -> new PreviewWarning(w.code(), w.variable())).toList());
    }

    @PostMapping("/api/v1/message-templates/{id}/reset")
    @ContractErrors({NOT_FOUND, TEMPLATE_NOT_CATALOG})
    @Operation(summary = "resetMessageTemplate", description = ROLES + "«Restaura el text per defecte»: the seed texts, icon, colour and matrix back, "
            + "customized = false, enabled kept; a CUSTOM template → TEMPLATE_NOT_CATALOG (422). MessageTemplateChanged, CATALOG_CHANGED audit." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "MessageTemplateDetail", useReturnTypeSchema = true))
    public MessageTemplateDetail resetMessageTemplate(@PathVariable String id) { return detail(templates.reset(id)); }

    @DeleteMapping("/api/v1/message-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({NOT_FOUND, TEMPLATE_NOT_CUSTOM})
    @Operation(summary = "archiveMessageTemplate", description = ROLES + "«Elimina»: a CUSTOM template becomes ARCHIVED (nothing is deleted, invisible in "
            + "D9 and in the send dialog; its id answers 404 afterwards); a CATALOG one → TEMPLATE_NOT_CUSTOM (422). No body." + TENANT,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void archiveMessageTemplate(@PathVariable String id) { templates.archive(id); }

    @PostMapping("/api/v1/message-templates/{id}/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ContractErrors({VALIDATION_ERROR, INVALID_FILTER, NOT_FOUND, TEMPLATE_NOT_SENDABLE, NO_RECIPIENTS, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "sendMessageTemplate", description = ROLES + "«Enviar comunicat» (R-11-13, N-24): N-24 or a CUSTOM template only "
            + "(TEMPLATE_NOT_SENDABLE, 422); recipients = the members of the selection or of the filters (the semantics of GET /members, any status the "
            + "list shows; NO_RECIPIENTS, 422). dryRun → 200 with the count and nothing written; otherwise 202, AnnouncementSent{templateId, batchId, "
            + "recipientCount, filters}, one MEMBER notification per member (dedupKey {batchId}:{memberId}) and ANNOUNCEMENT_SENT audit. The same "
            + "Idempotency-Key replays the same batchId (E7-T04)." + STUB,
            responses = {@ApiResponse(responseCode = "202", description = "AnnouncementResult", useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "200", description = "AnnouncementResult (dryRun)", content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AnnouncementResult.class)))})
    public AnnouncementResult sendMessageTemplate(@PathVariable String id, @Valid @RequestBody AnnouncementRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.template(id);
        throw new UnsupportedOperationException();
    }

    // ---- mapping

    private MessageTemplateListItem item(MessageTemplateService.View view) {
        var t = view.template();
        return new MessageTemplateListItem(t.id(), t.code(), t.kind(), t.category(), name(view), t.icon(), t.color(), t.enabled(), t.customized(), matrix(t.matrix()),
                caps(view.caps()), List.copyOf(view.push()), variables(view.variables()), lastChange(view));
    }
    private MessageTemplateDetail detail(MessageTemplateService.View view) {
        var t = view.template();
        var seed = view.seed() == null ? null : new TemplateTexts(view.seed().title().values(), view.seed().body().values(),
                view.seed().smsBody() == null ? null : view.seed().smsBody().values());
        return new MessageTemplateDetail(t.id(), t.code(), t.kind(), t.category(), name(view), t.icon(), t.color(), t.enabled(), t.customized(), matrix(t.matrix()),
                caps(view.caps()), List.copyOf(view.push()), variables(view.variables()), lastChange(view), t.title().values(), t.body().values(),
                t.smsBody() == null ? null : t.smsBody().values(), seed, t.mandatory(), t.status(), t.version() == null ? 0 : t.version());
    }
    private String name(MessageTemplateService.View view) {
        var title = view.template().title();
        String own = title.values().get(LocaleContext.current().getLanguage());
        return own != null ? own : title.withDefaultLocale(templates.config().club().defaultLocale()).resolve(templates.config().club().defaultLocale()).value();
    }
    private List<TemplateVariable> variables(List<String> keys) {
        var locale = LocaleContext.current();
        return keys.stream().map(key -> new TemplateVariable(key, messages.getMessage("notif.variable." + key, null, key, locale))).toList();
    }
    private static LastChange lastChange(MessageTemplateService.View view) {
        var last = view.lastChange();
        return last == null ? null : new LastChange(last.at(), last.actorName(), last.action().name());
    }
    static ChannelMatrix matrix(Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix) {
        return new ChannelMatrix(row(matrix.get(NotificationAudience.MEMBER)), row(matrix.get(NotificationAudience.INSTRUCTORS)), row(matrix.get(NotificationAudience.ADMINS)));
    }
    private static AudienceChannels row(Map<NotificationChannel, Boolean> row) {
        return row == null ? new AudienceChannels(false, false, false) : new AudienceChannels(Boolean.TRUE.equals(row.get(NotificationChannel.APP)),
                Boolean.TRUE.equals(row.get(NotificationChannel.EMAIL)), Boolean.TRUE.equals(row.get(NotificationChannel.SMS)));
    }
    static Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix(ChannelMatrix matrix) {
        var cells = new EnumMap<NotificationAudience, Map<NotificationChannel, Boolean>>(NotificationAudience.class);
        if (matrix == null) { return cells; }
        cells.put(NotificationAudience.MEMBER, cells(matrix.member())); cells.put(NotificationAudience.INSTRUCTORS, cells(matrix.instructors()));
        cells.put(NotificationAudience.ADMINS, cells(matrix.admins()));
        return cells;
    }
    private static Map<NotificationChannel, Boolean> cells(AudienceChannels row) {
        var cells = new EnumMap<NotificationChannel, Boolean>(NotificationChannel.class);
        if (row != null) { cells.put(NotificationChannel.APP, row.app()); cells.put(NotificationChannel.EMAIL, row.email()); cells.put(NotificationChannel.SMS, row.sms()); }
        return cells;
    }
    private static ChannelCaps caps(Map<NotificationAudience, Set<NotificationChannel>> caps) {
        return new ChannelCaps(ordered(caps.get(NotificationAudience.MEMBER)), ordered(caps.get(NotificationAudience.INSTRUCTORS)), ordered(caps.get(NotificationAudience.ADMINS)));
    }
    private static List<NotificationChannel> ordered(Set<NotificationChannel> channels) {
        var list = new ArrayList<NotificationChannel>();
        if (channels != null) { for (var channel : NotificationChannel.values()) { if (channels.contains(channel)) { list.add(channel); } } }
        return list;
    }
}

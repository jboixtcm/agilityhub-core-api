package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.application.contract.ApiContracts.LastChange;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S11 §6 wire forms of D9 (templates), the notification log, feed 11, the «Avisos» block of 12/D10 and the push devices,
 * field by field from §6 and its two JSON extracts. Instants are UTC; a nullable field is optional and the api sends it as
 * `null`. Localized texts travel as `{locale: text}` maps (`*I18n`, CONVENCIONS_I18N); a read resolves them to the caller's
 * locale with the club's fallback (R-11-01).
 */
public final class MessagingContracts {
    private MessagingContracts() { }

    // ---- D9 templates (R-11-12)
    @Schema(description = "A template variable: the code key written `[[key]]` in the text, and its D9 label in the admin's language (notif.variable.<key>)")
    public record TemplateVariable(String key, String label) { }
    @Schema(description = "The three matrix columns of one audience (S11 §3): APP · EMAIL · SMS. PUSH is fixed by the code (`push`).")
    public record AudienceChannels(@JsonProperty("APP") boolean app, @JsonProperty("EMAIL") boolean email, @JsonProperty("SMS") boolean sms) { }
    @Schema(description = "«Canals per públic — aquesta plantilla» (D9): every row is present; a cell outside `caps` is always false and inert")
    public record ChannelMatrix(@JsonProperty("MEMBER") @NotNull @Valid AudienceChannels member,
            @JsonProperty("INSTRUCTORS") @NotNull @Valid AudienceChannels instructors, @JsonProperty("ADMINS") @NotNull @Valid AudienceChannels admins) { }
    @Schema(description = "Per audience, the matrix cells the code lets the template activate (R-11-12); empty for an audience of no row of the code")
    public record ChannelCaps(@JsonProperty("MEMBER") List<NotificationChannel> member, @JsonProperty("INSTRUCTORS") List<NotificationChannel> instructors,
            @JsonProperty("ADMINS") List<NotificationChannel> admins) { }
    @Schema(description = "Templates per category, the card «Plantilles per categoria» of D9 (archived ones excluded)")
    public record CategoryCounts(@JsonProperty("OPERATIONAL") int operational, @JsonProperty("PERSONAL") int personal,
            @JsonProperty("CLUB_CHANGES") int clubChanges, @JsonProperty("CLUB_NEWS") int clubNews) { }
    public record MessageTemplateListItem(String id,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "N-xx of a CATALOG template; null for CUSTOM") String code,
            TemplateKind kind, NotificationCategory category, @Schema(description = "The title in the admin's language") String name,
            TemplateIcon icon, TemplateColor color, boolean enabled, boolean customized, ChannelMatrix matrix, ChannelCaps caps,
            @Schema(description = "Audiences with a web push (fixed by the code; the Push column of D9 is informative, PUSH module)") List<NotificationAudience> push,
            @Schema(description = "The code's variables only (CUSTOM: the member ones, R-11-12)") List<TemplateVariable> variables,
            @Schema(requiredMode = NOT_REQUIRED, description = "Last audited change (CATALOG_CHANGED, R-14-11); null when never edited") LastChange lastChange) { }
    public record MessageTemplateList(List<MessageTemplateListItem> items, CategoryCounts countsByCategory) { }
    @Schema(description = "The seed texts of a CATALOG template, for «Restaura el text per defecte»")
    public record TemplateTexts(Map<String, String> titleI18n, Map<String, String> bodyI18n,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, String> smsBodyI18n) { }
    public record MessageTemplateDetail(String id, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String code, TemplateKind kind,
            NotificationCategory category, String name, TemplateIcon icon, TemplateColor color, boolean enabled, boolean customized, ChannelMatrix matrix,
            ChannelCaps caps, List<NotificationAudience> push, List<TemplateVariable> variables,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(description = "Title per club locale (the `ca`/`es` tabs of D9)") Map<String, String> titleI18n,
            @Schema(description = "Body (≤ 2000) per club locale; the text of the MEMBER audience (staff texts are product copy, §13-3)") Map<String, String> bodyI18n,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "«SMS (text curt)» per club locale; null when no SMS cell may be active")
            Map<String, String> smsBodyI18n,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "The seed texts; null for CUSTOM") TemplateTexts seedDefault,
            boolean mandatory, TemplateStatus status, @Schema(description = "PUT optimistic lock (STALE_VERSION)") long version) { }
    @Schema(description = "[＋ Nova plantilla]: a CUSTOM template (category PERSONAL · CLUB_NEWS · CLUB_CHANGES)")
    public record MessageTemplateCreateRequest(@NotNull NotificationCategory category, @NotEmpty Map<String, String> title, @NotEmpty Map<String, String> body,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Required when an SMS cell is active (SMS_BODY_REQUIRED); ≤ 160 GSM-7 rendered (SMS_BODY_TOO_LONG)")
            Map<String, String> smsBody,
            @NotNull TemplateIcon icon, @NotNull TemplateColor color, @NotNull @Valid ChannelMatrix matrix) { }
    public record MessageTemplateUpdateRequest(@NotEmpty Map<String, String> title, @NotEmpty Map<String, String> body,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "A full replacement: absent or null removes it (then no SMS cell may be active)")
            Map<String, String> smsBody,
            @NotNull TemplateIcon icon, @NotNull TemplateColor color, @NotNull @Valid ChannelMatrix matrix,
            @NotNull @Schema(description = "false = DISABLED; a mandatory template cannot (TEMPLATE_MANDATORY)") Boolean enabled,
            @NotNull @PositiveOrZero Long version,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "CUSTOM only (the category of a CATALOG template is the code's)") NotificationCategory category) { }
    @Schema(description = "An unsaved draft of one locale")
    public record TemplateDraft(@NotBlank String title, @NotBlank String body, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String smsBody) { }
    public record TemplatePreviewRequest(@NotBlank @Schema(description = "One of club.locales") String locale,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Without a draft, the saved texts") @Valid TemplateDraft draft,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "«envia prova»: one delivery of the rendered draft to the acting admin only (E7-T03)") Boolean sendTest) { }
    @Schema(description = "The SMS after transliteration to GSM-7 (R-11-06)")
    public record SmsPreview(String text, int length, int segments, @Schema(description = "Cut with «…» at 160 characters") boolean truncated) { }
    public record PreviewWarning(@Schema(description = "Catalog code, e.g. TEMPLATE_UNKNOWN_VARIABLE (rendered empty, R-11-05)") String code,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String variable) { }
    @Schema(description = "Rendered with the fictional data of the locale («Laura», «Duna», «dimecres 12 · 18:50 · B+C · Central», R-11-12)")
    public record TemplatePreview(String title, String body, String emailSubject, String emailHtml,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null when the template has no SMS text") SmsPreview sms,
            List<PreviewWarning> warnings) { }
    @Schema(description = "Either the selection (memberIds) or the list's query (filters + q, the semantics of GET /members), as D5/D15 opened the dialog")
    public record AnnouncementRecipients(@Schema(requiredMode = NOT_REQUIRED, nullable = true) List<@NotBlank String> memberIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "field:op:value, the filters of GET /members") List<@NotBlank String> filters,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String q) { }
    public record AnnouncementRequest(@NotNull @Valid AnnouncementRecipients recipients,
            @NotNull @Schema(description = "true: count only («S'enviarà a {n} abonats»), 200 and nothing written") Boolean dryRun) { }
    public record AnnouncementResult(@Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null on dryRun") String batchId, int recipientCount) { }
    /** `details` of `VALIDATION_ERROR` for a missing required variable (S11 §6 `TEMPLATE_MISSING_VARIABLE` is a catalog proposal). */
    public record MissingVariablesDetails(@Schema(description = "The template's required variables absent from the text (N-08a admin_text; N-02's link is its "
            + "welcome e-mail's only, E76)") List<String> missingVariables) { }
    /** `details` of `422 CHANNEL_NOT_ALLOWED` (E76): the cell D9 marks, and every refused one. */
    public record ChannelNotAllowedDetails(@Schema(description = "The first refused cell's row") NotificationAudience audience,
            @Schema(description = "The first refused cell's channel") NotificationChannel channel, @Schema(description = "Every refused cell") List<ChannelCell> cells) { }
    public record ChannelCell(NotificationAudience audience, NotificationChannel channel) { }
    /** `details` of `TEMPLATE_SYNTAX_ERROR`, `TEMPLATE_UNKNOWN_VARIABLE`, `SMS_BODY_REQUIRED` and `SMS_BODY_TOO_LONG` (E76): the text D9 marks. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record TemplateFieldDetails(@Schema(description = "The text: title.<locale>, body.<locale> or smsBody.<locale> (SMS_BODY_REQUIRED: the default "
            + "language's)", example = "body.ca") String field,
            @Schema(requiredMode = NOT_REQUIRED, description = "TEMPLATE_UNKNOWN_VARIABLE only: every unknown variable of the save") List<String> variables,
            @Schema(requiredMode = NOT_REQUIRED, description = "SMS_BODY_TOO_LONG only: the maximum length, 160") Integer max) { }

    // ---- The notification log (ADMIN, R-11-10)
    public record NotificationRecipient(String displayName, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String memberId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "APPLICANT rows: the only recipient data") String email) { }
    public record ChannelState(NotificationChannel channel, DeliveryStatus status) { }
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record NotificationListItem(String id, Instant createdAt, String code, NotificationCategory category,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null on a row written before E7-T02 that does not tell it") NotificationAudience audience,
            NotificationRecipient recipient, @Schema(description = "One per delivery") List<ChannelState> channels,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant readAt) { }
    @Schema(description = "Universal list page (CONVENCIONS_API §4) of the notification log, createdAt desc by default")
    public record NotificationPage(List<NotificationListItem> items, @Schema(minimum = "0") int page, @Schema(minimum = "1") int size,
            @Schema(minimum = "0") long totalItems, @Schema(minimum = "0") int totalPages, List<Filter> appliedFilters) { }
    @Schema(description = "The action parameters and the log filter (S11 §3); null keys do not apply")
    public record NotificationSubject(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String dogId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String bookingId, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String classSessionId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String waitlistEntryId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String trainingBookingId, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String invoiceId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityId, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String taskId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String memberId) { }
    public record DeliveryView(NotificationChannel channel,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "The address, phone (E.164) or push subscription id; null for APP") String target,
            DeliveryStatus status, int attempts, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant nextAttemptAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "The provider's message id") String providerRef,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "The provider's last error") String lastError,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant sentAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant deliveredAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant failedAt) { }
    public record NotificationDetail(String id, Instant createdAt, String code, NotificationCategory category,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null on a row written before E7-T02 that does not tell it") NotificationAudience audience,
            NotificationRecipient recipient, List<ChannelState> channels, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant readAt,
            @Schema(description = "Rendered and frozen") String title, @Schema(description = "Rendered and frozen") String body,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String smsBody, @Schema(description = "The locale it was rendered in") String locale,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null for SYSTEM codes (no template)") String templateId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Long templateVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null for a direct send (N-50, N-53, a test send)") String eventType,
            NotificationSubject subject, List<DeliveryView> deliveries) { }

    // ---- Feed 11 (R-11-10, R-11-11)
    @Schema(description = "The native action (R-11-11): CHANGE_CLASS and CLAIM_SEAT are buttons, the rest open on tapping the card; enabled is computed on reading")
    public record FeedAction(NotificationActionType type, Map<String, String> params, boolean enabled) { }
    public record MeNotification(String id, String code, NotificationCategory category, TemplateIcon icon, TemplateColor color, String title, String body,
            Instant createdAt, @Schema(description = "Channels that reached the member; SMS only when SENT or DELIVERED («i per SMS»)") List<NotificationChannel> channels,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant readAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null when the code has no action («—»)") FeedAction action) { }
    public record MeNotifications(@Schema(description = "createdAt desc; only notifications with an APP delivery") List<MeNotification> items,
            @Schema(minimum = "0") int page, @Schema(minimum = "1") int size, @Schema(minimum = "0") long totalItems,
            @Schema(minimum = "0", description = "The same count as GET /me/home notifications.unreadCount") long unreadCount) { }
    public record ReadResult(@Schema(minimum = "0") long unreadCount) { }

    // ---- «Avisos» of 12 / D10 (R-11-04)
    @Schema(description = "E-mail per category; the fourth row CLUB_NEWS exists although mockup 12 does not show it (S11 §13-2)")
    public record EmailByCategory(@JsonProperty("OPERATIONAL") boolean operational, @JsonProperty("PERSONAL") boolean personal,
            @JsonProperty("CLUB_CHANGES") boolean clubChanges, @JsonProperty("CLUB_NEWS") boolean clubNews) { }
    public record PreferenceModules(@Schema(description = "SMS module: «+SMS» shown") boolean sms, @Schema(description = "PUSH module: the push toggle shown") boolean push) { }
    public record NotificationPreferences(EmailByCategory emailByCategory,
            @Schema(description = "Always true: the SMS of CLUB_CHANGES cannot be removed («+SMS», Josep 18-08)") boolean smsFixed,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "null = «Mai»") Integer reminderMinutesBefore,
            @Schema(description = "messaging.reminderOptionsMinutes") List<Integer> reminderOptionsMinutes, boolean pushClubNews,
            @Schema(description = "Account.locale (PATCH /me, S01)") String locale, @Schema(description = "club.locales") List<String> availableLocales,
            PreferenceModules modules) { }
    @Schema(description = "Partial: an absent category keeps its value")
    public record EmailByCategoryPatch(@JsonProperty("OPERATIONAL") @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean operational,
            @JsonProperty("PERSONAL") @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean personal,
            @JsonProperty("CLUB_CHANGES") @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean clubChanges,
            @JsonProperty("CLUB_NEWS") @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean clubNews) { }
    @Schema(description = "Partial save (T-11-20): an absent key keeps its value")
    public record NotificationPreferencesRequest(@Schema(requiredMode = NOT_REQUIRED, nullable = true) @Valid EmailByCategoryPatch emailByCategory,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, implementation = Integer.class,
                    description = "Absent keeps the value; null = «Mai»; otherwise one of messaging.reminderOptionsMinutes (INVALID_REMINDER_OPTION)")
            ReminderChoice reminderMinutesBefore,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean pushClubNews) { }
    /**
     * `reminderMinutesBefore` of the partial save: an absent key is a Java `null` (keep the value), a JSON `null` is
     * `ReminderChoice(null)` («Mai») and a number is `ReminderChoice(n)`. A record component alone cannot tell the first two apart.
     */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ReminderChoice.Reader.class)
    public record ReminderChoice(@com.fasterxml.jackson.annotation.JsonValue Integer minutes) {
        public static final class Reader extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<ReminderChoice> {
            public Reader() { super(ReminderChoice.class); }
            @Override public ReminderChoice deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context)
                    throws java.io.IOException { return new ReminderChoice(context.readValue(parser, Integer.class)); }
            @Override public ReminderChoice getNullValue(com.fasterxml.jackson.databind.DeserializationContext context) { return new ReminderChoice(null); }
            @Override public Object getAbsentValue(com.fasterxml.jackson.databind.DeserializationContext context) { return null; }
        }
    }

    // ---- Push (R-11-07) and CLUB_NEWS unsubscribe (R-11-08)
    @Schema(description = "The browser's PushSubscription keys, base64url: p256dh a 65-byte P-256 public key, auth a 16-byte secret")
    public record PushKeys(@NotBlank @Size(min = 1, max = 200) String p256dh, @NotBlank @Size(min = 1, max = 200) String auth) { }
    public record PushSubscriptionRequest(@NotBlank @Size(max = 2048) @Schema(format = "uri") String endpoint, @NotNull @Valid PushKeys keys,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Derived from the User-Agent when absent («iPhone · Safari»)") @Size(max = 120) String deviceLabel) { }
    public record PushSubscriptionCreated(String id) { }
    public record EmailUnsubscribeRequest(@NotBlank @Schema(description = "The signed token of the «Deixar de rebre aquests comunicats» link (30 days)") String token) { }
    public record EmailUnsubscribeResult(@Schema(description = "The category whose e-mail was turned off: always CLUB_NEWS") NotificationCategory category) { }
}

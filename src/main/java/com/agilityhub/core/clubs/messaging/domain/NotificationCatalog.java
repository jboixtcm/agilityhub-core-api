package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.clubs.messaging.domain.NotificationSpec.DedupKeyRule;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec.RelevanceRule;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec.Stage;
import com.agilityhub.core.platform.application.Module;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static com.agilityhub.core.clubs.messaging.domain.NotificationActionType.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationCategory.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationChannel.*;

/**
 * `CATALEG_NOTIFICACIONS.md` as code (S11 WP-11-A): every row of the main table (N-01…N-38, 43 rows) and of Annex A (18
 * rows), in the document's order. N-12 does not exist (the numbering jumps from N-11b to N-13). `NotificationCatalogContractTest`
 * proves both directions against the document. Product code, never stored in the database; read-only lookups.
 *
 * <p>Where the document leaves room, the code decides and says so here: a channel written «(+X …)» is allowed but its seed
 * default is the code's (N-24 PUSH on: the toggle is a preference; N-32a EMAIL off: `ActivityPublished.notifyEmail` decides;
 * N-42 EMAIL on, as E5-T01 sends it); «(X segons preferència)» is on (N-13). Audiences written in words map to the enum:
 * «inscrits», «selecció», «compte» → `MEMBER`; «compte convidat» → `APPLICANT` (an address without an account yet);
 * «admins de plataforma» → `ADMINS`. N-17's «MEMBER inscrit → N-08a» is N-08a's own `MEMBER` row.</p>
 */
public final class NotificationCatalog {
    /** R-11-12 `CUSTOM` templates and N-24: the member variables (`member_*`), `gender`, `club_name` and `dog_name`. */
    public static final List<String> CUSTOM_VARIABLES = List.of("member_name", "member_first_name", "member_last_names", "gender", "dog_name", "club_name");
    /**
     * The two variables of the Annex's «Variables noves» with no code of their own: formatting forms of `member_name`
     * (D9 «persona_cognoms») and of `dog_name` («la Duna», R-11-05), available wherever those are.
     */
    public static final Map<String, String> DERIVED_VARIABLES = Map.of("member_last_names", "member_name", "dog_name_article", "dog_name");
    /**
     * «`club_name` és una variable general» («Variables disponibles», E66, 27-09): any template may use it, whatever its code,
     * so it is not repeated in every row's `variables`. The engine gives it to every notice.
     */
    public static final List<String> GENERAL_VARIABLES = List.of("club_name");

    private static final Map<String, NotificationSpec> BY_CODE;
    static {
        var specs = new LinkedHashMap<String, NotificationSpec>();
        for (var spec : rows()) {
            if (specs.put(spec.code(), spec) != null) { throw new IllegalStateException("Duplicated " + spec.code()); }
        }
        BY_CODE = Collections.unmodifiableMap(specs);
    }

    private NotificationCatalog() { }

    public static Optional<NotificationSpec> byCode(String code) { return Optional.ofNullable(code == null ? null : BY_CODE.get(code)); }

    /**
     * Variables of a code's e-mail copy that its template (the APP copy) never has (E76): N-02's welcome e-mail carries the
     * S01 link, a credential identity renders itself; the reader of the feed is already signed in.
     */
    public static final Map<String, Set<String>> EMAIL_COPY_VARIABLES = Map.of("N-02", Set.of("link"));

    /**
     * The one list of the `[[var]]` a club template of the code may use (E7-T03 round 2): D9's «Variables:» («només les del
     * codi», S11 §2), what a save accepts (R-11-12), what the preview renders and what the engine delivers. The row's
     * variables (without those of its e-mail copy only, {@link #EMAIL_COPY_VARIABLES}), the derived forms whose base is there
     * (`member_last_names`, `dog_name_article`) and the general `club_name`. The member variables of R-11-12 (`member_*`,
     * `gender`, `dog_name`) belong to `CUSTOM` templates (N-24's row); a catalog code has one only when its row does.
     */
    public static List<String> templateVariables(NotificationSpec spec) {
        var keys = new java.util.LinkedHashSet<String>(spec.variables());
        keys.removeAll(EMAIL_COPY_VARIABLES.getOrDefault(spec.code(), Set.of()));
        DERIVED_VARIABLES.forEach((derived, base) -> { if (keys.contains(base)) { keys.add(derived); } });
        keys.addAll(GENERAL_VARIABLES);
        return List.copyOf(keys);
    }
    /** The variables every language of the code's template must contain: the row's required ones but its e-mail copy's (E76). */
    public static Set<String> templateRequiredVariables(NotificationSpec spec) {
        var required = new java.util.LinkedHashSet<String>(spec.requiredVariables());
        required.removeAll(EMAIL_COPY_VARIABLES.getOrDefault(spec.code(), Set.of()));
        return Collections.unmodifiableSet(required);
    }
    /**
     * N-24, the announcement (S11 R-11-13): the one code «Enviar comunicat» sends, under which a `CUSTOM` template is sent and
     * whose PUSH `pushClubNews` turns off whatever the category (ruling E82). One constant (E7-T04 round 3, review nit #3).
     */
    public static final String ANNOUNCEMENT = "N-24";
    /** R-11-12 `CUSTOM` templates: the member variables, the list of N-24, the code that carries them (§7, R-11-13). */
    public static List<String> customTemplateVariables() { return templateVariables(BY_CODE.get(ANNOUNCEMENT)); }
    /** R-11-12 caps of a `CUSTOM` template (sent to members only, S11 §13-6): the category's `MEMBER` caps; none for staff. */
    public static Set<NotificationChannel> customCaps(NotificationCategory category, NotificationAudience audience) {
        return audience == MEMBER ? caps(category, audience) : Set.of();
    }
    /** Every registered code, in the document's order (main table, then Annex A). */
    public static List<String> codes() { return List.copyOf(BY_CODE.keySet()); }
    public static List<NotificationSpec> specs() { return List.copyOf(BY_CODE.values()); }
    /** The codes an event emits (0..n): R1 codes only, a `LATER` code is never emitted. */
    public static List<NotificationSpec> specsFor(String eventType) {
        return BY_CODE.values().stream().filter(spec -> spec.stage() == Stage.R1 && spec.eventTypes().contains(eventType)).toList();
    }

    /**
     * A variant of the Annex A «Variants» line (N-32b with `origin = BACKOFFICE` → `CLUB_CHANGES`, S07): the same code with the
     * other category, whose caps are also its channels for every audience (a variant has no template of its own); push unchanged.
     */
    public static NotificationSpec variant(NotificationSpec spec, NotificationCategory category) {
        var caps = new EnumMap<NotificationAudience, Set<NotificationChannel>>(NotificationAudience.class);
        for (var audience : spec.audiences()) { caps.put(audience, caps(category, audience)); }
        return new NotificationSpec(spec.code(), spec.eventTypes(), category, spec.audiences(), caps, caps, spec.push(), spec.actions(), spec.variables(),
                spec.requiredVariables(), spec.mandatory(), spec.icon(), spec.color(), spec.dedupKeyFn(), spec.stillRelevantFn(), spec.moduleGuards(), spec.stage());
    }

    /** R-11-12 caps: `OPERATIONAL`/`PERSONAL`/`CLUB_NEWS` → APP+EMAIL; `CLUB_CHANGES` → +SMS for `MEMBER`; `SYSTEM` and `APPLICANT` → EMAIL. */
    static Set<NotificationChannel> caps(NotificationCategory category, NotificationAudience audience) {
        if (category == SYSTEM || audience == APPLICANT) { return EnumSet.of(EMAIL); }
        return category == CLUB_CHANGES && audience == MEMBER ? EnumSet.of(APP, EMAIL, SMS) : EnumSet.of(APP, EMAIL);
    }

    private static List<NotificationSpec> rows() {
        var rows = new ArrayList<NotificationSpec>();
        // ---- Main table
        rows.add(code("N-01", OPERATIONAL, "SignupSubmitted").to(APPLICANT, EMAIL).to(ADMINS, APP, EMAIL).action(ADMINS, OPEN_SIGNUP)
                .vars("member_name", "dogs", "plan_name", "club_name", "upfront_total", "payment_instructions", "pay_link").build());
        rows.add(code("N-02", PERSONAL, "MemberValidated").to(MEMBER, EMAIL, APP).vars("member_first_name", "gender", "club_name", "link")
                .required("link").mandatory().seed(TemplateIcon.mail, TemplateColor.OK).build());
        rows.add(code("N-03", PERSONAL, "SignupRejected").to(APPLICANT, EMAIL).vars("member_name", "reason", "club_name").build());
        rows.add(code("N-04", OPERATIONAL, "BookingCreated").to(MEMBER, APP).action(OPEN_BOOKING)
                .vars("dog_name", "class_date", "class_time", "class_description", "ring_name", "calendar_links").seed(TemplateIcon.check, TemplateColor.OK).build());
        rows.add(code("N-05", OPERATIONAL, "BookingCancelled").to(MEMBER, APP).vars("dog_name", "class_date", "class_time", "late").build());
        rows.add(code("N-06", OPERATIONAL, "TrainingBooked").to(MEMBER, APP).vars("dog_name", "date", "time", "ring_name")
                .seed(TemplateIcon.check, TemplateColor.OK).modules(Module.FREE_TRAINING).build());
        rows.add(code("N-07", OPERATIONAL, "TrainingCancelled").to(MEMBER, APP).vars("dog_name", "date", "time", "ring_name").modules(Module.FREE_TRAINING).build());
        rows.add(code("N-08a", CLUB_CHANGES, "ClassCancelledByClub").to(MEMBER, APP, EMAIL, SMS).to(INSTRUCTORS, APP, EMAIL).to(ADMINS, APP)
                .action(CHANGE_CLASS).vars("dog_name", "class_date", "class_time", "class_description", "admin_text").required("admin_text").mandatory()
                .seed(TemplateIcon.x, TemplateColor.ERROR).build());
        rows.add(code("N-08b", CLUB_CHANGES, "ClassSessionUpdated").to(MEMBER, APP, EMAIL, SMS).to(INSTRUCTORS, APP).action(OPEN_BOOKING)
                .vars("dog_name", "class_date", "changes").build());
        rows.add(code("N-09", PERSONAL, "DogLevelChanged").to(MEMBER, APP, EMAIL).action(OPEN_DOG).vars("dog_name", "level_name")
                .seed(TemplateIcon.up, TemplateColor.OK).build());
        rows.add(code("N-10", OPERATIONAL, "InvoiceFailed").to(ADMINS, APP, EMAIL).action(OPEN_INVOICES)
                .vars("member_name", "invoice_number", "amount", "reason").modules(Module.BILLING).build());
        rows.add(code("N-11a", PERSONAL, "PackLowBalance").to(MEMBER, APP, EMAIL).action(OPEN_DOG).vars("dog_name", "pack_remaining")
                .modules(Module.BILLING, Module.PACKS).build());
        rows.add(code("N-11b", PERSONAL, "PackExpiring", "PackExpired").to(MEMBER, APP, EMAIL).action(OPEN_DOG).vars("dog_name", "pack_expiry")
                .modules(Module.BILLING, Module.PACKS).build());
        // E66 (27-09): N-13, N-15 and N-16 gain `class_description`; N-21 and N-22 `gender`; N-28 its member variables (Annex A «Variables noves»).
        rows.add(code("N-13", OPERATIONAL, "ReminderDue").to(MEMBER, APP, PUSH, EMAIL).action(OPEN_BOOKING)
                .vars("dog_name", "date", "time", "ring_name", "kind", "class_description")
                .seed(TemplateIcon.clock, TemplateColor.NEUTRAL).dedup(DedupKeyRule.PER_BOOKING).relevance(RelevanceRule.BOOKING_ACTIVE_AND_FUTURE).build());
        rows.add(code("N-14", OPERATIONAL, "LeaveRequested").to(ADMINS, APP, EMAIL).action(OPEN_MEMBER).vars("member_name", "requested_date", "reason").build());
        // R-11-12: the one per-code exception to the caps, SMS for the member although the category is OPERATIONAL. S11 §7 emits it on WaitlistNotified.
        rows.add(code("N-15", OPERATIONAL, "WaitlistNotified").to(MEMBER, APP, SMS, PUSH).memberSms().action(CLAIM_SEAT)
                .vars("dog_name", "class_date", "class_time", "confirm_by", "mode", "entityId", "class_description").mandatory()
                .seed(TemplateIcon.unlock, TemplateColor.ACCENT).modules(Module.WAITLIST).build());
        rows.add(code("N-16", CLUB_CHANGES, "ClassAtRisk").to(MEMBER, APP, EMAIL).to(ADMINS, APP).action(CHANGE_CLASS)
                .vars("dog_name", "class_date", "class_time", "review_time", "review_day", "audience", "class_description", "auto_cancel")
                .seed(TemplateIcon.warn, TemplateColor.WARNING).build());
        rows.add(code("N-17", CLUB_CHANGES, "ClassAutoCancelled").to(INSTRUCTORS, APP, EMAIL).to(ADMINS, APP, EMAIL).action(CHANGE_CLASS)
                .vars("class_date", "class_time", "dogs_count", "class_description", "ring_name", "auto_cancel").mandatory().build());
        rows.add(code("N-18a", OPERATIONAL, "InactivityRequested").to(ADMINS, APP, EMAIL).action(OPEN_MEMBER).vars("member_name", "from_month", "to_month")
                .modules(Module.INACTIVITY).build());
        rows.add(code("N-18b", PERSONAL, "InactivityResolved").to(MEMBER, APP, EMAIL)
                .vars("from_month", "to_month", "decision", "fee", "admin_text", "cancelled_count").modules(Module.INACTIVITY).build());
        rows.add(code("N-18c", PERSONAL, "InactivityEnded").to(MEMBER, APP).modules(Module.INACTIVITY).build());
        rows.add(code("N-19", PERSONAL, "NoShowNoticeDue").to(MEMBER, APP, EMAIL).vars("dog_name", "class_date", "class_description")
                .seed(TemplateIcon.heart, TemplateColor.NEUTRAL).build());
        rows.add(code("N-20", PERSONAL, "TaskCreated").to(MEMBER, APP, EMAIL).action(OPEN_TASKS).vars("dog_name", "instructor_name", "task_excerpt")
                .modules(Module.TASKS).build());
        rows.add(code("N-21", OPERATIONAL, "TaskCompleted").to(INSTRUCTORS, APP).action(OPEN_DOG).vars("member_name", "dog_name", "task_excerpt", "gender")
                .modules(Module.TASKS).build());
        rows.add(code("N-22", OPERATIONAL, "MemberNoteChanged").to(INSTRUCTORS, APP).action(OPEN_DOG).vars("member_name", "dog_name", "gender")
                .modules(Module.TASKS).build());
        rows.add(code("N-23", PERSONAL, "DocumentReminderDue", "DogDocumentPending").to(MEMBER, APP, EMAIL).action(OPEN_DOG).vars("dog_name", "document_type").build());
        rows.add(code("N-24", CLUB_NEWS, "AnnouncementSent").to(MEMBER, APP, EMAIL, PUSH).vars(CUSTOM_VARIABLES.toArray(String[]::new))
                .dedup(DedupKeyRule.PER_BATCH_MEMBER).build());
        rows.add(code("N-25", SYSTEM, "MagicLinkRequested").to(MEMBER, EMAIL).vars("link", "expires_minutes").build());
        rows.add(code("N-26", SYSTEM, "PasswordChanged").to(MEMBER, EMAIL).build());
        rows.add(code("N-27", SYSTEM, "AccessResent").to(MEMBER, EMAIL).vars("link").build());
        rows.add(code("N-28", PERSONAL, "LeaveResolved").to(MEMBER, APP, EMAIL)
                .vars("effective_date", "admin_text", "cancelled_count", "decision", "source", "member_first_name", "dog_name")
                .seed(TemplateIcon.doc, TemplateColor.NEUTRAL).build());
        rows.add(code("N-29", PERSONAL, "BookingBlockChanged").to(MEMBER, APP, EMAIL).vars("reason").build());
        rows.add(code("N-30", PERSONAL, "InvoicePaid", "UpfrontPaymentSucceeded").to(MEMBER, EMAIL).action(OPEN_INVOICES)
                .vars("amount", "concept", "invoice_number").modules(Module.BILLING).build());
        rows.add(code("N-31", OPERATIONAL, "RingSetupChanged").to(MEMBER, APP).action(OPEN_SETUP).vars("ring_name", "setup_kind", "level").modules(Module.COURSES).build());
        rows.add(code("N-32a", CLUB_NEWS, "ActivityPublished").to(MEMBER, APP).action(OPEN_ACTIVITY).vars("activity_title", "date").modules(Module.ACTIVITIES).build());
        rows.add(code("N-32b", OPERATIONAL, "ActivityRegistrationChanged").to(MEMBER, APP).action(OPEN_ACTIVITY).vars("activity_title", "date", "state")
                .modules(Module.ACTIVITIES).build());
        rows.add(code("N-32c", CLUB_CHANGES, "ActivityCancelled").to(MEMBER, APP, EMAIL, SMS).vars("activity_title", "date", "admin_text").mandatory()
                .modules(Module.ACTIVITIES).build());
        rows.add(code("N-33", OPERATIONAL, "WeekOpened").to(MEMBER, APP, PUSH).action(OPEN_BOOKING).vars("week_start").build());
        rows.add(code("N-34", OPERATIONAL, "SignupPendingAging").to(ADMINS, APP).action(OPEN_SIGNUP).vars("count", "oldest_days").build());
        rows.add(code("N-35", PERSONAL, "InvoiceFailed", "MemberCardInvalidated").to(MEMBER, APP, EMAIL).action(OPEN_INVOICES).vars("amount", "reason", "retry_link")
                .modules(Module.BILLING).build());
        rows.add(code("N-36", CLUB_CHANGES, "BookingCreated", "BookingCancelled").to(MEMBER, APP, EMAIL, SMS).action(OPEN_BOOKING)
                .vars("dog_name", "class_date", "class_time", "actor", "change").mandatory().build());
        rows.add(code("N-37", PERSONAL, "DogRegistered", "DogDeactivated").to(MEMBER, APP).action(OPEN_DOG).vars("dog_name").build());
        rows.add(code("N-38", PERSONAL, "MemberPaymentMethodChanged").to(MEMBER, EMAIL).vars("masked_account").modules(Module.BILLING).build());
        // ---- Annex A (03-09)
        rows.add(code("N-18d", OPERATIONAL, "InactivityChanged").to(ADMINS, APP).action(OPEN_MEMBER).vars("member_name", "from_month", "to_month")
                .modules(Module.INACTIVITY).build());
        rows.add(code("N-32d", CLUB_CHANGES, "ActivityUpdated").to(MEMBER, APP, EMAIL, SMS).action(OPEN_ACTIVITY).vars("activity_title", "changes")
                .modules(Module.ACTIVITIES).build());
        rows.add(code("N-39", SYSTEM, "SignupRecognitionRequested").to(MEMBER, EMAIL).vars("link", "expires_minutes", "club_name").build());
        rows.add(code("N-40", OPERATIONAL, "BookingCancelled").to(MEMBER, APP, EMAIL).action(OPEN_BOOKING).vars("dog_name", "class_date", "class_time").build());
        rows.add(code("N-41", OPERATIONAL, "RemittanceReminderDue").to(ADMINS, APP, EMAIL).action(OPEN_INVOICES).vars("period", "pending_count")
                .modules(Module.BILLING).build());
        rows.add(code("N-42", OPERATIONAL, "JobFailed").to(ADMINS, APP, EMAIL).action(OPEN_JOBS).vars("job_name", "error_count").build());
        rows.add(code("N-43", SYSTEM, "ClubDomainBroken").to(ADMINS, EMAIL).vars("club_name", "host").build());
        rows.add(code("N-44", CLUB_NEWS, "ChallengePublished").to(MEMBER, APP).action(OPEN_CHALLENGE).vars("challenge_title", "level").later().build());
        rows.add(code("N-45", PERSONAL, "ChallengeAttemptValidated").to(MEMBER, APP, EMAIL).action(OPEN_CHALLENGE).vars("challenge_title", "score").later().build());
        // E5-T03: the seat taken of an ALL_AT_ONCE offer; E5 consumes these three events (CATALEG_NOTIFICACIONS N-46 row).
        rows.add(code("N-46", OPERATIONAL, "WaitlistConsolidated", "BookingCreated", "SeatHoldReleased").to(MEMBER, APP)
                .vars("dog_name", "class_date", "class_time").modules(Module.WAITLIST).build());
        rows.add(code("N-47", CLUB_CHANGES, "TrainingBooked", "TrainingCancelled").to(MEMBER, APP, EMAIL, SMS).action(OPEN_BOOKING)
                .vars("dog_name", "date", "time", "ring_name", "admin_text", "change").modules(Module.FREE_TRAINING).build());
        rows.add(code("N-48", PERSONAL, "MembershipChanged").to(MEMBER, APP, EMAIL).vars("club_name", "role").later().build());
        rows.add(code("N-49", OPERATIONAL, "SmsCapReached").to(ADMINS, APP, EMAIL).vars("month", "cap").modules(Module.SMS).build());
        // «ExportJob{MEMBER_DATA} → READY» is no catalog event: the S14 export worker triggers N-50 directly.
        rows.add(code("N-50", PERSONAL).to(MEMBER, APP, EMAIL).action(OPEN_EXPORT).vars("link", "expires_days").build());
        rows.add(code("N-51", OPERATIONAL, "EmailBounced").to(ADMINS, APP).action(OPEN_MEMBER).vars("member_name", "email").build());
        rows.add(code("N-52", SYSTEM, "AccountErasureRequested").to(MEMBER, EMAIL).vars("execute_date", "club_name").build());
        rows.add(code("N-54", OPERATIONAL, "ClassBelowMinimum").to(INSTRUCTORS, APP, EMAIL).to(ADMINS, APP, EMAIL).action(CHANGE_CLASS)
                .vars("class_date", "class_time", "class_description", "ring_name", "dogs_count").build());
        // `POST /platform/clubs/{id}/admins` (S17) is a request, not an event: the console triggers N-53 directly.
        rows.add(code("N-53", SYSTEM).to(APPLICANT, EMAIL).vars("club_name", "link", "inviter_name").build());
        return rows;
    }

    private static Builder code(String code, NotificationCategory category, String... eventTypes) { return new Builder(code, category, List.of(eventTypes)); }

    private static final class Builder {
        private final String code; private final NotificationCategory category; private final List<String> eventTypes;
        private final List<NotificationAudience> audiences = new ArrayList<>();
        private final Map<NotificationAudience, Set<NotificationChannel>> defaults = new EnumMap<>(NotificationAudience.class);
        private final Set<NotificationAudience> push = EnumSet.noneOf(NotificationAudience.class);
        private final Map<NotificationAudience, NotificationActionType> actions = new EnumMap<>(NotificationAudience.class);
        private NotificationActionType action;
        private List<String> variables = List.of(); private Set<String> required = Set.of();
        private boolean mandatory, memberSms;
        private TemplateIcon icon = TemplateIcon.bell; private TemplateColor color = TemplateColor.NEUTRAL;
        private DedupKeyRule dedup = DedupKeyRule.DEFAULT; private RelevanceRule relevance = RelevanceRule.ALWAYS;
        private Set<Module> modules = Set.of(); private Stage stage = Stage.R1;

        Builder(String code, NotificationCategory category, List<String> eventTypes) { this.code = code; this.category = category; this.eventTypes = eventTypes; }
        /** An audience with its default channels; `PUSH` goes to the push set, never to the template. */
        Builder to(NotificationAudience audience, NotificationChannel... channels) {
            audiences.add(audience);
            var seeded = EnumSet.noneOf(NotificationChannel.class);
            for (var channel : channels) { if (channel == PUSH) { push.add(audience); } else { seeded.add(channel); } }
            defaults.put(audience, seeded);
            return this;
        }
        Builder memberSms() { memberSms = true; return this; }
        Builder action(NotificationActionType type) { action = type; return this; }
        Builder action(NotificationAudience audience, NotificationActionType type) { actions.put(audience, type); return this; }
        Builder vars(String... keys) { variables = List.of(keys); return this; }
        Builder required(String... keys) { required = Set.of(keys); return this; }
        Builder mandatory() { mandatory = true; return this; }
        Builder seed(TemplateIcon seedIcon, TemplateColor seedColor) { icon = seedIcon; color = seedColor; return this; }
        Builder dedup(DedupKeyRule rule) { dedup = rule; return this; }
        Builder relevance(RelevanceRule rule) { relevance = rule; return this; }
        Builder modules(Module... guards) { modules = Set.of(guards); return this; }
        Builder later() { stage = Stage.LATER; return this; }
        NotificationSpec build() {
            var caps = new EnumMap<NotificationAudience, Set<NotificationChannel>>(NotificationAudience.class);
            for (var audience : audiences) {
                var allowed = NotificationCatalog.caps(category, audience);
                if (memberSms && audience == MEMBER) { allowed.add(SMS); }
                caps.put(audience, allowed);
            }
            if (action != null) { for (var audience : audiences) { actions.putIfAbsent(audience, action); } }
            return new NotificationSpec(code, eventTypes, category, audiences, caps, defaults, push, actions, variables, required, mandatory, icon, color,
                    dedup, relevance, modules, stage);
        }
    }
}

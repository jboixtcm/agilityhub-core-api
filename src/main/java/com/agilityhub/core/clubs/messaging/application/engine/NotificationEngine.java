package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.BookingRelevancePort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.domain.ChannelResolver;
import com.agilityhub.core.clubs.messaging.domain.DeliveryStatus;
import com.agilityhub.core.clubs.messaging.domain.DogNameArticle;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationPreference;
import com.agilityhub.core.clubs.messaging.domain.NotificationEvent;
import com.agilityhub.core.clubs.messaging.domain.NotificationEventEnvelope;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ClubFormats;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S11 WP-11-B, the one notification engine: every outbox event of the `NotificationCatalog` becomes its notifications
 * (S11 §6 pipeline). Runs inside the outbox dispatcher's transaction, outside any business transaction: for each code of
 * the event (`specsFor`) — module guards (R-11-17), the parameter conditions of S11 §7, the owner's facts
 * ({@link NotificationFactsPort}), the club's template (created from the seed on first use; `DISABLED` → nothing, R-11-01),
 * `stillRelevant` (R-11-16: a stale reminder is stored once with a `SKIPPED_STALE` delivery), the recipients (R-11-02), the
 * recipient's language (`Account.locale` → the member's own → `club.defaultLocale`), the formatted variables (R-11-05),
 * the rendered texts, the `dedupKey` (R-11-09) and the deliveries of R-11-03. New notifications are inserted in batches
 * and a reprocessed event only adds the deliveries a document lacks; then the owner's hook runs, `NotificationQueued` is
 * published per notification and channel, and the dispatcher sends right after the commit, outside the transaction.
 * `SYSTEM` codes are never produced here: their product copy goes through `SystemNotificationService`.
 */
public class NotificationEngine {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationEngine.class);
    /** Variables that carry a credential or a payment capability: rendered, never stored in `variables` (R-14-18). */
    static final Set<String> SECRET_VARIABLES = Set.of("link", "pay_link", "retry_link");
    private final ClubConfigService configs; private final IcuMessageSource messages; private final TemplateProvider templates;
    private final RecipientResolver recipients; private final TemplateRenderer renderer = new TemplateRenderer();
    private final NotificationRepository notifications; private final PushSubscriptionRepository subscriptions; private final NotificationAccounts accounts;
    private final List<NotificationFactsPort> owners; private final BookingRelevancePort bookings; private final EventPublisher events;
    private final NotificationDispatcher dispatcher; private final Clock clock; private final TransactionTemplate outside;

    public NotificationEngine(ClubConfigService configs, IcuMessageSource messages, TemplateProvider templates, RecipientResolver recipients,
            NotificationRepository notifications, PushSubscriptionRepository subscriptions, NotificationAccounts accounts, List<NotificationFactsPort> owners,
            BookingRelevancePort bookings, EventPublisher events, NotificationDispatcher dispatcher, Clock clock, PlatformTransactionManager transactions) {
        this.configs = configs; this.messages = messages; this.templates = templates; this.recipients = recipients; this.notifications = notifications;
        this.subscriptions = subscriptions; this.accounts = accounts; this.owners = List.copyOf(owners); this.bookings = bookings; this.events = events;
        this.dispatcher = dispatcher; this.clock = clock;
        this.outside = new TransactionTemplate(transactions); outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** The outbox entry point (`notifications.<EventType>` handlers). */
    public void handle(String eventId, NotificationEventEnvelope event) {
        if (event.clubId() == null) { return; } // a platform event: the SYSTEM path owns it
        var trigger = new NotificationTrigger(eventId, event.type(), event.clubId(), event.aggregateType(), event.aggregateId(), event.occurredAt(),
                event.payload(), event.actorAccountId(), event.impersonatedMemberId(), event.origin());
        try (var tenant = TenantContext.open(event.clubId())) {
            var ids = process(trigger);
            afterCommit(event.clubId(), ids);
        }
    }

    /**
     * A catalog event its owner derives from another one inside that one's outbox delivery (S15 R-15-11: the deferred N-33 of
     * a week validated after its opening is the `WeekOpened` notice): the same pipeline and the same sending after the commit.
     */
    public void emit(NotificationTrigger trigger) {
        try (var tenant = TenantContext.open(trigger.clubId())) { afterCommit(trigger.clubId(), process(trigger)); }
    }

    /**
     * Notifications built outside a catalog event in the current club (E7-T03, D9's test send of a template): stored like any
     * other (the log shows them), `NotificationQueued` once per channel, and their queued deliveries sent after the commit.
     */
    public void deliver(List<Notification> fresh) {
        String clubId = TenantContext.require();
        notifications.insertAll(fresh);
        fresh.forEach(notification -> publishQueued(notification.id(), notification.deliveries()));
        afterCommit(clubId, fresh.stream().filter(notification -> hasQueued(notification.deliveries())).map(Notification::id).toList());
    }

    /** The notifications of one event in the current club; returns the ids with deliveries to send. */
    public List<String> process(NotificationTrigger trigger) {
        ClubConfig config;
        try { config = configs.get(trigger.clubId()); }
        catch (ApiException missing) { if (missing.code() == ErrorCode.CLUB_NOT_FOUND) { return List.of(); } throw missing; }
        var toSend = new LinkedHashSet<String>();
        for (var spec : NotificationCatalog.specsFor(trigger.type())) {
            if (!spec.templated()) { continue; }
            if (!config.modules().containsAll(spec.moduleGuards())) { continue; }
            if (!conditions(spec, trigger, config)) { continue; }
            var facts = facts(trigger, spec);
            if (facts.isEmpty()) { continue; }
            // R-11-13 (ruling E82): an announcement renders the template (N-24's own, or a CUSTOM one) as its batch froze it at the
            // send, whatever the template's status or texts are now; any other code renders the club's template, unless DISABLED.
            MessageTemplate template;
            if (facts.get().templateId() == null) {
                template = templates.forCode(spec, config.club().locales(), config.club().defaultLocale());
                if (template.status() != TemplateStatus.ACTIVE || !template.enabled()) { continue; }
            } else {
                template = templates.asSent(trigger.text("batchId"), facts.get().templateId()).orElse(null);
                if (template == null) {
                    // A batch stored before E7-T04's round 2 has no frozen copy: nothing rather than today's text, and it is said
                    // (round 2 assumption R2-2; review nit #1 of round 3). Ids only.
                    LOG.warn("Announcement batch without its frozen template; nothing sent batchId={} templateId={}", trigger.text("batchId"), facts.get().templateId());
                    continue;
                }
            }
            List<Notification> built = relevant(spec, trigger) ? build(spec, trigger, facts.get(), template, config) : stale(spec, trigger, facts.get(), template);
            var stored = store(spec, trigger, built);
            for (var owner : ownersOf(trigger.type())) { owner.stored(trigger, spec.code(), stored.views()); }
            toSend.addAll(stored.toSend());
        }
        return List.copyOf(toSend);
    }

    /** S11 §7 «Particularitats» that depend on club parameters (the rest are the owners' facts). */
    boolean conditions(NotificationSpec spec, NotificationTrigger trigger, ClubConfig config) {
        return switch (spec.code()) {
            case "N-33" -> Boolean.TRUE.equals(config.get("messaging.notifyWeekOpening", Boolean.class));
            case "N-31" -> Boolean.TRUE.equals(config.get("messaging.notifyNewRingSetup", Boolean.class)) && "ACTIVE".equals(trigger.text("status"));
            case "N-35" -> trigger.type().equals("MemberCardInvalidated") || "STRIPE".equals(trigger.text("provider"));
            case "N-30" -> trigger.type().equals("UpfrontPaymentSucceeded") || "STRIPE".equals(trigger.text("provider")) && !Boolean.TRUE.equals(config.get("billing.stripeReceiptEmail", Boolean.class));
            default -> true;
        };
    }

    private Optional<NotificationFacts> facts(NotificationTrigger trigger, NotificationSpec spec) {
        var candidates = ownersOf(trigger.type());
        if (candidates.isEmpty()) { return Optional.of(payloadFacts(trigger)); }
        for (var owner : candidates) {
            var facts = owner.facts(trigger, spec.code());
            if (facts.isPresent()) { return facts; }
        }
        return Optional.empty();
    }
    /**
     * An event no owner explains yet (S12/S13 events of later stages): the payload's members (R-11-02 default) and its scalar
     * values as variables, under their own name and in `snake_case` (`effectiveDate` → `effective_date`).
     */
    static NotificationFacts payloadFacts(NotificationTrigger trigger) {
        var builder = NotificationFacts.builder();
        trigger.payload().forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                builder.value(key, value);
                builder.value(key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT), value);
            }
        });
        return builder.build();
    }
    private List<NotificationFactsPort> ownersOf(String type) { return owners.stream().filter(owner -> owner.eventTypes().contains(type)).toList(); }

    private boolean relevant(NotificationSpec spec, NotificationTrigger trigger) {
        if (spec.stillRelevantFn() == NotificationSpec.RelevanceRule.ALWAYS) { return true; }
        return bookings.stillActive(trigger.text("bookingId"), trigger.text("trainingBookingId"), clock.instant());
    }

    /** R-11-16: the reminder of a booking that is no longer active is stored once, with a `SKIPPED_STALE` delivery. */
    private List<Notification> stale(NotificationSpec spec, NotificationTrigger trigger, NotificationFacts facts, MessageTemplate template) {
        var memberId = trigger.text("memberId");
        var subject = facts.subject().with(new NotificationSubject(trigger.text("dogId"), trigger.text("bookingId"), null, null, trigger.text("trainingBookingId"),
                null, null, null, memberId));
        String dedupKey = spec.dedupKey(new NotificationSpec.DedupKeyRule.Input(occurrence(trigger, facts), NotificationAudience.MEMBER,
                "member:" + Objects.toString(memberId, "unknown"), memberId, trigger.text("dogId"), trigger.payload()));
        var now = clock.instant();
        return List.of(notification(spec, trigger, template, dedupKey, NotificationAudience.MEMBER, new Notification.Recipient(null, memberId, null, null, null),
                null, subject, "", "", null, null, List.of(new Notification.Delivery(NotificationChannel.APP, null, DeliveryStatus.SKIPPED_STALE, 0, null, null,
                        null, null, null, null)), Map.of(), now));
    }

    private List<Notification> build(NotificationSpec catalogSpec, NotificationTrigger trigger, NotificationFacts facts, MessageTemplate template, ClubConfig config) {
        // The catalog's Annex A variant (N-32b from the back office → CLUB_CHANGES): its category and its caps as channels. A CUSTOM
        // template (R-11-12, sent as N-24 by R-11-13) has its own category, whose caps bound the template's own matrix below.
        var spec = facts.categoryOverride() != null ? NotificationCatalog.variant(catalogSpec, NotificationCategory.valueOf(facts.categoryOverride()))
                : template.kind() == TemplateKind.CUSTOM ? NotificationCatalog.variant(catalogSpec, template.category()) : catalogSpec;
        var resolved = recipients.resolve(spec, trigger, facts);
        var formats = new ClubFormats(config, messages);
        var formatter = new VariableFormatter(formats, messages, clock, config.club().defaultLocale());
        boolean smsModule = config.modules().contains(Module.SMS), pushModule = config.modules().contains(Module.PUSH);
        boolean transliterate = !Boolean.FALSE.equals(config.get("messaging.sms.transliterateToGsm7", Boolean.class));
        var accountIds = resolved.stream().map(RecipientResolver.Recipient::accountId).filter(Objects::nonNull).distinct().toList();
        var pushTargets = pushModule && !spec.push().isEmpty() ? subscriptions.activeFor(accountIds) : Map.<String, List<String>>of();
        var known = known(spec);
        var now = clock.instant();
        var built = new ArrayList<Notification>();
        for (var recipient : resolved) {
            var account = recipient.accountId() == null ? null : accounts.find(recipient.accountId()).orElse(null);
            var locale = locale(recipient, account, config);
            var raw = new LinkedHashMap<String, Object>(facts.values());
            raw.putAll(facts.valuesOf(recipient.audience().name()));
            derived(recipient, spec, config, locale, raw);
            raw.putAll(recipient.values());
            var variables = formatter.format(raw, locale);
            boolean staff = recipient.audience() == NotificationAudience.INSTRUCTORS || recipient.audience() == NotificationAudience.ADMINS;
            String title = staff ? staffCopy(spec, "title", locale).orElse(null) : null, body = staff ? staffCopy(spec, "body", locale).orElse(null) : null;
            if (title == null) { title = formatter.localized(template.title(), locale); }
            if (body == null) { body = formatter.localized(template.body(), locale); }
            String sms = template.smsBody() == null ? null : formatter.localized(template.smsBody(), locale);
            var rendered = renderer.render(spec.code(), title, body, sms, variables, locale, known, transliterate);
            var contact = contact(recipient, account, pushTargets);
            var row = new java.util.EnumMap<NotificationChannel, Boolean>(NotificationChannel.class);
            var base = facts.categoryOverride() == null ? template.matrix().get(recipient.audience()) : spec.defaultMatrix().get(recipient.audience());
            if (base != null) { row.putAll(base); }
            facts.enabledChannels().forEach(channel -> row.put(NotificationChannel.valueOf(channel), true)); // within the caps: the resolver ignores any other cell
            var planned = ChannelResolver.resolve(spec, recipient.audience(), row, new ChannelResolver.Modules(smsModule, pushModule), contact);
            var deliveries = new ArrayList<Notification.Delivery>();
            for (var delivery : planned) {
                if (facts.excludedChannels().contains(delivery.channel().name())) { continue; }
                deliveries.add(new Notification.Delivery(delivery.channel(), delivery.target(), delivery.status(), 0,
                        delivery.status() == DeliveryStatus.QUEUED ? now : null, null, null, null, delivery.status() == DeliveryStatus.DELIVERED ? now : null, null));
            }
            // R-11-09 `recipientKey`: the account, else the normalized address, else (a member without account nor address) the member.
            String recipientKey = recipient.accountId() != null || recipient.email() != null
                    ? NotificationSpec.DedupKeyRule.recipientKey(recipient.accountId(), recipient.email()) : "member:" + recipient.memberId();
            String occurrence = recipient.occurrence() != null ? recipient.occurrence() : occurrence(trigger, facts);
            String dedupKey = spec.dedupKey(new NotificationSpec.DedupKeyRule.Input(occurrence, recipient.audience(), recipientKey,
                    recipient.memberId(), recipient.dogId(), trigger.payload()));
            var stored = new LinkedHashMap<String, Object>(variables);
            SECRET_VARIABLES.forEach(stored::remove);
            var notificationRecipient = new Notification.Recipient(recipient.accountId(), recipient.memberId(), null,
                    recipient.audience() == NotificationAudience.APPLICANT ? recipient.email() : null,
                    recipient.contact() == null ? (recipient.applicant() == null ? null : recipient.applicant().displayName()) : recipient.contact().displayName());
            built.add(notification(spec, trigger, template, dedupKey, recipient.audience(), notificationRecipient, locale, recipient.subject(),
                    rendered.title(), rendered.body(), rendered.sms() == null ? null : rendered.sms().text(), action(spec, recipient), deliveries, stored, now));
        }
        return built;
    }

    private Notification notification(NotificationSpec spec, NotificationTrigger trigger, MessageTemplate template, String dedupKey, NotificationAudience audience,
            Notification.Recipient recipient, Locale locale, NotificationSubject subject, String title, String body, String smsBody, Notification.Action action,
            List<Notification.Delivery> deliveries, Map<String, Object> variables, Instant now) {
        var ids = subject == null ? NotificationSubject.NONE : subject;
        return new Notification(UUID.randomUUID().toString(), trigger.clubId(), spec.code(), spec.category(), template.id(), template.version(), trigger.eventId(),
                trigger.type(), dedupKey, audience, recipient, locale == null ? null : locale.toLanguageTag(),
                new Notification.Subject(ids.dogId(), ids.bookingId(), ids.classSessionId(), ids.waitlistEntryId(), ids.trainingBookingId(), ids.invoiceId(),
                        ids.activityId(), ids.taskId(), ids.memberId()),
                template.icon(), template.color(), title, body, smsBody, action, List.copyOf(deliveries), null, now,
                null, null, null, null, null, null, null, null, variables);
    }

    /** R-11-11: the native action fixed by the code for the audience, with the subject's ids as parameters. */
    static Notification.Action action(NotificationSpec spec, RecipientResolver.Recipient recipient) {
        var type = spec.action(recipient.audience());
        if (type == null) { return null; }
        var params = new LinkedHashMap<String, String>();
        var s = recipient.subject();
        if (s.dogId() != null) { params.put("dogId", s.dogId()); }
        if (s.classSessionId() != null) { params.put("classSessionId", s.classSessionId()); }
        if (s.waitlistEntryId() != null) { params.put("waitlistEntryId", s.waitlistEntryId()); }
        if (s.bookingId() != null) { params.put("bookingId", s.bookingId()); }
        if (s.trainingBookingId() != null) { params.put("trainingBookingId", s.trainingBookingId()); }
        if (s.activityId() != null) { params.put("activityId", s.activityId()); }
        if (s.taskId() != null) { params.put("taskId", s.taskId()); }
        if (s.invoiceId() != null) { params.put("invoiceId", s.invoiceId()); }
        if (s.memberId() != null && recipient.audience() != NotificationAudience.MEMBER) { params.put("memberId", s.memberId()); }
        return new Notification.Action(type, params);
    }

    /**
     * The values every code can use: `club_name`, the recipient's names and gender, the subject dog's name and article,
     * `audience`. `member_last_names` is the derived form of `member_name` wherever the name facts are (E7-T06): the MEMBER
     * recipient's own, or the facts' `member_name`/`member_first_name` for any other audience (an applicant's, from the signup).
     */
    private void derived(RecipientResolver.Recipient recipient, NotificationSpec spec, ClubConfig config, Locale locale, Map<String, Object> raw) {
        raw.putIfAbsent("club_name", config.club().name());
        raw.put("audience", recipient.audience() == NotificationAudience.MEMBER || recipient.audience() == NotificationAudience.APPLICANT ? "MEMBER" : "STAFF");
        if (recipient.audience() == NotificationAudience.MEMBER && recipient.contact() != null) {
            var contact = recipient.contact();
            raw.putIfAbsent("member_name", contact.displayName());
            raw.putIfAbsent("member_first_name", contact.firstName());
            raw.putIfAbsent("member_last_names", lastNames(contact.displayName(), contact.firstName()));
            raw.putIfAbsent("gender", contact.gender() == null ? "OTHER" : contact.gender());
            // The subject dog whoever owns it (the family member who booked the owner's dog reads its name, A20b).
            var dog = Optional.ofNullable(recipient.subjectDog());
            if (dog.isPresent()) {
                raw.putIfAbsent("dog_name", dog.get().name());
                raw.putIfAbsent("dog_name_article", DogNameArticle.of(dog.get().name(), dog.get().sex(), locale));
            } else if (recipient.dogId() == null && raw.get("dog_name") == null) {
                // R-11-12 member variables (every MEMBER notice, N-28's copy among them): `dog_name` = the member's active dogs,
                // joined in the recipient's language («Duna i Rock», «Duna y Rock», «Duna and Rock»).
                var names = contact.dogs().stream().filter(d -> !"INACTIVE".equals(d.status())).map(MemberContact.DogContact::name).filter(Objects::nonNull).toList();
                if (!names.isEmpty()) { raw.put("dog_name", com.ibm.icu.text.ListFormatter.getInstance(locale).format(names)); }
            }
        }
        if (raw.get("dog_name") instanceof String name && !raw.containsKey("dog_name_article")) { raw.put("dog_name_article", name); }
        if (!raw.containsKey("member_last_names") && raw.get("member_name") instanceof String name && raw.get("member_first_name") instanceof String first) {
            raw.put("member_last_names", lastNames(name, first));
        }
    }
    /** The surnames of a full name that starts with the first name («Laura Serra Puig» → «Serra Puig»); empty otherwise. */
    static String lastNames(String fullName, String firstName) {
        if (fullName == null || firstName == null || fullName.length() <= firstName.length()) { return ""; }
        return fullName.startsWith(firstName) ? fullName.substring(firstName.length()).strip() : "";
    }

    /** `notif.{code}.staff.{part}`: the non-editable product copy of the staff audiences (part C S11). */
    private Optional<String> staffCopy(NotificationSpec spec, String part, Locale locale) {
        String key = "notif." + spec.code() + ".staff." + part;
        String pattern = messages.patternIn(key, locale);
        if (pattern == null) { pattern = messages.patternIn(key, Locale.forLanguageTag("ca")); }
        return Optional.ofNullable(pattern);
    }

    /** R-11-01: `Account.locale` → the member's own (signup) → `club.defaultLocale` → the club's first language; a product language. */
    Locale locale(RecipientResolver.Recipient recipient, NotificationAccounts.Recipient account, ClubConfig config) {
        var candidates = new ArrayList<String>();
        if (recipient.applicant() != null) { candidates.add(recipient.applicant().locale()); } // APPLICANT: `signup.locale` first
        if (account != null) { candidates.add(account.locale()); }
        if (recipient.contact() != null) { candidates.add(recipient.contact().locale()); }
        candidates.add(config.club().defaultLocale());
        candidates.addAll(config.club().locales());
        for (String tag : candidates) {
            if (tag == null || tag.isBlank()) { continue; }
            var locale = Locale.forLanguageTag(tag);
            if (messages.supports(locale)) { return Locale.forLanguageTag(locale.getLanguage()); }
        }
        return Locale.forLanguageTag("ca");
    }

    /** The R-11-03 contact of a recipient; an address the account marked as bounced (E1-T03) counts as bounced too ({@link EmailSuppression}). */
    private static ChannelResolver.Contact contact(RecipientResolver.Recipient recipient, NotificationAccounts.Recipient account, Map<String, List<String>> push) {
        if (recipient.applicant() != null) {
            return new ChannelResolver.Contact(recipient.applicant().accountId(), List.of(new ChannelResolver.EmailAddress(recipient.applicant().email(), false)),
                    List.of(), List.of(), null);
        }
        var person = recipient.contact();
        return new ChannelResolver.Contact(person.accountId(), EmailSuppression.addresses(person, account), person.phones(),
                person.accountId() == null ? List.of() : push.getOrDefault(person.accountId(), List.of()), NotificationPreference.of(person.preferences()));
    }

    /** The `[[var]]` the code renders: the one list of D9, the save and the preview ({@link NotificationCatalog#templateVariables}). */
    static Set<String> known(NotificationSpec spec) { return new HashSet<>(NotificationCatalog.templateVariables(spec)); }
    private static String occurrence(NotificationTrigger trigger, NotificationFacts facts) { return facts.occurrence() == null ? trigger.eventId() : facts.occurrence(); }

    private record Stored(List<NotificationFactsPort.StoredNotification> views, List<String> toSend) { }

    /** Upsert by `dedupKey`: new documents in batches; an existing one only gets the deliveries it lacks (R-11-09). */
    private Stored store(NotificationSpec spec, NotificationTrigger trigger, List<Notification> built) {
        var existing = notifications.byDedupKeys(built.stream().map(Notification::dedupKey).toList());
        var fresh = new ArrayList<Notification>(); var views = new ArrayList<NotificationFactsPort.StoredNotification>(); var toSend = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var notification : built) {
            if (!seen.add(notification.dedupKey())) { continue; }
            var current = existing.get(notification.dedupKey());
            if (current == null) {
                fresh.add(notification);
                views.add(view(notification, true));
                if (hasQueued(notification.deliveries())) { toSend.add(notification.id()); }
                continue;
            }
            var missing = new ArrayList<Notification.Delivery>();
            for (var delivery : notification.deliveries()) {
                // R-11-16: a later reminder of a booking that is no longer active leaves the stored reminder as it is.
                if (delivery.status() == DeliveryStatus.SKIPPED_STALE) { continue; }
                boolean present = current.deliveries() != null && current.deliveries().stream().anyMatch(d -> d.channel() == delivery.channel()
                        && Objects.equals(d.target(), delivery.target()) && (delivery.target() != null || d.status() == delivery.status()));
                if (!present) { missing.add(delivery); }
            }
            notifications.addDeliveries(current.id(), missing);
            var merged = new ArrayList<>(current.deliveries() == null ? List.<Notification.Delivery>of() : current.deliveries()); merged.addAll(missing);
            views.add(new NotificationFactsPort.StoredNotification(current.id(), current.code(), current.eventType(), current.audience() == null ? null : current.audience().name(),
                    current.recipient() == null ? null : current.recipient().accountId(), current.recipient() == null ? null : current.recipient().memberId(),
                    NotificationDispatcher.view(current).subject(), merged.stream().map(d -> new NotificationFactsPort.DeliveryView(d.channel().name(), d.status().name())).toList(), false));
            if (hasQueued(merged)) { toSend.add(current.id()); }
            publishQueued(current.id(), missing);
        }
        notifications.insertAll(fresh);
        fresh.forEach(notification -> publishQueued(notification.id(), notification.deliveries()));
        if (!built.isEmpty()) { LOG.debug("Notifications stored code={} event={} new={} total={}", spec.code(), trigger.type(), fresh.size(), built.size()); }
        return new Stored(views, toSend);
    }
    private static boolean hasQueued(List<Notification.Delivery> deliveries) { return deliveries.stream().anyMatch(d -> d.status() == DeliveryStatus.QUEUED); }
    private static NotificationFactsPort.StoredNotification view(Notification notification, boolean created) {
        var base = NotificationDispatcher.view(notification);
        return new NotificationFactsPort.StoredNotification(base.notificationId(), base.code(), base.eventType(), base.audience(), base.accountId(), base.memberId(),
                base.subject(), base.deliveries(), created);
    }
    /** `NotificationQueued{notificationId, channel}` once per channel of the new deliveries (S11 §5). */
    private void publishQueued(String notificationId, List<Notification.Delivery> deliveries) {
        var channels = new LinkedHashSet<NotificationChannel>();
        deliveries.forEach(delivery -> channels.add(delivery.channel()));
        for (var channel : channels) {
            events.publish(new NotificationEvent(NotificationEvent.Kind.NotificationQueued, TenantContext.require(), notificationId, clock.instant(), channel));
        }
    }

    /** The sends happen after the commit, outside any transaction (decision E13); without a transaction, at once. */
    private void afterCommit(String clubId, List<String> ids) {
        if (ids.isEmpty()) { return; }
        Runnable send = () -> {
            try (var tenant = TenantContext.open(clubId)) { outside.executeWithoutResult(tx -> dispatcher.dispatch(ids)); }
            catch (RuntimeException failure) {
                // The deliveries stay QUEUED: the 5-second poll retries them.
                LOG.warn("Inline notification dispatch failed; the poll retries it error={}", failure.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { send.run(); } });
        } else { send.run(); }
    }
}

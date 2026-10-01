package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateSampleData;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateValidator;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditQuery;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S11 R-11-12, D9's templates (ADMIN): the list with its counts (every eligible code of the club has its `CATALOG` template
 * afterwards, R-11-01), the detail with the seed texts, `CUSTOM` creation, the save with optimistic locking, «Restaura el text
 * per defecte» and «Elimina» (`CUSTOM` → `ARCHIVED`; nothing is deleted). Every change is validated by
 * {@link TemplateValidator}, sets `customized` when a catalog template's texts differ from the seed, publishes
 * `MessageTemplateChanged{id, diff}` and writes `CATALOG_CHANGED` (`entityType = MessageTemplate`) in the same transaction.
 * There is no template cache: the engine reads `message_templates` at every event, so the next notice uses the saved text.
 * With `SMS` off the SMS cells are kept as stored and a save leaves them untouched (R-11-17).
 */
@Service
public class MessageTemplateService {
    static final String ENTITY = "MessageTemplate", SYSTEM_ACTOR = "system:message-templates";
    /** D9's card order (S11 §2): «Operativa · Comunicats individuals · Canvis en reserves · Comunicats del club». */
    public static final List<NotificationCategory> CATEGORIES = List.of(NotificationCategory.OPERATIONAL, NotificationCategory.PERSONAL,
            NotificationCategory.CLUB_CHANGES, NotificationCategory.CLUB_NEWS);
    /** R-11-12: a `CUSTOM` template is a personal notice, club news or a change in bookings. */
    static final Set<NotificationCategory> CUSTOM_CATEGORIES = EnumSet.of(NotificationCategory.PERSONAL, NotificationCategory.CLUB_NEWS, NotificationCategory.CLUB_CHANGES);

    private final MessageTemplateRepository templates; private final TemplateProvider provider; private final ClubConfigService configs;
    private final EventPublisher events; private final AuditWriter audit; private final AuditQuery audits; private final Clock clock;
    private final TemplateSampleData samples; private final TemplateValidator validator = new TemplateValidator();
    private final MessagingTransactions transactions;

    public MessageTemplateService(MessageTemplateRepository templates, TemplateProvider provider, ClubConfigService configs, EventPublisher events,
            AuditWriter audit, AuditQuery audits, IcuMessageSource messages, Clock clock, MessagingTransactions transactions) {
        this.templates = templates; this.provider = provider; this.configs = configs; this.events = events; this.audit = audit; this.audits = audits;
        this.clock = clock; this.samples = new TemplateSampleData(messages); this.transactions = transactions;
    }

    /** The texts of a save: `{locale: text}` maps (blank entries are no translation). */
    public record Texts(Map<String, String> title, Map<String, String> body, Map<String, String> smsBody) { }
    public record Create(NotificationCategory category, Texts texts, TemplateIcon icon, TemplateColor color,
            Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix) { }
    public record Update(Texts texts, TemplateIcon icon, TemplateColor color, Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix,
            boolean enabled, long version, NotificationCategory category) { }
    /** A template with what D9 shows next to it: the rules of its code and its last audited change. */
    public record View(MessageTemplate template, List<String> variables, Map<NotificationAudience, Set<NotificationChannel>> caps, Set<NotificationAudience> push,
            AuditQuery.LastChange lastChange, MessageTemplate seed) { }
    public record Listing(List<View> items, Map<NotificationCategory, Integer> countsByCategory) { }

    public ClubConfig config() { return configs.get(TenantContext.require()); }

    /** `GET /message-templates` in D9's order: category, then the catalog's codes in the catalog's order, then the `CUSTOM` ones by title. */
    public Listing list(NotificationCategory category, TemplateKind kind, boolean includeArchived) {
        var config = config();
        provider.ensureAll(config.club().locales(), config.club().defaultLocale());
        var all = templates.findAll();
        var counts = new EnumMap<NotificationCategory, Integer>(NotificationCategory.class);
        CATEGORIES.forEach(c -> counts.put(c, 0));
        all.stream().filter(t -> t.status() != TemplateStatus.ARCHIVED).forEach(t -> counts.merge(t.category(), 1, Integer::sum));
        var codes = NotificationCatalog.codes();
        var items = all.stream().filter(t -> includeArchived || t.status() != TemplateStatus.ARCHIVED)
                .filter(t -> category == null || t.category() == category).filter(t -> kind == null || t.kind() == kind)
                .filter(t -> t.code() == null || NotificationCatalog.byCode(t.code()).isPresent())
                .sorted(Comparator.comparingInt((MessageTemplate t) -> CATEGORIES.indexOf(t.category()))
                        .thenComparing(t -> t.kind() == TemplateKind.CATALOG ? 0 : 1)
                        .thenComparingInt(t -> t.code() == null ? Integer.MAX_VALUE : codes.indexOf(t.code()))
                        .thenComparing(t -> title(t, config)).thenComparing(MessageTemplate::id))
                .map(t -> view(t, config, false)).toList();
        return new Listing(items, counts);
    }

    /** One template of the club; another club's, an unknown or an archived one is 404. */
    public View get(String id) { return view(find(id), config(), true); }

    public View create(Create request) { var config = config(); return view(transactions.write(() -> insert(request, config)), config, true); }
    private MessageTemplate insert(Create request, ClubConfig config) {
        if (request.category() == null || !CUSTOM_CATEGORIES.contains(request.category())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "category"));
        }
        var texts = clean(request.texts());
        var matrix = matrix(request.matrix(), null, TemplateKind.CUSTOM, null, request.category(), config);
        validate(rules(TemplateKind.CUSTOM, null, request.category()), texts, matrix, true, config);
        var now = clock.instant(); String actor = actor();
        var created = templates.insert(new MessageTemplate(java.util.UUID.randomUUID().toString(), config.club().id(), null, TemplateKind.CUSTOM, request.category(),
                text(texts.title(), config), text(texts.body(), config), texts.smsBody().isEmpty() ? null : text(texts.smsBody(), config), request.icon(),
                request.color(), matrix, true, false, false, TemplateStatus.ACTIVE, 0L, now, actor, now, actor));
        changed(null, created);
        return created;
    }

    public View update(String id, Update request) { var config = config(); return view(transactions.write(() -> save(id, request, config)), config, true); }
    private MessageTemplate save(String id, Update request, ClubConfig config) {
        var current = find(id);
        if (request.version() != current.version()) { throw new ApiException(ErrorCode.STALE_VERSION); }
        if (current.kind() == TemplateKind.CATALOG && request.category() != null && request.category() != current.category()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "category"));
        }
        var category = current.kind() == TemplateKind.CUSTOM && request.category() != null ? request.category() : current.category();
        if (current.kind() == TemplateKind.CUSTOM && !CUSTOM_CATEGORIES.contains(category)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "category"));
        }
        var texts = clean(request.texts());
        var matrix = matrix(request.matrix(), current.matrix(), current.kind(), current.code(), category, config);
        validate(rules(current.kind(), current.code(), category), texts, matrix, request.enabled(), config);
        var title = text(texts.title(), config); var body = text(texts.body(), config);
        var sms = texts.smsBody().isEmpty() ? null : text(texts.smsBody(), config);
        boolean customized = current.kind() == TemplateKind.CATALOG && differs(seed(current.code(), config), title, body, sms);
        var next = new MessageTemplate(current.id(), current.clubId(), current.code(), current.kind(), category, title, body, sms, request.icon(), request.color(),
                matrix, request.enabled(), current.mandatory(), customized, request.enabled() ? TemplateStatus.ACTIVE : TemplateStatus.DISABLED, current.version(),
                current.createdAt(), current.createdBy(), clock.instant(), actor());
        var saved = templates.update(next, current.version());
        changed(current, saved);
        return saved;
    }

    /** «Restaura el text per defecte» (R-11-12): the seed's texts, icon, colour and matrix back and `customized = false`; `enabled` stays. */
    public View reset(String id) { var config = config(); return view(transactions.write(() -> restore(id, config)), config, true); }
    private MessageTemplate restore(String id, ClubConfig config) {
        var current = find(id);
        if (current.kind() != TemplateKind.CATALOG) { throw new ApiException(ErrorCode.TEMPLATE_NOT_CATALOG); }
        var seed = seed(current.code(), config);
        var next = new MessageTemplate(current.id(), current.clubId(), current.code(), current.kind(), current.category(), seed.title(), seed.body(), seed.smsBody(),
                seed.icon(), seed.color(), seed.matrix(), current.enabled(), current.mandatory(), false, current.status(), current.version(), current.createdAt(),
                current.createdBy(), clock.instant(), actor());
        var saved = templates.update(next, current.version());
        changed(current, saved);
        return saved;
    }

    /** «Elimina»: a `CUSTOM` template becomes `ARCHIVED` (invisible in D9 and in the send dialog); a catalog one → `TEMPLATE_NOT_CUSTOM`. */
    public void archive(String id) { transactions.run(() -> archived(id)); }
    private void archived(String id) {
        var current = find(id);
        if (current.kind() != TemplateKind.CUSTOM) { throw new ApiException(ErrorCode.TEMPLATE_NOT_CUSTOM); }
        var next = new MessageTemplate(current.id(), current.clubId(), current.code(), current.kind(), current.category(), current.title(), current.body(),
                current.smsBody(), current.icon(), current.color(), current.matrix(), current.enabled(), current.mandatory(), current.customized(),
                TemplateStatus.ARCHIVED, current.version(), current.createdAt(), current.createdBy(), clock.instant(), actor());
        changed(current, templates.update(next, current.version()));
    }

    // ---- rules

    /** The validation rules of a template of this kind, code and category (R-11-12). */
    TemplateValidator.Rules rules(TemplateKind kind, String code, NotificationCategory category) {
        if (kind == TemplateKind.CUSTOM) {
            var caps = new EnumMap<NotificationAudience, Set<NotificationChannel>>(NotificationAudience.class);
            for (var audience : List.of(NotificationAudience.MEMBER, NotificationAudience.INSTRUCTORS, NotificationAudience.ADMINS)) {
                caps.put(audience, NotificationCatalog.customCaps(category, audience));
            }
            var variables = NotificationCatalog.customTemplateVariables();
            return new TemplateValidator.Rules(variables, variables, Set.of(), caps, category == NotificationCategory.CLUB_CHANGES, false);
        }
        var spec = spec(code);
        var caps = new EnumMap<NotificationAudience, Set<NotificationChannel>>(NotificationAudience.class);
        for (var audience : spec.audiences()) { if (audience.templated()) { caps.put(audience, spec.caps(audience)); } }
        return new TemplateValidator.Rules(NotificationCatalog.templateVariables(spec), seedArguments(code), NotificationCatalog.templateRequiredVariables(spec), caps,
                MessageTemplateSeed.smsCapable(spec), spec.mandatory());
    }
    /**
     * The selectors of the code's seed in every language: the names of its `select` and `plural` arguments (`has_upfront`,
     * `active`, `change`…), which choose a branch and are no `[[var]]` of their own. A simple `{var}` of the seed is never
     * one (E7-T06, AGENTS rule 2): a template may print only its row's variables ({@link NotificationCatalog#templateVariables}).
     */
    Set<String> seedArguments(String code) {
        var names = new LinkedHashSet<String>();
        provider.seeds().of(code).ifPresent(seed -> {
            for (var texts : List.of(seed.title(), seed.body(), seed.smsBody())) {
                texts.forEach((locale, text) -> names.addAll(TemplateValidator.syntax(code, text).selectors()));
            }
        });
        return names;
    }
    private void validate(TemplateValidator.Rules rules, Texts texts, Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix, boolean enabled,
            ClubConfig config) {
        boolean transliterate = !Boolean.FALSE.equals(config.get("messaging.sms.transliterateToGsm7", Boolean.class));
        validator.validate(rules, new TemplateValidator.Draft(texts.title(), texts.body(), texts.smsBody(), matrix, enabled), config.club().locales(),
                config.club().defaultLocale(), locale -> samples.values(config, locale), transliterate);
    }

    /**
     * The matrix to store: the request's cells for the rows the template has (the code's audiences; `CUSTOM`: every row), `false`
     * where absent. With `SMS` off, the SMS cells are the stored ones (R-11-17: the column is hidden, its cells kept).
     */
    Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix(Map<NotificationAudience, Map<NotificationChannel, Boolean>> requested,
            Map<NotificationAudience, Map<NotificationChannel, Boolean>> stored, TemplateKind kind, String code, NotificationCategory category, ClubConfig config) {
        boolean sms = config.modules().contains(Module.SMS);
        var rows = new ArrayList<NotificationAudience>();
        if (kind == TemplateKind.CUSTOM) { rows.addAll(List.of(NotificationAudience.MEMBER, NotificationAudience.INSTRUCTORS, NotificationAudience.ADMINS)); }
        else { spec(code).audiences().stream().filter(NotificationAudience::templated).forEach(rows::add); }
        var matrix = new EnumMap<NotificationAudience, Map<NotificationChannel, Boolean>>(NotificationAudience.class);
        var outside = new ArrayList<Map<String, Object>>();
        (requested == null ? Map.<NotificationAudience, Map<NotificationChannel, Boolean>>of() : requested).forEach((audience, row) -> {
            if (!rows.contains(audience) && row != null && row.values().stream().anyMatch(Boolean.TRUE::equals)) {
                row.forEach((channel, on) -> { if (Boolean.TRUE.equals(on)) { outside.add(Map.of("audience", audience.name(), "channel", channel.name())); } });
            }
        });
        if (!outside.isEmpty()) { throw TemplateValidator.channelNotAllowed(outside); }
        for (var audience : rows) {
            var cells = new EnumMap<NotificationChannel, Boolean>(NotificationChannel.class);
            var row = requested == null ? null : requested.get(audience);
            for (var channel : List.of(NotificationChannel.APP, NotificationChannel.EMAIL, NotificationChannel.SMS)) {
                boolean on = row != null && Boolean.TRUE.equals(row.get(channel));
                if (channel == NotificationChannel.SMS && !sms) {
                    on = stored != null && stored.get(audience) != null && Boolean.TRUE.equals(stored.get(audience).get(NotificationChannel.SMS));
                }
                cells.put(channel, on);
            }
            matrix.put(audience, cells);
        }
        return matrix;
    }

    // ---- views

    private View view(MessageTemplate stored, ClubConfig config, boolean detail) {
        var template = normalized(stored, config);
        var caps = new EnumMap<NotificationAudience, Set<NotificationChannel>>(NotificationAudience.class);
        rules(template.kind(), template.code(), template.category()).caps().forEach((audience, channels) -> {
            var visible = EnumSet.noneOf(NotificationChannel.class); visible.addAll(channels);
            if (!config.modules().contains(Module.SMS)) { visible.remove(NotificationChannel.SMS); }
            caps.put(audience, visible);
        });
        var push = EnumSet.noneOf(NotificationAudience.class);
        if (config.modules().contains(Module.PUSH)) {
            push.addAll(template.kind() == TemplateKind.CATALOG ? spec(template.code()).push() : spec(NotificationCatalog.ANNOUNCEMENT).push());
        }
        var variables = template.kind() == TemplateKind.CATALOG ? NotificationCatalog.templateVariables(spec(template.code())) : NotificationCatalog.customTemplateVariables();
        var seed = detail && template.kind() == TemplateKind.CATALOG ? seed(template.code(), config) : null;
        return new View(template, variables, caps, push, audits.lastChange(ENTITY, template.id()), seed);
    }
    private MessageTemplate seed(String code, ClubConfig config) {
        return provider.seed(spec(code), config.club().locales(), config.club().defaultLocale());
    }
    /**
     * The template as D9 shows it (E7-T03 round 2): its texts in the club's languages only. A template stored before clubs kept
     * only their own languages (E7-T02's first use stored every product language) shows the club's ones, edited or not, and
     * leaves the others out, so D9's GET → edit → PUT sends only the club's languages and saves them; nothing the club can edit
     * is lost. A text with none of the club's languages is shown as stored. Nothing is written here: the next save stores it.
     */
    static MessageTemplate normalized(MessageTemplate template, ClubConfig config) {
        var title = inClubLocales(template.title(), config); var body = inClubLocales(template.body(), config);
        var sms = inClubLocales(template.smsBody(), config);
        if (title == template.title() && body == template.body() && sms == template.smsBody()) { return template; }
        return new MessageTemplate(template.id(), template.clubId(), template.code(), template.kind(), template.category(), title, body, sms, template.icon(),
                template.color(), template.matrix(), template.enabled(), template.mandatory(), template.customized(), template.status(), template.version(),
                template.createdAt(), template.createdBy(), template.updatedAt(), template.updatedBy());
    }
    private static LocalizedText inClubLocales(LocalizedText text, ClubConfig config) {
        if (text == null) { return null; }
        var kept = new LinkedHashMap<String, String>();
        for (String locale : config.club().locales()) { if (text.values().containsKey(locale)) { kept.put(locale, text.values().get(locale)); } }
        if (kept.isEmpty() || kept.size() == text.values().size()) { return text; }
        return new LocalizedText(kept, kept.containsKey(config.club().defaultLocale()) ? config.club().defaultLocale() : kept.keySet().iterator().next());
    }
    static boolean differs(MessageTemplate seed, LocalizedText title, LocalizedText body, LocalizedText sms) {
        return !values(seed.title()).equals(values(title)) || !values(seed.body()).equals(values(body)) || !values(seed.smsBody()).equals(values(sms));
    }
    private static Map<String, String> values(LocalizedText text) { return text == null ? Map.of() : text.values(); }

    // ---- helpers

    MessageTemplate find(String id) {
        var template = templates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (template.status() == TemplateStatus.ARCHIVED) { throw new ApiException(ErrorCode.NOT_FOUND); }
        if (template.code() != null && NotificationCatalog.byCode(template.code()).isEmpty()) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return template;
    }
    static NotificationSpec spec(String code) { return NotificationCatalog.byCode(code).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    private static String title(MessageTemplate template, ClubConfig config) {
        return template.title().withDefaultLocale(config.club().defaultLocale()).resolve(Locale.ROOT).value();
    }
    /**
     * Blank translations dropped, the rest stripped, and the keys of every `{gender, select, …}` in lower case (E7-T06 round 2
     * review #1, ruling E83; S11 §10): the engine gives `gender` in lower case, so `FEMALE {…}` is stored `female {…}`, with
     * the function the start-up upgrade uses ({@link TemplateUpgrade#lowerGenderKeys}), which then finds nothing to correct.
     */
    static Texts clean(Texts texts) {
        return new Texts(clean(texts.title()), clean(texts.body()), clean(texts.smsBody()));
    }
    private static Map<String, String> clean(Map<String, String> values) {
        var kept = new LinkedHashMap<String, String>();
        if (values != null) {
            values.forEach((locale, text) -> { if (locale != null && text != null && !text.isBlank()) { kept.put(locale, TemplateUpgrade.lowerGenderKeys(text.strip())); } });
        }
        return kept;
    }
    private static LocalizedText text(Map<String, String> values, ClubConfig config) {
        var ordered = new LinkedHashMap<String, String>();
        for (String locale : config.club().locales()) { if (values.containsKey(locale)) { ordered.put(locale, values.get(locale)); } }
        return new LocalizedText(ordered, config.club().defaultLocale());
    }
    private static String actor() {
        var user = CurrentUser.current();
        return user == null ? SYSTEM_ACTOR : user.accountId();
    }

    /** `MessageTemplateChanged{id, diff}` and `CATALOG_CHANGED` with the same before/after, in the caller's transaction. */
    private void changed(MessageTemplate before, MessageTemplate after) {
        var from = before == null ? null : snapshot(before);
        var to = snapshot(after);
        var diff = TemplateDiff.between(from, to);
        if (diff.isEmpty()) { return; }
        var user = CurrentUser.current();
        var payload = new LinkedHashMap<String, Object>(); payload.put("id", after.id()); payload.put("diff", diff);
        events.publish(new MessagingEvent(MessagingEvent.Kind.MessageTemplateChanged, after.clubId(), after.id(), clock.instant(), payload,
                user == null ? null : user.accountId(), user == null || user.impersonation() == null ? null : user.impersonation().memberId(),
                user == null ? DomainEvent.Origin.SYSTEM : Objects.requireNonNullElse(user.origin(), DomainEvent.Origin.BACKOFFICE)));
        audit.write(new AuditCommand(AuditAction.CATALOG_CHANGED, ENTITY, after.id(), null, from, to, null));
    }
    static Map<String, Object> snapshot(MessageTemplate template) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("category", template.category().name());
        snapshot.put("title", values(template.title())); snapshot.put("body", values(template.body())); snapshot.put("smsBody", values(template.smsBody()));
        snapshot.put("icon", template.icon().name()); snapshot.put("color", template.color().name());
        var matrix = new LinkedHashMap<String, Object>();
        template.matrix().forEach((audience, row) -> {
            var cells = new LinkedHashMap<String, Object>(); row.forEach((channel, on) -> cells.put(channel.name(), on));
            matrix.put(audience.name(), cells);
        });
        snapshot.put("matrix", matrix);
        snapshot.put("enabled", template.enabled()); snapshot.put("status", template.status().name()); snapshot.put("customized", template.customized());
        return snapshot;
    }
}

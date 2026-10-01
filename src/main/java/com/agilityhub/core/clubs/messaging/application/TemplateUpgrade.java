package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateValidator;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * E7-T06 (E7-T03's round-2 reviews; round 2, ruling E81): the club templates stored before E7-T03's round 2 are brought up to
 * date at the API's start-up, before the web server and the schedulers start (`SmartInitializingSingleton`); a `bin/core`
 * command (`core.command` set) skips it, so `club:apply --dry-run` still writes nothing. Two older kinds of template exist:
 * E7-T02's first use copied `messages_*` (ICU `{var}` arguments, `FEMALE`/`MALE` select keys that no longer match the
 * lower-case `gender` of S11 §10, every product language), and E7-T03's round 1 stored N-02 with «Entra-hi amb aquest
 * enllaç: [[link]].», whose `link` is its welcome e-mail's only since E76; E7-T06 also took `{class_description}` out of
 * N-08b's seed (it is no N-08b variable).
 * <ul>
 * <li>A catalog template the club never edited (`customized = false`) follows the current seed: the product's text (E81), in
 * the club's languages and in any other language it stores (a language is never dropped); its matrix, icon, colour and state
 * stay the club's.</li>
 * <li>An edited catalog template, and a `CUSTOM` one, keep every stored value, in every stored language, with only the
 * corrections applied ({@link #corrected}): the `gender` select keys in lower case, N-02 without the sentence of its link,
 * N-08b without `{class_description}`. A language the club removed keeps its words (D9 shows and saves the club's languages;
 * its next save drops the others). A correction that would stop the text parsing is not applied.</li>
 * </ul>
 * A template is written only when a text changes, conditionally on the version read (a D9 save in between wins; the template
 * is read again and upgraded from there), with `MessageTemplateChanged{id, diff}` and the `CATALOG_CHANGED` audit in the same
 * transaction, both by the actor {@value #ACTOR} (S11 §4: D9's «last change» names it). Idempotent: an up-to-date template is
 * never written. A template, or a whole club, that fails is logged (ids only) and counted, and the others go on: the API always
 * starts. Nothing brings the old texts back: the lazy seed, `club:apply` and «Restaura el text per defecte» store the current seed.
 */
@Service
public class TemplateUpgrade implements SmartInitializingSingleton {
    private static final Logger LOG = LoggerFactory.getLogger(TemplateUpgrade.class);
    static final String ACTOR = "system:template-upgrade";
    static final int ATTEMPTS = 3;
    /** The `gender` select of a text: its keys follow (S11 §10: `female`, `male`, `other`). */
    private static final Pattern GENDER_SELECT = Pattern.compile("\\{\\s*gender\\s*,\\s*select\\s*,");
    private static final Set<String> GENDER_KEYS = Set.of("female", "male", "other");
    /**
     * N-02's link sentences the product shipped: E7-T02's `messages_*` copy (ca, es, en) and E7-T03's round-1 seed. Any other
     * sentence of N-02 with the link variable goes too ({@link #LINK}).
     */
    static final List<String> LINK_SENTENCES = List.of("Entra al teu compte amb aquest enllaç.", "Accede a tu cuenta con este enlace.",
            "Use the link to access your account.", "Entra-hi amb aquest enllaç: [[link]].", "Entra con este enlace: [[link]].", "Sign in with this link: [[link]].");
    private static final Pattern LINK = Pattern.compile("\\[\\[\\s*link\\s*]]|\\{\\s*link\\s*}");
    /**
     * N-08b's `{class_description}`, which E7-T03's seed printed («[[class_date]] · {class_description}, amb…»): with the
     * seed's « · » before it, inside brackets, or alone; each form takes the space before it.
     */
    private static final Pattern CLASS_DESCRIPTION = Pattern.compile("[ \\t]*·[ \\t]*\\{\\s*class_description\\s*}|[ \\t]*\\(\\{\\s*class_description\\s*}\\)"
            + "|[ \\t]?\\{\\s*class_description\\s*}");

    private final MessageTemplateRepository templates; private final TemplateProvider provider; private final ClubConfigService configs;
    private final MessagingTransactions transactions; private final EventPublisher events; private final AuditWriter audit; private final Clock clock;
    private final boolean command;

    public TemplateUpgrade(MessageTemplateRepository templates, TemplateProvider provider, ClubConfigService configs, MessagingTransactions transactions,
            EventPublisher events, AuditWriter audit, Clock clock, @Value("${core.command:}") String command) {
        this.templates = templates; this.provider = provider; this.configs = configs; this.transactions = transactions; this.events = events; this.audit = audit;
        this.clock = clock; this.command = command != null && !command.isBlank();
    }

    /**
     * What one run changed: templates that took the seed, edited or custom ones corrected, templates it could not write, and
     * clubs it could not read or upgrade (each logged; the next start-up tries them again).
     */
    public record Result(int seeded, int corrected, int failed, int failedClubs) {
        Result plus(Result other) {
            return new Result(seeded + other.seeded, corrected + other.corrected, failed + other.failed, failedClubs + other.failedClubs);
        }
        int total() { return seeded + corrected + failed + failedClubs; }
    }
    private enum Outcome { NONE, SEEDED, CORRECTED }

    /** The start-up run: every club that holds a template; nothing in a `bin/core` command. It never stops the start-up. */
    @Override public void afterSingletonsInstantiated() {
        if (command) { return; }
        Result result;
        try { result = upgradeAll(); }
        catch (RuntimeException failure) {
            // The clubs could not even be listed: the API starts with the templates as stored, the next start-up tries again.
            LOG.warn("Message templates not brought up to date error={}", failure.getClass().getSimpleName());
            return;
        }
        if (result.total() > 0) {
            LOG.info("Message templates brought up to date: {} took the current seed, {} edited ones corrected, {} failed, {} clubs failed", result.seeded(),
                    result.corrected(), result.failed(), result.failedClubs());
        }
    }

    /** Every club with templates, one tenant at a time; a club that no longer exists is skipped, one that fails is counted. */
    public Result upgradeAll() {
        var total = new Result(0, 0, 0, 0);
        for (String clubId : templates.clubIds()) {
            try (var tenant = TenantContext.open(clubId)) { total = total.plus(upgrade(configs.get(clubId))); }
            catch (RuntimeException failure) {
                if (failure instanceof ApiException missing && missing.code() == ErrorCode.CLUB_NOT_FOUND) { continue; } // a deleted club's templates are nobody's
                total = total.plus(new Result(0, 0, 0, 1));
                LOG.warn("Message templates of a club not brought up to date club={} error={}", clubId, failure.getClass().getSimpleName());
            }
        }
        return total;
    }

    /** The current club's templates (the tenant is open). A template that cannot be written never stops the others. */
    Result upgrade(ClubConfig config) {
        int seeded = 0, corrected = 0, failed = 0;
        for (var stored : templates.findAll()) {
            try {
                switch (upgradeOne(stored, config)) {
                    case SEEDED -> seeded++;
                    case CORRECTED -> corrected++;
                    case NONE -> { }
                }
            } catch (RuntimeException failure) {
                failed++;
                LOG.warn("Message template not brought up to date club={} template={} error={}", stored.clubId(), stored.id(), failure.getClass().getSimpleName());
            }
        }
        return new Result(seeded, corrected, failed, 0);
    }
    private Outcome upgradeOne(MessageTemplate stored, ClubConfig config) {
        var current = stored;
        for (int attempt = 1; ; attempt++) {
            var next = upgraded(current, seed(current, config));
            if (next == current) { return Outcome.NONE; }
            var before = current;
            try {
                transactions.write(() -> {
                    var saved = templates.update(stamped(next), Objects.requireNonNullElse(before.version(), 0L));
                    changed(before, saved);
                    return saved;
                });
                return before.kind() == TemplateKind.CATALOG && !before.customized() ? Outcome.SEEDED : Outcome.CORRECTED;
            } catch (ApiException stale) {
                // A D9 save (or another instance) got there first: read it again and upgrade what it saved.
                if (stale.code() != ErrorCode.STALE_VERSION || attempt >= ATTEMPTS) { throw stale; }
                current = templates.findById(current.id()).orElse(null);
                if (current == null) { return Outcome.NONE; }
            }
        }
    }

    /**
     * The code's product seed in the club's languages and in every other language the template stores (null for a `CUSTOM`
     * template or a code without seed): the text a never-edited template takes, and an emptied text's fallback.
     */
    private MessageTemplate seed(MessageTemplate template, ClubConfig config) {
        if (template.kind() != TemplateKind.CATALOG) { return null; }
        var languages = new LinkedHashSet<String>(config.club().locales());
        for (var text : List.of(values(template.title()), values(template.body()), values(template.smsBody()))) { languages.addAll(text.keySet()); }
        return MessageTemplateSeed.eligible().stream().filter(spec -> spec.code().equals(template.code())).findFirst()
                .map(spec -> provider.seed(spec, languages, config.club().defaultLocale())).orElse(null);
    }
    private MessageTemplate stamped(MessageTemplate template) {
        return new MessageTemplate(template.id(), template.clubId(), template.code(), template.kind(), template.category(), template.title(), template.body(),
                template.smsBody(), template.icon(), template.color(), template.matrix(), template.enabled(), template.mandatory(), template.customized(),
                template.status(), template.version(), template.createdAt(), template.createdBy(), clock.instant(), ACTOR);
    }
    /** S11 §4 (E81): `MessageTemplateChanged{id, diff}` and `CATALOG_CHANGED`, the same before/after as a D9 save, by {@value #ACTOR}. */
    private void changed(MessageTemplate before, MessageTemplate after) {
        var from = MessageTemplateService.snapshot(before); var to = MessageTemplateService.snapshot(after);
        var payload = new LinkedHashMap<String, Object>(); payload.put("id", after.id()); payload.put("diff", TemplateDiff.between(from, to));
        events.publish(new MessagingEvent(MessagingEvent.Kind.MessageTemplateChanged, after.clubId(), after.id(), clock.instant(), payload, ACTOR, null,
                DomainEvent.Origin.SYSTEM));
        audit.writeAsSystem(new AuditCommand(AuditAction.CATALOG_CHANGED, MessageTemplateService.ENTITY, after.id(), null, from, to, null), ACTOR);
    }

    /**
     * The template brought up to date, or the same object when it already is. `seed` is the code's product seed in the
     * template's languages ({@link #seed}; null for a `CUSTOM` template or a code without one). An archived template is left
     * as it is. `customized` stays true unless every text equals the seed in every language (D9's own rule).
     */
    static MessageTemplate upgraded(MessageTemplate stored, MessageTemplate seed) {
        if (stored.status() == TemplateStatus.ARCHIVED || stored.kind() == TemplateKind.CATALOG && seed == null) { return stored; }
        LocalizedText title, body, sms;
        if (stored.kind() == TemplateKind.CATALOG && !stored.customized()) {
            title = seed.title(); body = seed.body(); sms = seed.smsBody();
        } else {
            title = corrected(stored.code(), stored.title(), seed == null ? null : seed.title());
            body = corrected(stored.code(), stored.body(), seed == null ? null : seed.body());
            sms = corrected(stored.code(), stored.smsBody(), seed == null ? null : seed.smsBody());
        }
        if (values(title).equals(values(stored.title())) && values(body).equals(values(stored.body())) && values(sms).equals(values(stored.smsBody()))) {
            return stored;
        }
        boolean customized = stored.kind() == TemplateKind.CATALOG && MessageTemplateService.differs(seed, title, body, sms);
        return new MessageTemplate(stored.id(), stored.clubId(), stored.code(), stored.kind(), stored.category(), title, body, sms, stored.icon(), stored.color(),
                stored.matrix(), stored.enabled(), stored.mandatory(), customized, stored.status(), stored.version(), stored.createdAt(), stored.createdBy(),
                stored.updatedAt(), stored.updatedBy());
    }
    /**
     * Every stored language corrected, none added or dropped; one left blank takes the seed's text of that language (a body is
     * required), or keeps its text when the product has none.
     */
    private static LocalizedText corrected(String code, LocalizedText text, LocalizedText seed) {
        if (text == null) { return null; }
        var values = new LinkedHashMap<String, String>();
        text.values().forEach((locale, value) -> {
            String fixed = value == null ? null : corrected(code, value);
            if (fixed != null && fixed.isBlank()) { fixed = seed != null && seed.values().get(locale) != null ? seed.values().get(locale) : value; }
            values.put(locale, fixed);
        });
        return new LocalizedText(values, text.defaultLocale());
    }
    /** One stored text of `code` with the corrections of E7-T06 (E81) only: `gender` keys, N-02's link sentence, N-08b's `{class_description}`. */
    static String corrected(String code, String text) {
        String fixed = lowerGenderKeys(text);
        if ("N-02".equals(code)) { fixed = withoutLinkSentence(fixed); }
        if ("N-08b".equals(code)) { fixed = withoutClassDescription(fixed); }
        return fixed;
    }
    private static Map<String, String> values(LocalizedText text) { return text == null ? Map.of() : text.values(); }

    /**
     * The keys of every `{gender, select, …}` of a text in lower case (`FEMALE {Benvinguda}` → `female {Benvinguda}`): the
     * engine gives `gender` in lower case (S11 §10), so an upper-case key never matched. A text that would no longer parse
     * is returned as it was.
     */
    static String lowerGenderKeys(String text) {
        if (text == null) { return null; }
        var chars = text.toCharArray();
        var select = GENDER_SELECT.matcher(text);
        while (select.find()) {
            int i = select.end();
            while (i < chars.length) {
                while (i < chars.length && Character.isWhitespace(chars[i])) { i++; }
                if (i >= chars.length || chars[i] == '}') { break; }
                int start = i;
                while (i < chars.length && !Character.isWhitespace(chars[i]) && chars[i] != '{' && chars[i] != '}') { i++; }
                String key = text.substring(start, i), lower = key.toLowerCase(Locale.ROOT);
                if (GENDER_KEYS.contains(lower) && !key.equals(lower)) { lower.getChars(0, lower.length(), chars, start); }
                while (i < chars.length && Character.isWhitespace(chars[i])) { i++; }
                if (i >= chars.length || chars[i] != '{') { break; }
                int depth = 0;
                do { if (chars[i] == '{') { depth++; } else if (chars[i] == '}') { depth--; } i++; } while (i < chars.length && depth > 0);
            }
        }
        String lowered = new String(chars);
        return lowered.equals(text) || !parses(lowered) ? text : lowered;
    }

    /**
     * N-02's text without the sentence of its link (E76: the reader of the feed is signed in; the welcome e-mail carries the
     * S01 link): the sentences the product shipped ({@link #LINK_SENTENCES}), then any sentence with `[[link]]` or `{link}`.
     */
    static String withoutLinkSentence(String text) {
        if (text == null) { return null; }
        String out = text;
        for (String sentence : LINK_SENTENCES) { out = out.replace(sentence, ""); }
        for (var link = LINK.matcher(out); link.find(); link = LINK.matcher(out)) {
            out = out.substring(0, sentenceStart(out, link.start())) + out.substring(sentenceEnd(out, link.end()));
        }
        return tidy(text, out);
    }
    /**
     * N-08b's text without `{class_description}` (E7-T06 step 5: it is no N-08b variable, S06 §8; an edited N-08b that kept
     * it from E7-T03's seed rendered it empty and could not be saved unchanged): E7-T03's «[[class_date]] · {class_description},
     * amb…» becomes the current seed's «[[class_date]], amb…».
     */
    static String withoutClassDescription(String text) {
        if (text == null) { return null; }
        return tidy(text, CLASS_DESCRIPTION.matcher(text).replaceAll(""));
    }
    /** A removal's result with its doubled spaces joined and its ends stripped, or the text as it was when nothing went or it would not parse. */
    private static String tidy(String text, String out) {
        if (out.equals(text)) { return text; }
        String tidy = out.replaceAll("[ \\t]{2,}", " ").strip();
        return parses(tidy) ? tidy : text;
    }
    private static int sentenceStart(String text, int at) {
        for (int i = at - 1; i >= 0; i--) { if (".!?\n".indexOf(text.charAt(i)) >= 0) { return i + 1; } }
        return 0;
    }
    private static int sentenceEnd(String text, int at) {
        for (int i = at; i < text.length(); i++) {
            if (".!?".indexOf(text.charAt(i)) >= 0) { return i + 1; }
            if (text.charAt(i) == '\n') { return i; }
        }
        return text.length();
    }
    private static boolean parses(String text) {
        try { TemplateValidator.syntax("upgrade", text); return true; }
        catch (ApiException unparsable) { return false; }
    }
}

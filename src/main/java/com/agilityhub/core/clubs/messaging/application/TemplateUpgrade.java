package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateProvider;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateValidator;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * E7-T06 step 1 (E7-T03's round-2 reviews): the club templates stored before E7-T03's round 2 are brought up to date at the
 * API's start-up, before the web server and the schedulers start (`SmartInitializingSingleton`); a `bin/core` command
 * (`core.command` set) skips it, so `club:apply --dry-run` still writes nothing. Two older kinds of template exist:
 * E7-T02's first use copied `messages_*` (ICU `{var}` arguments, `FEMALE`/`MALE` select keys that no longer match the
 * lower-case `gender` of S11 §10, every product language), and E7-T03's round 1 stored N-02 with «Entra-hi amb aquest
 * enllaç: [[link]].», whose `link` is its welcome e-mail's only since E76 (it rendered empty, and an unchanged save was
 * `TEMPLATE_UNKNOWN_VARIABLE`).
 * <ul>
 * <li>A catalog template the club never edited (`customized = false`) takes the current seed's texts in the club's languages
 * (the texts {@link TemplateProvider#seed} stores on first use); its matrix, icon, colour and state stay the club's.</li>
 * <li>An edited catalog template, and a `CUSTOM` one, keep the club's words in the club's languages (D9's
 * {@link MessageTemplateService#normalized}), with the `gender` select keys in lower case and, for N-02, without the sentence
 * of its link. A text that would stop parsing is kept as it was.</li>
 * </ul>
 * Idempotent: an up-to-date template is never written, and a write is conditional on the version read (a D9 save in between
 * wins; the template is read again and upgraded from there). Nothing brings the old texts back: the lazy seed,
 * `club:apply` and «Restaura el text per defecte» all store the current seed. It is a product data migration, like
 * `messaging:migrate-notifications`: no audit entry nor `MessageTemplateChanged`; `updatedBy` = {@value #ACTOR}.
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

    private final MessageTemplateRepository templates; private final TemplateProvider provider; private final ClubConfigService configs; private final Clock clock;
    private final boolean command;

    public TemplateUpgrade(MessageTemplateRepository templates, TemplateProvider provider, ClubConfigService configs, Clock clock,
            @Value("${core.command:}") String command) {
        this.templates = templates; this.provider = provider; this.configs = configs; this.clock = clock; this.command = command != null && !command.isBlank();
    }

    /**
     * What one run changed: templates that took the seed, edited or custom ones corrected, and the ones it could not write
     * (logged; the next start-up tries them again).
     */
    public record Result(int seeded, int corrected, int failed) {
        Result plus(Result other) { return new Result(seeded + other.seeded, corrected + other.corrected, failed + other.failed); }
    }
    private enum Outcome { NONE, SEEDED, CORRECTED }

    /** The start-up run: every club that holds a template; nothing in a `bin/core` command. */
    @Override public void afterSingletonsInstantiated() {
        if (command) { return; }
        var result = upgradeAll();
        if (result.seeded() + result.corrected() + result.failed() > 0) {
            LOG.info("Message templates brought up to date: {} took the current seed, {} edited ones corrected, {} failed", result.seeded(), result.corrected(),
                    result.failed());
        }
    }

    /** Every club with templates, one tenant at a time; a club that no longer exists is skipped. */
    public Result upgradeAll() {
        var total = new Result(0, 0, 0);
        for (String clubId : templates.clubIds()) {
            ClubConfig config;
            try { config = configs.get(clubId); }
            catch (ApiException missing) { if (missing.code() == ErrorCode.CLUB_NOT_FOUND) { continue; } throw missing; }
            try (var tenant = TenantContext.open(clubId)) { total = total.plus(upgrade(config)); }
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
        return new Result(seeded, corrected, failed);
    }
    private Outcome upgradeOne(MessageTemplate stored, ClubConfig config) {
        var current = stored;
        for (int attempt = 1; ; attempt++) {
            var next = upgraded(current, seed(current, config), config);
            if (next == current) { return Outcome.NONE; }
            try {
                templates.update(stamped(next), Objects.requireNonNullElse(current.version(), 0L));
                return current.kind() == TemplateKind.CATALOG && !current.customized() ? Outcome.SEEDED : Outcome.CORRECTED;
            } catch (ApiException stale) {
                // A D9 save (or another instance) got there first: read it again and upgrade what it saved.
                if (stale.code() != ErrorCode.STALE_VERSION || attempt >= ATTEMPTS) { throw stale; }
                current = templates.findById(current.id()).orElse(null);
                if (current == null) { return Outcome.NONE; }
            }
        }
    }

    private MessageTemplate seed(MessageTemplate template, ClubConfig config) {
        if (template.kind() != TemplateKind.CATALOG) { return null; }
        return MessageTemplateSeed.eligible().stream().filter(spec -> spec.code().equals(template.code())).findFirst()
                .map(spec -> provider.seed(spec, config.club().locales(), config.club().defaultLocale())).orElse(null);
    }
    private MessageTemplate stamped(MessageTemplate template) {
        return new MessageTemplate(template.id(), template.clubId(), template.code(), template.kind(), template.category(), template.title(), template.body(),
                template.smsBody(), template.icon(), template.color(), template.matrix(), template.enabled(), template.mandatory(), template.customized(),
                template.status(), template.version(), template.createdAt(), template.createdBy(), clock.instant(), ACTOR);
    }

    /**
     * The template brought up to date, or the same object when it already is. `seed` is the code's product seed in the
     * club's languages (null for a `CUSTOM` template or a code without one). An archived template is left as it is.
     */
    static MessageTemplate upgraded(MessageTemplate stored, MessageTemplate seed, ClubConfig config) {
        if (stored.status() == TemplateStatus.ARCHIVED || stored.kind() == TemplateKind.CATALOG && seed == null) { return stored; }
        LocalizedText title, body, sms;
        if (stored.kind() == TemplateKind.CATALOG && !stored.customized()) {
            title = seed.title(); body = seed.body(); sms = seed.smsBody();
        } else {
            var shown = MessageTemplateService.normalized(stored, config);
            boolean n02 = "N-02".equals(stored.code());
            UnaryOperator<String> fix = text -> n02 ? withoutLinkSentence(lowerGenderKeys(text)) : lowerGenderKeys(text);
            title = corrected(shown.title(), fix, seed == null ? null : seed.title());
            body = corrected(shown.body(), fix, seed == null ? null : seed.body());
            sms = corrected(shown.smsBody(), fix, seed == null ? null : seed.smsBody());
        }
        if (values(title).equals(values(stored.title())) && values(body).equals(values(stored.body())) && values(sms).equals(values(stored.smsBody()))) {
            return stored;
        }
        boolean customized = stored.kind() == TemplateKind.CATALOG && MessageTemplateService.differs(seed, title, body, sms);
        return new MessageTemplate(stored.id(), stored.clubId(), stored.code(), stored.kind(), stored.category(), title, body, sms, stored.icon(), stored.color(),
                stored.matrix(), stored.enabled(), stored.mandatory(), customized, stored.status(), stored.version(), stored.createdAt(), stored.createdBy(),
                stored.updatedAt(), stored.updatedBy());
    }
    /** Each language corrected; one that would be left blank takes the seed's text of that language (a body is required). */
    private static LocalizedText corrected(LocalizedText text, UnaryOperator<String> fix, LocalizedText seed) {
        if (text == null) { return null; }
        var values = new LinkedHashMap<String, String>();
        text.values().forEach((locale, value) -> {
            String fixed = value == null ? null : fix.apply(value);
            if (fixed != null && fixed.isBlank() && seed != null && seed.values().get(locale) != null) { fixed = seed.values().get(locale); }
            values.put(locale, fixed);
        });
        return new LocalizedText(values, text.defaultLocale());
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

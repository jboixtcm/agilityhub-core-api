package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * S11 §8 and R-11-01, the product seed of the club templates: `seed/message-templates.{ca,es,en}.json`, one entry per
 * `NotificationCatalog` code of stage `R1` whose category is not `SYSTEM` (SYSTEM codes never have a template), each with
 * `title`, `body`, `smsBody` (only on the codes that can send an SMS), `icon`, `color` and the default `matrix` of the
 * catalog's «Públic → canals per defecte» column. The `ca` texts of N-02, N-04, N-06, N-08a, N-09, N-13, N-15, N-16, N-19
 * and N-28 are S11 §8's; `es` and `en` translate the same content and are reviewed before go-live (`seeds/README.md`).
 * Staff copy (`notif.N-xx.staff.*`) is product copy in `messages_*`, never here. Read once; immutable; product defaults
 * only (no club literal). A file that disagrees with the catalog (a missing or extra code, another icon, colour or matrix,
 * an SMS text on a code without SMS) fails at start-up.
 */
public final class MessageTemplateSeed {
    public static final List<String> LOCALES = List.of("ca", "es", "en");
    static final String PATH = "/seed/message-templates.%s.json";

    /** One seed entry of one code in one locale. */
    public record Entry(String code, String title, String body, String smsBody, TemplateIcon icon, TemplateColor color,
            Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix) { }
    record File(String locale, List<Entry> templates) { }

    /** The seed texts of one code in every product language, the club-independent part of a `MessageTemplate`. */
    public record Seeded(String code, Map<String, String> title, Map<String, String> body, Map<String, String> smsBody, TemplateIcon icon, TemplateColor color,
            Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix) {
        /** The texts in the given locales only (a club stores its own languages, R-11-01); all of them when none matches. */
        public LocalizedText title(Collection<String> locales, String defaultLocale) { return localized(title, locales, defaultLocale); }
        public LocalizedText body(Collection<String> locales, String defaultLocale) { return localized(body, locales, defaultLocale); }
        public LocalizedText smsBody(Collection<String> locales, String defaultLocale) { return smsBody.isEmpty() ? null : localized(smsBody, locales, defaultLocale); }
        static LocalizedText localized(Map<String, String> values, Collection<String> locales, String defaultLocale) {
            var kept = new LinkedHashMap<String, String>();
            values.forEach((locale, text) -> { if (locales == null || locales.contains(locale)) { kept.put(locale, text); } });
            if (kept.isEmpty()) { kept.putAll(values); }
            String fallback = defaultLocale != null && kept.containsKey(defaultLocale) ? defaultLocale : kept.keySet().iterator().next();
            return new LocalizedText(kept, fallback);
        }
    }

    private final Map<String, Seeded> byCode;

    private MessageTemplateSeed(Map<String, Seeded> byCode) { this.byCode = Collections.unmodifiableMap(byCode); }

    /** Reads and checks the three files from the classpath. */
    public static MessageTemplateSeed load() { return load(new ObjectMapper()); }
    public static MessageTemplateSeed load(ObjectMapper mapper) { return load(mapper, path -> MessageTemplateSeed.class.getResourceAsStream(path)); }
    /** The same checks over other files (the tests' broken copies). */
    static MessageTemplateSeed load(ObjectMapper mapper, java.util.function.Function<String, java.io.InputStream> files) {
        var entries = new LinkedHashMap<String, Map<String, Entry>>();
        for (String locale : LOCALES) {
            try (var input = files.apply(PATH.formatted(locale))) {
                if (input == null) { throw new IllegalStateException("Missing " + PATH.formatted(locale)); }
                var file = mapper.readValue(input, File.class);
                if (!locale.equals(file.locale())) { throw new IllegalStateException(PATH.formatted(locale) + " declares " + file.locale()); }
                for (var entry : file.templates()) {
                    if (entries.computeIfAbsent(entry.code(), code -> new LinkedHashMap<>()).put(locale, entry) != null) {
                        throw new IllegalStateException("Duplicated " + entry.code() + " in " + locale);
                    }
                }
            } catch (IOException unreadable) { throw new UncheckedIOException(unreadable); }
        }
        var eligible = eligible().stream().map(NotificationSpec::code).toList();
        if (!new java.util.HashSet<>(eligible).equals(entries.keySet())) {
            throw new IllegalStateException("The template seed must hold exactly the R1 templated codes " + eligible + ", not " + entries.keySet());
        }
        var seeded = new LinkedHashMap<String, Seeded>();
        for (String code : eligible) {
            var spec = NotificationCatalog.byCode(code).orElseThrow();
            var perLocale = entries.get(code);
            var title = new LinkedHashMap<String, String>(); var body = new LinkedHashMap<String, String>(); var sms = new LinkedHashMap<String, String>();
            for (String locale : LOCALES) {
                var entry = perLocale.get(locale);
                if (entry == null) { throw new IllegalStateException(code + " has no " + locale + " seed"); }
                if (blank(entry.title()) || blank(entry.body())) { throw new IllegalStateException(code + " " + locale + ": title and body are required"); }
                if (entry.icon() != spec.icon() || entry.color() != spec.color()) { throw new IllegalStateException(code + " " + locale + ": icon/colour differ from the catalog"); }
                if (!matrix(entry.matrix()).equals(spec.defaultMatrix())) { throw new IllegalStateException(code + " " + locale + ": the matrix differs from the catalog"); }
                if (entry.smsBody() != null && !smsCapable(spec)) { throw new IllegalStateException(code + " cannot send SMS: no smsBody"); }
                title.put(locale, entry.title()); body.put(locale, entry.body());
                if (entry.smsBody() != null) { sms.put(locale, entry.smsBody()); }
            }
            if (!sms.isEmpty() && sms.size() != LOCALES.size()) { throw new IllegalStateException(code + ": smsBody in some locales only"); }
            seeded.put(code, new Seeded(code, Collections.unmodifiableMap(title), Collections.unmodifiableMap(body), Collections.unmodifiableMap(sms),
                    spec.icon(), spec.color(), spec.defaultMatrix()));
        }
        return new MessageTemplateSeed(seeded);
    }

    /** The codes that get a template (R-11-01): stage `R1` and a category other than `SYSTEM`, in the catalog's order. */
    public static List<NotificationSpec> eligible() {
        return NotificationCatalog.specs().stream().filter(spec -> spec.stage() == NotificationSpec.Stage.R1 && spec.templated()).toList();
    }
    /**
     * Whether a template of the code may carry an SMS text: a cap of one of its audiences allows SMS (R-11-12), or the code
     * has the Annex A `CLUB_CHANGES` variant from the back office (N-32b, whose variant's caps are its channels).
     */
    public static boolean smsCapable(NotificationSpec spec) {
        return spec.audiences().stream().anyMatch(audience -> spec.caps(audience).contains(NotificationChannel.SMS)) || "N-32b".equals(spec.code());
    }

    public Optional<Seeded> of(String code) { return Optional.ofNullable(byCode.get(code)); }
    public Collection<Seeded> all() { return byCode.values(); }
    public int size() { return byCode.size(); }

    private static Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix(Map<NotificationAudience, Map<NotificationChannel, Boolean>> source) {
        var copy = new EnumMap<NotificationAudience, Map<NotificationChannel, Boolean>>(NotificationAudience.class);
        if (source != null) {
            source.forEach((audience, row) -> {
                var cells = new EnumMap<NotificationChannel, Boolean>(NotificationChannel.class); cells.putAll(Objects.requireNonNull(row));
                copy.put(audience, cells);
            });
        }
        return copy;
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
}

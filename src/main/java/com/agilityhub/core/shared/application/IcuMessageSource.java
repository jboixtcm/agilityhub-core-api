package com.agilityhub.core.shared.application;

import com.ibm.icu.text.MessageFormat;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** UTF-8 bundles with ICU named arguments; formatters are never shared across threads. */
public final class IcuMessageSource implements MessageSource {
    private final Map<Locale, Properties> bundles;

    public IcuMessageSource() throws IOException { this("classpath*:messages/messages_*.properties"); }

    public IcuMessageSource(String resourcePattern) throws IOException {
        var loaded = new LinkedHashMap<Locale, Properties>();
        for (var resource : new PathMatchingResourcePatternResolver()
                .getResources(resourcePattern)) {
            String filename = resource.getFilename();
            var locale = Locale.forLanguageTag(filename.substring("messages_".length(),
                    filename.length() - ".properties".length()).replace('_', '-'));
            var properties = new Properties();
            try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            loaded.computeIfAbsent(locale, ignored -> new Properties()).putAll(properties);
        }
        if (!loaded.containsKey(LocaleContext.DEFAULT)) {
            throw new IllegalStateException("The default Catalan message bundle is required");
        }
        bundles = Map.copyOf(loaded);
    }

    public Set<Locale> supportedLocales() { return bundles.keySet(); }

    public boolean supports(Locale locale) {
        return bundles.containsKey(locale) || bundles.containsKey(Locale.forLanguageTag(locale.getLanguage()));
    }

    public String format(String code, Map<String, ?> arguments, Locale locale) {
        return new MessageFormat(requiredPattern(code, locale), locale).format(arguments);
    }

    @Override public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        String pattern = pattern(code, locale);
        if (pattern == null) { pattern = defaultMessage; }
        return pattern == null ? null : new MessageFormat(pattern, locale).format(args == null ? new Object[0] : args);
    }

    @Override public String getMessage(String code, Object[] args, Locale locale) {
        return new MessageFormat(requiredPattern(code, locale), locale).format(args == null ? new Object[0] : args);
    }

    @Override public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
        if (resolvable.getCodes() != null) {
            for (String code : resolvable.getCodes()) {
                String message = getMessage(code, resolvable.getArguments(), null, locale);
                if (message != null) { return message; }
            }
        }
        if (resolvable.getDefaultMessage() != null) {
            return new MessageFormat(resolvable.getDefaultMessage(), locale)
                    .format(resolvable.getArguments() == null ? new Object[0] : resolvable.getArguments());
        }
        throw new NoSuchMessageException(java.util.Arrays.toString(resolvable.getCodes()), locale);
    }

    private String requiredPattern(String code, Locale locale) {
        String pattern = pattern(code, locale);
        if (pattern == null) { throw new NoSuchMessageException(code, locale); }
        return pattern;
    }

    private String pattern(String code, Locale locale) {
        Properties localized = bundles.getOrDefault(locale,
                bundles.getOrDefault(Locale.forLanguageTag(locale.getLanguage()), bundles.get(LocaleContext.DEFAULT)));
        return localized.getProperty(code, bundles.get(LocaleContext.DEFAULT).getProperty(code));
    }
}

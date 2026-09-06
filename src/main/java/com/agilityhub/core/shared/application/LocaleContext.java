package com.agilityhub.core.shared.application;

import java.util.Locale;
import java.util.Objects;
import org.springframework.context.i18n.LocaleContextHolder;

/** Explicit recipient scopes also work in scheduled jobs; the JVM locale is never a fallback. */
public final class LocaleContext {
    public static final Locale DEFAULT = Locale.forLanguageTag("ca");
    private LocaleContext() { }

    public static Locale current() {
        var context = LocaleContextHolder.getLocaleContext();
        return context == null || context.getLocale() == null ? DEFAULT : context.getLocale();
    }

    public static Scope open(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        var previous = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(locale);
        return () -> LocaleContextHolder.setLocaleContext(previous);
    }

    public interface Scope extends AutoCloseable { @Override void close(); }
}

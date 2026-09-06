package com.agilityhub.core.shared.application;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import static org.assertj.core.api.Assertions.*;

class LocaleContextTest {
    @Test void E0_T08_scopesRestoreTheRecipientAfterNestedCallsAndFailures() {
        LocaleContextHolder.resetLocaleContext();
        assertThat(LocaleContext.current()).isEqualTo(Locale.forLanguageTag("ca"));
        try (var outer = LocaleContext.open(Locale.ENGLISH)) {
            assertThat(LocaleContext.current()).isEqualTo(Locale.ENGLISH);
            assertThatThrownBy(() -> {
                try (var inner = LocaleContext.open(Locale.forLanguageTag("es"))) {
                    assertThat(LocaleContext.current()).isEqualTo(Locale.forLanguageTag("es"));
                    throw new IllegalStateException("failure");
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(LocaleContext.current()).isEqualTo(Locale.ENGLISH);
        }
        assertThat(LocaleContextHolder.getLocaleContext()).isNull();
        assertThat(LocaleContext.current()).isEqualTo(Locale.forLanguageTag("ca"));
        LocaleContextHolder.setLocaleContext(() -> null);
        try { assertThat(LocaleContext.current()).isEqualTo(Locale.forLanguageTag("ca")); }
        finally { LocaleContextHolder.resetLocaleContext(); }
        assertThatThrownBy(() -> LocaleContext.open(null)).isInstanceOf(NullPointerException.class);
    }
}

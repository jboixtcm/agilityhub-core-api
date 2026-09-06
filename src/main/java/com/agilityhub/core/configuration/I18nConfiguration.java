package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.RequestLocaleFilter;
import com.agilityhub.core.shared.api.RequestLocaleResolver;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleSettingsProvider;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class I18nConfiguration {
    @Bean IcuMessageSource messageSource() throws IOException { return new IcuMessageSource(); }

    @Bean RequestLocaleResolver localeResolver(IcuMessageSource messages, ObjectProvider<LocaleSettingsProvider> clubs) {
        return new RequestLocaleResolver(messages, clubId -> clubs.getIfAvailable(() -> ignored -> Optional.empty()).settings(clubId));
    }

    @Bean FilterRegistrationBean<RequestLocaleFilter> requestLocaleFilter(RequestLocaleResolver locales) {
        var registration = new FilterRegistrationBean<>(new RequestLocaleFilter(locales));
        registration.setOrder(-80);
        return registration;
    }
}

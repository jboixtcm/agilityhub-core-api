package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.api.RequiresModuleInterceptor;
import com.agilityhub.core.platform.application.ModuleGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ModuleConfiguration {
    @Bean
    WebMvcConfigurer moduleMvcConfigurer(ModuleGuard guard, com.agilityhub.core.shared.application.SupportWriteAuditor supportWrites) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                // S17 R-17-10 (E10-T04 round 2): a support token is refused by its handler before any module check.
                registry.addInterceptor(new com.agilityhub.core.shared.api.SupportRestrictedInterceptor()).order(Ordered.HIGHEST_PRECEDENCE);
                registry.addInterceptor(new RequiresModuleInterceptor(guard)).order(Ordered.HIGHEST_PRECEDENCE + 1);
                // S17 R-17-10 (AUTHZ-05): a support write that its service does not audit is audited `SUPPORT_WRITE` after it.
                registry.addInterceptor(new com.agilityhub.core.shared.api.SupportWriteAuditInterceptor(supportWrites)).order(Ordered.HIGHEST_PRECEDENCE + 2);
            }
        };
    }
}

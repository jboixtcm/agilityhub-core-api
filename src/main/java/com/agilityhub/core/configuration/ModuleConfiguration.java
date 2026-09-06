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
    WebMvcConfigurer moduleMvcConfigurer(ModuleGuard guard) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RequiresModuleInterceptor(guard)).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }
}

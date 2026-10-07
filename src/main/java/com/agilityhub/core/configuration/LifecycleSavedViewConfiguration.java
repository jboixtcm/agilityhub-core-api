package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.ClubSavedViewProvisioner;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LifecycleSavedViewConfiguration {
    @Bean ApplicationRunner lifecycleSavedViewsBackfill(ClubRepository clubs, ClubSavedViewProvisioner views) {
        return args -> {
            for (var club : clubs.schedulableClubs()) {
                try (var tenant = TenantContext.open(club.id())) { views.provision(); }
            }
        };
    }
}

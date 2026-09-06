package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.domain.events.ParameterChanged;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.api.TenantFilter;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {
    @Bean ApplicationRunner platformIndexes(ClubRepository clubs, ParameterRepository parameters) {
        return arguments -> { clubs.ensureIndexes(); parameters.ensureIndexes(); };
    }
    @Bean FilterRegistrationBean<TenantFilter> tenantFilter(HostTenantResolver hosts, Environment environment,
                                                          ApiExceptionHandler errors, ObjectMapper mapper,
                                                          com.agilityhub.core.shared.application.SecurityEvents events) {
        var registration = new FilterRegistrationBean<>(new TenantFilter(hosts, environment.acceptsProfiles(Profiles.of("local")), errors, mapper, events));
        registration.setOrder(-90);
        return registration;
    }
    @Bean DomainEventHandler<ClubConfigChanged> clubConfigCache(ClubConfigService configs, HostTenantResolver hosts) {
        return clubHandler("ClubUpdated", configs, hosts);
    }
    @Bean DomainEventHandler<ClubConfigChanged> clubCreatedCache(ClubConfigService configs, HostTenantResolver hosts) {
        return clubHandler("ClubCreated", configs, hosts);
    }
    @Bean DomainEventHandler<ClubConfigChanged> clubStatusCache(ClubConfigService configs, HostTenantResolver hosts) {
        return clubHandler("ClubStatusChanged", configs, hosts);
    }
    @Bean DomainEventHandler<ClubConfigChanged> clubModulesCache(ClubConfigService configs, HostTenantResolver hosts) {
        return clubHandler("ClubModulesChanged", configs, hosts);
    }
    @Bean DomainEventHandler<ParameterChanged> parameterConfigCache(ClubConfigService configs) {
        return new DomainEventHandler<>() {
            @Override public String eventType() { return "ParameterChanged"; }
            @Override public Class<ParameterChanged> eventClass() { return ParameterChanged.class; }
            @Override public void handle(String eventId, ParameterChanged event) { configs.invalidate(event.clubId()); }
        };
    }
    private DomainEventHandler<ClubConfigChanged> clubHandler(String type, ClubConfigService configs, HostTenantResolver hosts) {
        return new DomainEventHandler<>() {
            @Override public String eventType() { return type; }
            @Override public Class<ClubConfigChanged> eventClass() { return ClubConfigChanged.class; }
            @Override public void handle(String eventId, ClubConfigChanged event) { configs.invalidate(event.clubId()); hosts.invalidate(); }
        };
    }
}

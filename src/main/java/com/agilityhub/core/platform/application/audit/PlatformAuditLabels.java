package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** E11-T10 (INC-38), S14 §3: a parameter's entries are labelled with its key («bookings.maxCurrentWeek»), the club's with its name. */
@Configuration(proxyBeanMethods = false)
public class PlatformAuditLabels {
    @Bean AuditLabeler parameterLabel(ParameterRepository parameters) {
        return labeler("Parameter", id -> parameters.findById(id).map(com.agilityhub.core.platform.persistence.Parameter::key).orElse(null));
    }
    @Bean AuditLabeler clubLabel(ClubRepository clubs) {
        return labeler("Club", id -> clubs.findById(id).map(com.agilityhub.core.platform.persistence.Club::name).orElse(null));
    }
    private static AuditLabeler labeler(String type, java.util.function.Function<String, String> label) {
        return new AuditLabeler() {
            @Override public String entityType() { return type; }
            @Override public String label(String entityId) { return label.apply(entityId); }
        };
    }
}

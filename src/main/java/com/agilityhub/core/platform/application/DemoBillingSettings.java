package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Fictional credentials are installed by seed:demo only, never by club:apply. */
@Service
public class DemoBillingSettings {
    private final ClubRepository clubs; private final ClubConfigService configs; private final ObjectMapper mapper; private final Environment environment;
    public DemoBillingSettings(ClubRepository clubs, ClubConfigService configs, ObjectMapper mapper, Environment environment) {
        this.clubs=clubs; this.configs=configs; this.mapper=mapper; this.environment=environment;
    }
    @Audited(action=AuditAction.CLUB_UPDATED, entityType="'Club'", entity="#result", reason="'DEMO_SEED'")
    public String providers(Map<String,Object> providers) {
        if (!environment.matchesProfiles("local","staging","test") || environment.matchesProfiles("prod","production")) { throw new ApiException(ErrorCode.FORBIDDEN); }
        String id=TenantContext.require(); var club=clubs.findById(id).orElseThrow();
        ObjectNode tree=mapper.valueToTree(club); ObjectNode next=(ObjectNode)tree.path("paymentProviders");
        providers.forEach((key,value) -> next.set(key,mapper.valueToTree(value)));
        clubs.save(mapper.convertValue(tree,Club.class)); configs.invalidateAfterCommit(id); return id;
    }
}

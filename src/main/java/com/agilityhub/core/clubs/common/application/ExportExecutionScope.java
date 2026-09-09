package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.ExportJob;
import com.agilityhub.core.shared.application.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Restores the accepted request's identity for tenant projections and audited completion. No token is issued. */
final class ExportExecutionScope implements AutoCloseable {
    private final org.springframework.security.core.context.SecurityContext security = SecurityContextHolder.getContext();
    private final TenantContext.Scope tenant;
    private final LocaleContext.Scope locale;
    ExportExecutionScope(ExportJob job) {
        tenant = TenantContext.open(job.clubId()); locale = LocaleContext.open(Locale.forLanguageTag(job.locale()));
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("internal-export-context").header("alg", "none").subject(job.ownerAccountId())
                .claim("clubId", job.clubId()).claim("roles", List.of("ADMIN")).build();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_ADMIN")));
        SecurityContextHolder.setContext(context);
    }
    @Override public void close() { SecurityContextHolder.setContext(security); locale.close(); tenant.close(); }
}

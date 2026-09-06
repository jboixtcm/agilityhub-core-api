package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.audit.AuditActor;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.shared.api.RequestTraceFilter;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = 0)
public class AuditConfiguration {
    @Bean ApplicationRunner auditIndexes(AuditRepository repository) {
        return arguments -> repository.ensureIndexes();
    }

    @Bean AuditActorProvider auditActorProvider() {
        return () -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            String accountId = null;
            String name = null;
            String role = "SYSTEM";
            String impersonated = null;
            Boolean support = null;
            if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
                accountId = authentication.getName();
                name = accountId;
                role = authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                        .filter(authority -> authority.startsWith("ROLE_")).map(authority -> authority.substring(5))
                        .sorted().findFirst().orElse("MEMBER");
                if (authentication instanceof JwtAuthenticationToken jwt) {
                    name = jwt.getToken().getClaimAsString("name");
                    impersonated = jwt.getToken().getClaimAsString("impersonatedMemberId");
                    support = jwt.getToken().getClaimAsBoolean("support");
                    var current = com.agilityhub.core.shared.application.CurrentUser.current();
                    if (current != null && current.impersonation() != null) {
                        accountId = current.impersonation().actorAccountId();
                        name = current.impersonation().actorName();
                        role = "ADMIN";
                        impersonated = current.impersonation().memberId();
                    }
                }
                if (role.equals("AGILITYHUB_ADMIN")) { role = "PLATFORM"; }
            }
            var attributes = RequestContextHolder.getRequestAttributes();
            var request = attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
            return new AuditActor(accountId, name, role, impersonated, support,
                    request == null ? null : request.getRemoteAddr(), request == null ? null : request.getHeader("User-Agent"),
                    request == null ? UUID.randomUUID().toString() : RequestTraceFilter.traceId(request));
        };
    }
}

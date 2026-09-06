package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class AuditConfigurationTest {
    private final AuditActorProvider actors = new AuditConfiguration().auditActorProvider();

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void T_14_12_capturesJwtActorImpersonationSupportAndRequestTrace() {
        var jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject("account-a")
                .claim("name", "Example Admin").claim("impersonatedMemberId", "member-a").claim("support", true).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        request.addHeader("User-Agent", "Example client");
        request.addHeader("X-Forwarded-For", "untrusted");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var actor = actors.current();
        assertThat(actor.accountId()).isEqualTo("account-a");
        assertThat(actor.name()).isEqualTo("Example Admin");
        assertThat(actor.role()).isEqualTo("ADMIN");
        assertThat(actor.impersonatedMemberId()).isEqualTo("member-a");
        assertThat(actor.support()).isTrue();
        assertThat(actor.ip()).isEqualTo("192.0.2.1");
        assertThat(actor.userAgent()).isEqualTo("Example client");
        assertThat(actor.traceId()).isNotBlank().isEqualTo(actors.current().traceId());
    }

    @Test void T_14_12_platformRoleAndSystemFallbackUseNoSyntheticAccount() {
        assertThat(actors.current().role()).isEqualTo("SYSTEM");
        assertThat(actors.current().accountId()).isNull();
        assertThat(actors.current().traceId()).isNotBlank();
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(actors.current().accountId()).isNull();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("account-p", "unused", List.of(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN"))));
        assertThat(actors.current().role()).isEqualTo("PLATFORM");
        assertThat(actors.current().name()).isEqualTo("account-p");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("account-m", "unused", List.of()));
        assertThat(actors.current().role()).isEqualTo("MEMBER");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("unverified", "unused"));
        assertThat(actors.current().accountId()).isNull();
    }
}

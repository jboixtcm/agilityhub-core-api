package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Runs demo-seed work as a fictional seed account, so role guards, audit actors and event origins stay real. */
public final class DemoSeedActor {
    private DemoSeedActor() { }
    public static <T> T as(String accountId, String role, Supplier<T> work) {
        var context = SecurityContextHolder.getContext(); var previous = context.getAuthentication();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(accountId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        var origin = "MEMBER".equals(role) ? DomainEvent.Origin.APP : "INSTRUCTOR".equals(role) ? DomainEvent.Origin.INSTRUCTOR : DomainEvent.Origin.BACKOFFICE;
        try (var user = CurrentUser.open(new CurrentUser(accountId, "Demo seed", null, origin))) { return work.get(); }
        finally { context.setAuthentication(previous); }
    }
}

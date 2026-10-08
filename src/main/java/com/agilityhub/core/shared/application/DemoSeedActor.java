package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Runs demo-seed work as a fictional seed account, so role guards, audit actors and event origins stay real. E5-T14
 * (review E5-T09 #10): it refuses to run outside the `local`/`test` profiles (or with `staging`/`prod` active), like
 * `DemoPlanningService`, and without a registered application environment.
 */
public final class DemoSeedActor {
    private static final AtomicReference<Environment> ENVIRONMENT = new AtomicReference<>();
    private DemoSeedActor() { }

    /** Hands the application environment to the static guard. */
    @Component
    public static class ProfileGuard implements EnvironmentAware {
        @Override public void setEnvironment(Environment environment) { ENVIRONMENT.set(environment); }
    }

    public static <T> T as(String accountId, String role, Supplier<T> work) { return as(ENVIRONMENT.get(), accountId, role, work); }

    static <T> T as(Environment environment, String accountId, String role, Supplier<T> work) {
        if (!allowed(environment)) { throw new ApiException(ErrorCode.FORBIDDEN); }
        var context = SecurityContextHolder.getContext(); var previous = context.getAuthentication();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(accountId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        var origin = "MEMBER".equals(role) ? DomainEvent.Origin.APP : "INSTRUCTOR".equals(role) ? DomainEvent.Origin.INSTRUCTOR : DomainEvent.Origin.BACKOFFICE;
        try (var user = CurrentUser.open(new CurrentUser(accountId, "Demo seed", null, origin))) { return work.get(); }
        finally { context.setAuthentication(previous); }
    }

    static boolean allowed(Environment environment) {
        return environment != null && environment.acceptsProfiles(Profiles.of("local", "test")) && !environment.acceptsProfiles(Profiles.of("staging", "prod"));
    }
}

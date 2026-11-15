package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.RecentLogins;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;

/**
 * AUTH-01 (S01 R-01-18): a `@RecentLogin` handler refuses a staff caller whose sign-in is not recent (`403 RECENT_LOGIN_REQUIRED`).
 * It runs inside method security's `@PreAuthorize` (order 200) and the audit aspect (100), so a caller without the route's role
 * keeps its `403 FORBIDDEN` and a refused request leaves no audit entry, and outside the idempotent replay (lowest), so a replay
 * also needs a recent sign-in. The MVC interceptors (support, impersonation, module guards) already ran.
 */
@Aspect
@Order(300)
public class RecentLoginAspect {
    private final RecentLogins logins;
    public RecentLoginAspect(RecentLogins logins) { this.logins = logins; }

    @Before("within(com.agilityhub.core..*) && @annotation(com.agilityhub.core.shared.application.RecentLogin)")
    public void requireRecentLogin() { logins.require(); }
}

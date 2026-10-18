package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** MVC preHandle runs before argument resolution, JSON decoding and bean validation. */
public class RequiresModuleInterceptor implements HandlerInterceptor {
    private final ModuleGuard guard;

    public RequiresModuleInterceptor(ModuleGuard guard) { this.guard = guard; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // E9-T04: the dispatch that ends an async response (an SSE stream) passed this check when it started; it runs without the
        // request's tenant (OncePerRequestFilter skips it), so checking again could only fail after the response was committed.
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) { return true; }
        if (handler instanceof HandlerMethod method) {
            require(AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresModule.class));
            require(method.getMethodAnnotation(RequiresModule.class));
        }
        return true;
    }

    private void require(RequiresModule requirement) {
        if (requirement != null && !exempt(requirement.exemptRoles())) {
            guard.require(TenantContext.require(), requirement.value());
        }
    }

    private static boolean exempt(String[] roles) {
        if (roles.length == 0) { return false; }
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) { return false; }
        var granted = java.util.Arrays.stream(roles).map(role -> "ROLE_" + role).toList();
        return authentication.getAuthorities().stream().anyMatch(authority -> granted.contains(authority.getAuthority()));
    }
}

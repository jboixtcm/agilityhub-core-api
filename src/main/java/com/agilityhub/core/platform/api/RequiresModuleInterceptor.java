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
        if (handler instanceof HandlerMethod method) {
            require(AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresModule.class));
            require(method.getMethodAnnotation(RequiresModule.class));
        }
        return true;
    }

    private void require(RequiresModule requirement) {
        if (requirement != null) {
            guard.require(TenantContext.require(), requirement.value());
        }
    }
}

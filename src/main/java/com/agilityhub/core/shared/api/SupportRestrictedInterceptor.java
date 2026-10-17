package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.SupportRestricted;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * S17 R-17-10 (E10-T04 round 2): a `@SupportRestricted` handler refuses a support access (`403 SUPPORT_RESTRICTED`) once Spring
 * has chosen it, so no spelling of its path (an encoded letter, a `;` parameter) takes a support token to it. MVC's preHandle
 * runs before argument resolution, JSON decoding and bean validation.
 */
public class SupportRestrictedInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) { return true; }
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(SupportRestricted.class)) {
            var user = CurrentUser.current();
            if (user != null && user.support() != null) { throw new ApiException(ErrorCode.SUPPORT_RESTRICTED); }
        }
        return true;
    }
}

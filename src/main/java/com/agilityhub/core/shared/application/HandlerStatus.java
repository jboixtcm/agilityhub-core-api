package com.agilityhub.core.shared.application;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * INC-48 (E11-T02; CONVENCIONS_API §7): the status a keyed write stores with its key, read from the current handler's own
 * `@ResponseStatus` (200 without one). A second, hand-written copy could diverge, and a repeated key would then answer another
 * status than the first.
 */
public final class HandlerStatus {
    private HandlerStatus() { }

    /** @throws IllegalStateException outside a request mapped to a handler method */
    public static int current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        Object handler = attributes == null ? null : attributes.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (!(handler instanceof HandlerMethod method)) { throw new IllegalStateException("No handler method for this request"); }
        var status = method.getMethodAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class);
        return status == null ? 200 : status.code().value();
    }
}

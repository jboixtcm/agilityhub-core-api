package com.agilityhub.core.shared.api;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * CONVENCIONS_API §7 (ruling E62): a repeated `Idempotency-Key` is answered here, around the handler method and inside
 * every other advice — method security's `@PreAuthorize` (order 200) and the audit aspect (100) run first, and the MVC
 * interceptors (impersonation and module guards) already ran before the handler was invoked. The handler itself is never
 * invoked for a replay. `IdempotentReplayContractIT` checks that every handler method of the api is advised.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class IdempotentReplayAspect {
    static final String HANDLERS = "within(com.agilityhub.core..*) && (@annotation(org.springframework.web.bind.annotation.RequestMapping)"
            + " || @annotation(org.springframework.web.bind.annotation.GetMapping) || @annotation(org.springframework.web.bind.annotation.PostMapping)"
            + " || @annotation(org.springframework.web.bind.annotation.PutMapping) || @annotation(org.springframework.web.bind.annotation.DeleteMapping)"
            + " || @annotation(org.springframework.web.bind.annotation.PatchMapping))";

    @Around(HANDLERS)
    public Object handler(ProceedingJoinPoint invocation) throws Throwable {
        var replay = IdempotentReplay.take();
        if (replay != null) { throw replay.answer(); }
        return invocation.proceed();
    }
}

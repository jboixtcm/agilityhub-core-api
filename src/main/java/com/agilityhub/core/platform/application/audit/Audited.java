package com.agilityhub.core.platform.application.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {
    AuditAction action();
    /** SpEL, for example "'Parameter'" or "#result.type". */
    String entityType();
    String entity() default "#result.id";
    /** Optional SpEL snapshot for an existing object, captured before invocation. */
    String before() default "";
    String member() default "";
    String reason() default "";
}

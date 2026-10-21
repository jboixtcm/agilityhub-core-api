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
    /**
     * Optional SpEL evaluated after the invocation (`#result` available): a map written as the entry's `details` (S14 §3),
     * e.g. the announcement's `{batchId, recipientCount, filters}`; null or empty writes none.
     */
    String details() default "";
    /**
     * Optional SpEL evaluated after the invocation: the ids of the outbox events the entry links (`eventIds[]`, R-14-10), for a
     * flow that writes several entries in one transaction and links each one to its own event (S09 R-09-13's batch, E11-T17).
     * Empty links every event published so far in the transaction.
     */
    String events() default "";
}

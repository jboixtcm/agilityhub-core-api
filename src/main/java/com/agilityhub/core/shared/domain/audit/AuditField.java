package com.agilityhub.core.shared.domain.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opts a field into audit snapshots; unannotated fields are never recorded. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditField { }

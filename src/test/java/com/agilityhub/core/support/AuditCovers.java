package com.agilityhub.core.support;

import com.agilityhub.core.platform.application.audit.AuditAction;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditCovers {
    AuditAction[] value();
}

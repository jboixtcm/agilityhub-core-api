package com.agilityhub.core.platform.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class and method requirements are cumulative when both are present. `exemptRoles` (INC-44): a caller holding one of these
 * roles (without the `ROLE_` prefix) passes without the module; the handler then answers its module-off projection.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresModule {
    Module value();
    String[] exemptRoles() default {};
}

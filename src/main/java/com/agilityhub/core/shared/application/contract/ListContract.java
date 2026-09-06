package com.agilityhub.core.shared.application.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares list capabilities; a star marks default columns, @ marks a module, # a parameter. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ListContract {
    String[] filterable() default {};
    String[] sortable() default {};
    String[] columns() default {};
    boolean paged() default false;
    boolean exportable() default false;
}

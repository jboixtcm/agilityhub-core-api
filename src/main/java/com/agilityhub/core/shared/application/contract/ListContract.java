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
    /**
     * The keys `fields=` accepts, published as `x-fields` (CONVENCIONS_API §4, E5-T20): every key the list's allowlist accepts
     * for any role; any other key is `400 INVALID_FILTER`. Empty for an operation that is not a universal list.
     */
    String[] fields() default {};
    boolean paged() default false;
    boolean exportable() default false;
}

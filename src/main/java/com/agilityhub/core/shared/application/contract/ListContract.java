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
     * for any role; any other key is `400 INVALID_FILTER`. An export publishes its list's keys and picks its columns from them
     * (E5-T24). A `paged` operation that accepts `fields` must declare them: the application refuses to start otherwise.
     */
    String[] fields() default {};
    /**
     * False for an operation that cannot honour `fields` (E5-T22, CONVENCIONS_API §4), such as a contract-only list or
     * `filter-values` (E5-T24): it then publishes neither the `fields` parameter nor `x-fields`, and declaring `fields` with it
     * stops the application at startup.
     */
    boolean acceptsFields() default true;
    boolean paged() default false;
    boolean exportable() default false;
}

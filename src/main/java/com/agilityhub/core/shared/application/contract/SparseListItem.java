package com.agilityhub.core.shared.application.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The item of a universal list (CONVENCIONS_API §4, E5-T22): with `fields`, the list leaves out every key that was not
 * requested and always sends the row id, so the item schema requires the row id only. The OpenAPI converter reads this
 * annotation instead of the property-by-property defaults; the item's properties are the list's `x-fields`.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SparseListItem {
    /** The key that identifies a row and is always sent: `id`, or `runId` for the job runs. */
    String rowId() default "id";
}

package com.agilityhub.core.shared.application.contract;

import com.agilityhub.core.shared.domain.ErrorCode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Business error descriptions use the closed catalog's canonical HTTP status. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ContractErrors {
    ErrorCode[] value();
    /**
     * Generic statuses the operation never answers, left out of the published responses: for example `422` when no catalog
     * code of that status can come from it (E5-T13, review E5-T09 #8). A status that a code in {@link #value()} maps to is
     * always kept.
     */
    int[] omit() default {};
}

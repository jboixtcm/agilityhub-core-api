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
}

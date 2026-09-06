package com.agilityhub.core.shared.domain.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Sensitive {
    Strategy value() default Strategy.MASK_IBAN;
    enum Strategy { MASK_IBAN, MASK_ID_DOCUMENT, HIDE }
}

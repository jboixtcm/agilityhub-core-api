package com.agilityhub.core.shared.application.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Member routes that accept the impersonation token of «Entra com l'abonat»; every other E5 route answers IMPERSONATION_DENIED. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowsImpersonation { }

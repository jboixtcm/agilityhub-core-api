package com.agilityhub.core.shared.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * S17 R-17-10 (E10-T04 round 2): a handler that a support access never reaches (`403 SUPPORT_RESTRICTED`): the personal-data
 * exports and their files, the SEPA files, a member's impersonation, the account's own credentials and sessions, and the
 * club's management of support accesses. `shared.api.SupportRestrictedInterceptor` refuses the request before its arguments are
 * read, whatever path reached the handler; the request filter's path check stays in front of it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SupportRestricted { }

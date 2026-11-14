package com.agilityhub.core.shared.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AUTH-01 (S01 R-01-18, owner decision of 13 Nov 2026): a handler that a staff caller (a club `ADMIN` or `INSTRUCTOR`, an
 * `AGILITYHUB_ADMIN`; a support access is its platform administrator) reaches only within {@link RecentLogins#WINDOW} of the sign-in
 * that opened its session (the token's `auth_time`; a refresh keeps it): the personal-data exports and their files, the
 * remittance files, a member's bank account and the club's payment providers. Otherwise `403 RECENT_LOGIN_REQUIRED`, and the client
 * signs in again: with the password, or with a sign-in link for an account without one. `shared.api.RecentLoginAspect` checks it
 * after the handler's `@PreAuthorize`, so a caller without the route's role still gets its `403 FORBIDDEN`.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RecentLogin { }

package com.agilityhub.core.clubs.messaging.application.integrations;

/**
 * S11 §6 `record SendResult(String providerRef, boolean retryable, String error)`: accepted when `error` is null. The
 * error is a short technical text, never a provider body (it can echo a phone number or a message, R-14-18).
 */
public record SendResult(String providerRef, boolean retryable, String error) {
    /** `SendResult.error` of an SMS the non-production guard refused (→ `SKIPPED_NOT_ALLOWED`, E7-T02 step 6). */
    public static final String NOT_ALLOWED = "SMS_NOT_ALLOWED";

    public static SendResult accepted(String providerRef) { return new SendResult(providerRef, false, null); }
    public static SendResult retryable(String error) { return new SendResult(null, true, error); }
    public static SendResult failed(String error) { return new SendResult(null, false, error); }
    public static SendResult notAllowed() { return new SendResult(null, false, NOT_ALLOWED); }
    public boolean ok() { return error == null; }
    public boolean refusedByGuard() { return NOT_ALLOWED.equals(error); }
}

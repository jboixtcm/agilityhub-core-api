package com.agilityhub.core.clubs.messaging.application.integrations;

/**
 * S11 §6 `PushResult ∈ OK · GONE · RETRYABLE · FAILED` (R-11-07): `GONE` (404/410) expires the subscription and is never
 * retried; `RETRYABLE` (429, 5xx, timeout) is retried with the R-11-09 backoff; `FAILED` is final for the delivery, and
 * the third consecutive one expires the subscription.
 */
public record PushResult(Status status, String error) {
    public enum Status { OK, GONE, RETRYABLE, FAILED }
    public static PushResult ok() { return new PushResult(Status.OK, null); }
    public static PushResult of(Status status, String error) { return new PushResult(status, error); }
}

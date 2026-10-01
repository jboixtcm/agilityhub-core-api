package com.agilityhub.core.payments.domain;

/**
 * S12 R-12-25 (S10 R-10-07): what a `CHARGE_ON_ATTENDANCE` booking's attendance or late cancellation does to its single
 * `PendingCharge` (unique per booking). A mark `PRESENT`/`NO_SHOW` from `PENDING` charges the class; marking it back to
 * `PENDING` before billing voids the charge, and a later `PRESENT`/`NO_SHOW` reinstates it; a late cancellation charges it.
 * A billed charge never changes (the invoice is immutable, R-12-10: an adjustment corrects it).
 */
public final class PendingChargeRules {
    private PendingChargeRules() { }
    public enum Action { CHARGE, VOID, REINSTATE, NONE }
    /** The booking's charge so far: none, open, voided, or billed. */
    public enum Existing { NONE, OPEN, VOIDED, BILLED }

    public static Action onAttendance(String state, String previousState, Existing existing) {
        boolean charged = "PRESENT".equals(state) || "NO_SHOW".equals(state);
        boolean wasCharged = "PRESENT".equals(previousState) || "NO_SHOW".equals(previousState);
        if (charged && (previousState == null || "PENDING".equals(previousState))) {
            return switch (existing) { case NONE -> Action.CHARGE; case VOIDED -> Action.REINSTATE; default -> Action.NONE; };
        }
        if ("PENDING".equals(state) && wasCharged && existing == Existing.OPEN) { return Action.VOID; }
        return Action.NONE;
    }
    public static Action onCancellation(boolean late, Existing existing) {
        if (!late) { return Action.NONE; }
        return switch (existing) { case NONE -> Action.CHARGE; case VOIDED -> Action.REINSTATE; default -> Action.NONE; };
    }
}

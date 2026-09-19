package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.*;

public final class ActivityTransitions {
    private ActivityTransitions() { }
    public static void activity(ActivityState before, ActivityState after) {
        boolean valid = switch (before) {
            case DRAFT -> after == ActivityState.PUBLISHED || after == ActivityState.CANCELLED;
            case PUBLISHED -> after == ActivityState.DRAFT || after == ActivityState.FINISHED || after == ActivityState.CANCELLED;
            default -> false;
        };
        if (!valid) throw new ApiException(ErrorCode.INVALID_STATE);
    }
    public static void registration(RegistrationState before, RegistrationState after) {
        if (!(before == RegistrationState.ACTIVE && after == RegistrationState.CANCELLED
                || before == RegistrationState.WAITLISTED && (after == RegistrationState.ACTIVE || after == RegistrationState.CANCELLED)))
            throw new ApiException(ErrorCode.INVALID_STATE);
    }
}

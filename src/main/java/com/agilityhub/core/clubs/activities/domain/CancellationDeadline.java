package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.Map;

public final class CancellationDeadline {
    private CancellationDeadline() { }
    public static Instant deadline(String policy, RegistrationState state, ActivityTimes times, boolean impersonated) {
        return impersonated || state == RegistrationState.WAITLISTED || "EVENT_START".equals(policy) ? times.startsAt() : times.registrationClosesAt();
    }
    public static void check(Instant now, String policy, RegistrationState state, ActivityTimes times, boolean impersonated, String reason) {
        if (state == RegistrationState.CANCELLED) throw new ApiException(ErrorCode.INVALID_STATE);
        if (impersonated && (reason == null || reason.isBlank())) throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "reason"));
        var deadline = deadline(policy, state, times, impersonated);
        if (!now.isBefore(deadline)) throw new ApiException(ErrorCode.REGISTRATION_NOT_CANCELLABLE, Map.of("deadline", deadline));
    }
}

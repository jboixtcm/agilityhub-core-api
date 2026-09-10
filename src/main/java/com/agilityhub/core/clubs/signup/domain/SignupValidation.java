package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.util.List;
import java.util.Map;

final class SignupValidation {
    private SignupValidation() { }
    static ApiException field(String field) {
        return new ApiException(ErrorCode.VALIDATION_ERROR,
                Map.of("fieldErrors", List.of(Map.of("field", field, "code", "VALIDATION_ERROR"))));
    }
    static Money nonnegative(Money value) {
        if (value == null || value.amountMinor() < 0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        return value;
    }
    static void day(int day) {
        if (day < 1 || day > 31) { throw new ApiException(ErrorCode.PARAMETER_INVALID); }
    }
}

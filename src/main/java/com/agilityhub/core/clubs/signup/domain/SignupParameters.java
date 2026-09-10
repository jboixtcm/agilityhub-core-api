package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.util.Map;
import java.util.function.Function;

/** A frozen set of catalog values; callers supply effective club/scoped values, never literals. */
public record SignupParameters(int firstMonthSplitDay, int nextInvoiceDayOfMonth, int upfrontCutoffDay,
                               int familyDiscountPercentFromSecondDog, Money entryFeePerDog) {
    public SignupParameters {
        SignupValidation.day(firstMonthSplitDay); SignupValidation.day(nextInvoiceDayOfMonth); SignupValidation.day(upfrontCutoffDay);
        if (familyDiscountPercentFromSecondDog < 0 || familyDiscountPercentFromSecondDog > 100) {
            throw new ApiException(ErrorCode.PARAMETER_INVALID);
        }
        SignupValidation.nonnegative(entryFeePerDog);
    }
    public static SignupParameters resolve(ParameterCatalog catalog, Function<String, Object> effectiveValue) {
        Function<String, Object> read = key -> {
            catalog.get(key);
            Object override = effectiveValue.apply(key);
            return override == null ? catalog.defaultValue(key) : override;
        };
        Object raw = read.apply("billing.entryFeePerDog");
        Money fee = raw instanceof Money money ? money : new Money(((Number) ((Map<?, ?>) raw).get("amountMinor")).longValue(),
                (String) ((Map<?, ?>) raw).get("currency"));
        return new SignupParameters(((Number) read.apply("signup.firstMonthSplitDay")).intValue(),
                ((Number) read.apply("billing.nextInvoiceDayOfMonth")).intValue(),
                ((Number) read.apply("billing.upfrontCutoffDay")).intValue(),
                ((Number) read.apply("billing.familyDiscountPercentFromSecondDog")).intValue(), fee);
    }
}

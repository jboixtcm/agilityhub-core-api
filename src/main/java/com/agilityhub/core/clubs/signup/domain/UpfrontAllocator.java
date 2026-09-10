package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class UpfrontAllocator {
    private UpfrontAllocator() { }
    public record Allocation(UpfrontLines.Line line, Money amountPaid, UpfrontPaymentStatus status) { }
    public static List<Allocation> allocate(List<UpfrontLines.Line> lines, Money paid) {
        SignupValidation.nonnegative(paid);
        Money due = new Money(0, paid.currency());
        for (var line : lines) { due = due.plus(line.amountDue()); }
        if (paid.amountMinor() > due.amountMinor()) { throw new ApiException(ErrorCode.UPFRONT_AMOUNT_EXCEEDS_DUE); }
        long remaining = paid.amountMinor(); var result = new ArrayList<Allocation>();
        var ordered = lines.stream().sorted(Comparator.comparingInt(l -> l.concept() == UpfrontLines.Concept.ENTRY_FEE ? 0 : 1)).toList();
        for (var line : ordered) {
            long allocated = Math.min(remaining, line.amountDue().amountMinor()); remaining -= allocated;
            var status = allocated == line.amountDue().amountMinor() ? UpfrontPaymentStatus.PAID
                    : allocated == 0 ? UpfrontPaymentStatus.DUE : UpfrontPaymentStatus.PARTIAL;
            result.add(new Allocation(line, new Money(allocated, paid.currency()), status));
        }
        return List.copyOf(result);
    }
}

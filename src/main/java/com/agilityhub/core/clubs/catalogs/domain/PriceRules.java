package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Inclusive business-date intervals; callers supply the club-local date and invoice usage. */
public final class PriceRules {
    private PriceRules() { }
    public enum Status { SCHEDULED, CURRENT, EXPIRED }
    public interface Period { LocalDate validFrom(); LocalDate validTo(); }
    public static boolean supports(OfferTerms.PlanType type, OfferTerms.PriceConcept concept) {
        return switch (type) {
            case MONTHLY -> concept == OfferTerms.PriceConcept.MONTHLY_FEE || concept == OfferTerms.PriceConcept.MAINTENANCE_FEE;
            case PACK -> concept == OfferTerms.PriceConcept.PACK;
            case SINGLE_CLASS -> concept == OfferTerms.PriceConcept.SINGLE_CLASS;
        };
    }
    public static Status status(Period price, LocalDate today) {
        if (today.isBefore(price.validFrom())) { return Status.SCHEDULED; }
        return price.validTo() != null && today.isAfter(price.validTo()) ? Status.EXPIRED : Status.CURRENT;
    }
    public static boolean overlaps(Period left, Period right) {
        return (left.validTo() == null || !left.validTo().isBefore(right.validFrom()))
                && (right.validTo() == null || !right.validTo().isBefore(left.validFrom()));
    }
    public static boolean locked(Period price, LocalDate today, boolean referenced) {
        return referenced || !price.validFrom().isAfter(today);
    }
    public static void validate(Money amount, String currency, BigDecimal tax, LocalDate from, LocalDate to) {
        if (amount == null || amount.amountMinor() < 0 || tax == null || tax.signum() < 0 || tax.compareTo(BigDecimal.valueOf(100)) > 0
                || tax.stripTrailingZeros().scale() > 2 || from == null || (to != null && to.isBefore(from))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (!amount.currency().equals(currency)) { throw new ApiException(ErrorCode.CURRENCY_MISMATCH); }
    }
    public static void newStart(LocalDate from, LocalDate today, LocalDate lastBilled) {
        if (from.isBefore(today.withDayOfMonth(1)) || lastBilled != null && !from.isAfter(lastBilled)) {
            throw new ApiException(ErrorCode.PRICE_LOCKED);
        }
    }
    public static void close(LocalDate to, LocalDate today, LocalDate lastBilled) {
        if (to != null && (to.isBefore(today) || lastBilled != null && to.isBefore(lastBilled))) {
            throw new ApiException(ErrorCode.PRICE_LOCKED);
        }
    }
}

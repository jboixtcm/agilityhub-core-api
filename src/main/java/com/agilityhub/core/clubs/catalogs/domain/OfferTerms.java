package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;

/** S05 plan terms, independent of persistence and transport. */
public final class OfferTerms {
    private OfferTerms() { }
    public enum PlanType { MONTHLY, PACK, SINGLE_CLASS }
    public enum BillingMode { MONTHLY_FEE, MAINTENANCE }
    public enum PriceConcept { MONTHLY_FEE, MAINTENANCE_FEE, PACK, SINGLE_CLASS }
    public enum EntryFeeMode { STANDARD, AMOUNT, PERCENT, NONE }
    public enum ChargeMode { CHARGE_ON_ATTENDANCE, PAY_TO_BOOK }
    public enum CancelPolicy { REFUND, CREDIT, NONE }
    public record EntryFee(EntryFeeMode mode, Money amount, Integer percent) { }
    public record Pack(int sessions, int validityMonths) { }
    public record SingleClass(ChargeMode chargeMode, CancelPolicy cancelPolicy) { }
    public record Texts(LocalizedText description, LocalizedText offerLabel, LocalizedText priceLabel) { }
}

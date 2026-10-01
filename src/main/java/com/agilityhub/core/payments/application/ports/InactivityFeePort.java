package com.agilityhub.core.payments.application.ports;

import com.agilityhub.core.shared.domain.Money;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * S13 R-13-08 `InactivityFeeService` as S12's invoicing reads it (E8-T02): the fee a member owes for a month inside an
 * approved inactivity period, which replaces the month's `MONTHLY_FEE` with an `INACTIVITY_FEE` line (R-12-02), and the
 * month's fees for D6's KPI. The default null object ({@link BillingPortDefaults}) knows no period: **E8-T05**
 * (`InactivityFeeService`) replaces it.
 */
public interface InactivityFeePort {
    /** One member's fee of the month; {@code firstMonth} = the period's first month (`feeSnapshot.firstMonth`). */
    record MemberFee(String memberId, Money fee, boolean firstMonth) { }
    Optional<Money> feeFor(String memberId, YearMonth month);
    List<MemberFee> feesForMonth(YearMonth month);
}

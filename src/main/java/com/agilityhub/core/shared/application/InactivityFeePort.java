package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.Money;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** S13 billing contract implemented by census; shared to preserve the context dependency direction (E90). */
public interface InactivityFeePort {
    /** One member's fee of the month; {@code firstMonth} = the period's first month (`feeSnapshot.firstMonth`). */
    record MemberFee(String memberId, Money fee, boolean firstMonth) { }
    Optional<Money> feeFor(String memberId, YearMonth month);
    List<MemberFee> feesForMonth(YearMonth month);
}

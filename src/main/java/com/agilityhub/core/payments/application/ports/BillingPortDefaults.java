package com.agilityhub.core.payments.application.ports;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The null objects of S12's invoicing ports (E8-T02), each until the task named in its port replaces it: no inactivity fee
 * and nobody leaving (E8-T05), packs not opened (E8-T05), no SEPA writer (E8-T03: `422 SEPA_NOT_CONFIGURED`, except the
 * local/test {@link InMemoryRemittanceWriter}) and no card provider (E8-T04: `422 PAYMENT_PROVIDER_NOT_ENABLED`).
 */
@AutoConfiguration
public class BillingPortDefaults {
    @Bean @ConditionalOnMissingBean(InactivityFeePort.class)
    InactivityFeePort inactivityFees() {
        return new InactivityFeePort() {
            @Override public Optional<com.agilityhub.core.shared.domain.Money> feeFor(String memberId, java.time.YearMonth month) { return Optional.empty(); }
            @Override public List<MemberFee> feesForMonth(java.time.YearMonth month) { return List.of(); }
        };
    }
    @Bean @ConditionalOnMissingBean(LeaveBillingPort.class)
    LeaveBillingPort leaveBilling() { return memberId -> Optional.empty(); }
    @Bean @ConditionalOnMissingBean(PackBalanceOpeningPort.class)
    PackBalanceOpeningPort packOpening() { return (memberId, dogId, upfrontPaymentId, paidOn) -> { }; }
    @Bean @ConditionalOnMissingBean(RemittanceWriterPort.class)
    RemittanceWriterPort remittanceWriter() {
        return (run, collections, collectionDate) -> { throw new ApiException(ErrorCode.SEPA_NOT_CONFIGURED); };
    }
    @Bean @ConditionalOnMissingBean(CardChargingPort.class)
    CardChargingPort cardCharging() { return runId -> { throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }; }
}

package com.agilityhub.core.payments.application.ports;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E8-T02 step 1: the null objects of the invoicing ports, each until the task named in its port replaces it — no fee, nobody
 * leaving, no pack opened (E8-T05), no card provider (`422 PAYMENT_PROVIDER_NOT_ENABLED`, E8-T04). E8-T03 replaced the SEPA
 * writer's null object and its local/test stub with `SepaRemittanceWriter` (see `SepaRemittanceWriterTest`).
 */
class BillingPortDefaultsTest {
    final BillingPortDefaults defaults = new BillingPortDefaults();

    @Test void E8_T02_theNullObjectsKnowNoFeeNoLeaveNoPackAndNoCardProvider() {
        assertThatThrownBy(() -> defaults.cardCharging().chargeRun("run"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
    }

    @Test void E8_T03_theSepaWriterHasNoNullObjectAnyMore() {
        assertThat(java.util.Arrays.stream(BillingPortDefaults.class.getDeclaredMethods()).map(java.lang.reflect.Method::getReturnType))
                .doesNotContain(RemittanceWriterPort.class);
    }

    /** The payloads E8-T04 and E8-T05 fill in: a card run's submitted count and skipped invoices, a member's inactivity fee. */
    @Test void E8_T02_thePortPayloadsCarryWhatTheirCallersRead() {
        var result = new CardChargingPort.Result(11, java.util.List.of(new CardChargingPort.Skip("invoice-1", "NO_PAYMENT_METHOD")));
        assertThat(result.submitted()).isEqualTo(11);
        assertThat(result.skipped()).singleElement().extracting(CardChargingPort.Skip::reason).isEqualTo("NO_PAYMENT_METHOD");
        var fee = new com.agilityhub.core.shared.application.InactivityFeePort.MemberFee("member", new com.agilityhub.core.shared.domain.Money(2000, "EUR"), true);
        assertThat(fee.firstMonth()).isTrue();
        assertThat(fee.fee().amountMinor()).isEqualTo(2000);
    }
}

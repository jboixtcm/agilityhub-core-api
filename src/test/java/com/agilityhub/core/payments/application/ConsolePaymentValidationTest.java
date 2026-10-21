package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConsolePaymentValidationTest {
    @Test void T_17_05_R_17_05_sepaChecksBankChecksumAndCreditorFormat() {
        ConsolePaymentValidation.sepa("Example","ES26000G12345678","es91 2100 0418 4502 0005 1332","000");
        for(String invalid:new String[]{null,"","ES0000000000000000000000","1234"}) assertThat(ConsolePaymentValidation.iban(invalid)).isFalse();
        for(String name:new String[]{null,""}) assertThatThrownBy(()->ConsolePaymentValidation.sepa(name,"ES26000G12345678","ES9121000418450200051332","000")).isInstanceOf(ApiException.class);
        for(String creditor:new String[]{null,"invalid"}) assertThatThrownBy(()->ConsolePaymentValidation.sepa("Example",creditor,"ES9121000418450200051332","000")).isInstanceOf(ApiException.class);
        for(String suffix:new String[]{null,"0000"}) assertThatThrownBy(()->ConsolePaymentValidation.sepa("Example","ES26000G12345678","ES9121000418450200051332",suffix)).isInstanceOf(ApiException.class);
        assertThat(ConsolePaymentValidation.iban("GB82WEST12345698765432")).isTrue();
    }

    /**
     * E11-T15 step 8 (the audit of 21-10, F8; S17 R-17-05, S12 R-12-12 `CdtrSchmeId`): the creditor identifier's check digits are
     * ISO 7064 mod 97-10 over the identifier without its business code. «ES00ZZZG12345678» has the right shape and wrong digits:
     * the bank would refuse every file, so the console refuses it; its right digits are 26 (the business code is not covered).
     */
    @Test void T_17_05_R_12_12_theCreditorIdentifiersCheckDigitsAreVerified() {
        assertThatThrownBy(()->ConsolePaymentValidation.sepa("Example","ES00ZZZG12345678","ES9121000418450200051332","000"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID));
        assertThatThrownBy(()->ConsolePaymentValidation.sepa("Example","ES97000G12345678","ES9121000418450200051332","000")).isInstanceOf(ApiException.class);
        assertThatCode(()->ConsolePaymentValidation.sepa("Example","ES26000G12345678","ES9121000418450200051332","000")).doesNotThrowAnyException();
        assertThatCode(()->ConsolePaymentValidation.sepa("Example","es26 zzz g12345678","ES9121000418450200051332","000")).doesNotThrowAnyException();
        assertThatCode(()->ConsolePaymentValidation.sepa("Example","ES64ZZZ999999999","ES9121000418450200051332","000")).doesNotThrowAnyException();
    }
}

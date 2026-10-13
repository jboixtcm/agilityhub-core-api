package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConsolePaymentValidationTest {
    @Test void T_17_05_R_17_05_sepaChecksBankChecksumAndCreditorFormat() {
        ConsolePaymentValidation.sepa("Example","ES97000G12345678","es91 2100 0418 4502 0005 1332","000");
        for(String invalid:new String[]{null,"","ES0000000000000000000000","1234"}) assertThat(ConsolePaymentValidation.iban(invalid)).isFalse();
        for(String name:new String[]{null,""}) assertThatThrownBy(()->ConsolePaymentValidation.sepa(name,"ES97000G12345678","ES9121000418450200051332","000")).isInstanceOf(ApiException.class);
        for(String creditor:new String[]{null,"invalid"}) assertThatThrownBy(()->ConsolePaymentValidation.sepa("Example",creditor,"ES9121000418450200051332","000")).isInstanceOf(ApiException.class);
        for(String suffix:new String[]{null,"0000"}) assertThatThrownBy(()->ConsolePaymentValidation.sepa("Example","ES97000G12345678","ES9121000418450200051332",suffix)).isInstanceOf(ApiException.class);
        assertThat(ConsolePaymentValidation.iban("GB82WEST12345698765432")).isTrue();
    }
}

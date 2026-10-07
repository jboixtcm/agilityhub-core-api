package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.payments.application.BillingExpirations;
import com.agilityhub.core.platform.application.Module;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** P5 a/i adapter; the payment context keeps ownership of balances, remittances and collections. */
@Configuration(proxyBeanMethods = false)
public class BillingExpirationSteps {
    @Bean ExpirationStep packExpirationStep(BillingExpirations billing) {
        return new CensusExpirationSteps.Step('a', Module.PACKS, Set.of("WARN_PACK", "EXPIRE_PACK"),
                Set.of("warnedPacks", "expiredPacks"), billing::packs, billing::apply);
    }
    @Bean ExpirationStep remittanceSettlementStep(BillingExpirations billing) {
        return new CensusExpirationSteps.Step('i', Module.BILLING, Set.of("SETTLE"), Set.of("settledInvoices"), billing::remittances, billing::apply);
    }
}

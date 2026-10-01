package com.agilityhub.core.migration.application;

import com.agilityhub.core.payments.application.BankAccountVault;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Component;

/**
 * Encrypted handoff to S12; no mandate is issued by the census importer. Ruling E85 (clarifies E43): `BILLING_BANK_KEY` is the
 * only bank key, so the imported IBANs are encrypted by S12's {@link BankAccountVault} itself (same key, cipher and associated
 * data `"{clubId}:{memberId}"`), the one the SEPA writer decrypts with.
 */
@Component
public class MigrationBankVault {
    private final BankAccountVault vault;
    public MigrationBankVault(BankAccountVault vault) { this.vault=vault; }
    /** An apply with bank data needs `BILLING_BANK_KEY`: without a usable key the run stops before writing anything. */
    public void requireKey() { if (!vault.configured()) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); } }
    public String encrypt(String iban,String clubId,String memberId) { return vault.encrypt(iban,clubId,memberId); }
}

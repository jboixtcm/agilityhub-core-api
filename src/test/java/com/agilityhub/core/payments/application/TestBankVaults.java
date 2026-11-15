package com.agilityhub.core.payments.application;

/**
 * Tests' vaults (E11-T11): the permissive {@link BankAccountVault} of the local stack, which keeps an IBAN in clear without a
 * key, is built here only, so production code cannot pick it.
 */
public final class TestBankVaults {
    private TestBankVaults() { }
    public static BankAccountVault of(String key) { return new BankAccountVault(key, true); }
    /** PRIV-11: a vault in the middle of a rotation, `BILLING_BANK_KEY` = {@code key}, `BILLING_BANK_KEY_PREVIOUS` = {@code previous}. */
    public static BankAccountVault of(String key, String previous) { return new BankAccountVault(key, previous, true); }
}

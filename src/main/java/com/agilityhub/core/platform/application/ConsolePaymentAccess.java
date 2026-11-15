package com.agilityhub.core.platform.application;

/** Payment-owned encryption, validation and the bounded Stripe connection probe. Never exposes stored secrets. */
public interface ConsolePaymentAccess {
    /** {@code accountId} is the connected Stripe account's identifier (`acct_…`), stored only to show its masked form. */
    record Connection(boolean ok,String accountId,String accountName,String mode) { }
    String encrypt(String value,String club,String provider,String field);
    String decrypt(String value,String club,String provider,String field);
    void validateSepa(String name,String creditor,String iban,String suffix);
    Connection ping();
    /** PAY-10 (S17 R-17-05): the connection test of a Stripe secret key about to be saved, with that key and mode; throws when it fails. */
    Connection ping(String mode,String secretKey);
}

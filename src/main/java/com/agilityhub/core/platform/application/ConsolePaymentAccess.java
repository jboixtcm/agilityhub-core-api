package com.agilityhub.core.platform.application;

/** Payment-owned encryption, validation and the bounded Stripe connection probe. Never exposes stored secrets. */
public interface ConsolePaymentAccess {
    record Connection(boolean ok,String accountName,String mode) { }
    String encrypt(String value,String club,String provider,String field);
    String decrypt(String value,String club,String provider,String field);
    void validateSepa(String name,String creditor,String iban,String suffix);
    Connection ping();
}

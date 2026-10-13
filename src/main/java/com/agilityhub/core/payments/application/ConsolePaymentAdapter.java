package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ConsolePaymentAccess;
import org.springframework.stereotype.Component;

@Component
public class ConsolePaymentAdapter implements ConsolePaymentAccess {
    private final ProviderSecretVault vault;private final PaymentProviderRegistry providers;
    public ConsolePaymentAdapter(ProviderSecretVault vault,PaymentProviderRegistry providers) {this.vault=vault;this.providers=providers;}
    public String encrypt(String value,String club,String provider,String field) {return vault.encrypt(value,club,provider,field);}
    public String decrypt(String value,String club,String provider,String field) {return vault.decrypt(value,club,provider,field);}
    public void validateSepa(String name,String creditor,String iban,String suffix) {ConsolePaymentValidation.sepa(name,creditor,iban,suffix);}
    public Connection ping() {var result=providers.ping();return new Connection(result.ok(),result.accountName(),result.mode());}
}

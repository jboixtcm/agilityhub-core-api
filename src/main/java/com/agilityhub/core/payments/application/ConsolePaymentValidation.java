package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.domain.*;

/**
 * S17 reuses the bank syntax checked by the payment boundary. R-17-05 (E11-T15 step 8): the creditor identifier's shape and its
 * mod 97-10 check digits ({@link SepaCreditorIdentifier}), so «ES00ZZZG12345678» is refused before the bank refuses the file.
 */
public final class ConsolePaymentValidation {
    private ConsolePaymentValidation() { }
    public static void sepa(String name,String creditor,String iban,String suffix) {
        if(name==null||name.isBlank()||!SepaCreditorIdentifier.valid(creditor)
                ||suffix==null||!suffix.matches("[A-Za-z0-9]{3}")||!iban(iban)) throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID);
    }
    static boolean iban(String value) {
        if(value==null) return false;String iban=value.replaceAll("\\s","").toUpperCase(java.util.Locale.ROOT);
        if(!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) return false;
        String moved=iban.substring(4)+iban.substring(0,4);int remainder=0;
        for(char c:moved.toCharArray()) { int n=Character.isDigit(c)?c-'0':c-'A'+10;remainder=(remainder*(n<10?10:100)+n)%97; }
        return remainder==1;
    }
}

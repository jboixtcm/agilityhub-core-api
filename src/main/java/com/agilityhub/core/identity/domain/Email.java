package com.agilityhub.core.identity.domain;

import java.text.Normalizer;
import java.util.Locale;

public final class Email {
    private Email() { }
    public static String normalize(String email) {
        return Normalizer.normalize(email.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}

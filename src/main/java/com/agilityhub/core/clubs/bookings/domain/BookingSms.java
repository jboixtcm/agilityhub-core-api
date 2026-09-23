package com.agilityhub.core.clubs.bookings.domain;

import java.text.Normalizer;

/** N-36 SMS body: GSM-7 safe characters only, at most 160 of them (same rule as the S06 N-08a/b intents). */
public final class BookingSms {
    private BookingSms() { }
    public static String compact(String text) {
        String ascii = Normalizer.normalize(text.replace("…", "...").replace("’", "'"), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 @!\"#%&'()*+,./:;<=>?_$\\-\\r\\n]", " ");
        return ascii.length() <= 160 ? ascii : ascii.substring(0, 157) + "...";
    }
}
